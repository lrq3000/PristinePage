package app.pristinepage.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual self-owned child-process termination while the production ingest/Room/WAL/file protocol is paused. Synthetic provider/inspection remain explicit; neither DB reopen nor a local thrown Error is the interruption mechanism. All test databases/files are retained. */
@RunWith(AndroidJUnit4::class)
class IngestProcessDeathIntegrationTest {
    @Test fun ownedIntentBeforeCopySurvivesActualProcessTermination() = runBlocking { exercise(IngestCrashPoint.OWNED_INTENT) }
    @Test fun partialCopyFromTerminatedProcessIsRetainedWhileFreshAttemptCommits() = runBlocking { exercise(IngestCrashPoint.PARTIAL) }
    @Test fun actualDeathAfterSealBeforeJournalConsumesTheVerifiedBytesOnce() = runBlocking { exercise(IngestCrashPoint.SEALED) }
    @Test fun actualDeathAfterMoveBeforeJournalCommitsTheSameFinalSourceOnce() = runBlocking { exercise(IngestCrashPoint.MOVED) }
    @Test fun actualDeathInsideVisibilityTransactionRollsBackWalThenRecoversOnce() = runBlocking { exercise(IngestCrashPoint.IN_METADATA) }
    @Test fun actualDeathAfterJointCommitReplaysTheOriginalReceiptWithoutCopy() = runBlocking { exercise(IngestCrashPoint.COMMITTED) }

    @Test fun expiredGateCannotBeAcceptedAsAnInterruptedVisibilityTransaction() = runBlocking {
        var accepted = false
        try { exercise(IngestCrashPoint.IN_METADATA, delayedHaltMillis = 65_000L); accepted = true }
        catch (failure: AssertionError) { if (failure.message != "ingest_crash_gate_expired") throw failure }
        assertFalse("An expired/unwound gate was accepted as a live transaction interrupted by HALT", accepted)
    }

    @Test fun incorrectStoredReferenceCountCannotPassThePostCrashEvidenceChecks() = runBlocking {
        var accepted = false
        try { exercise(IngestCrashPoint.OWNED_INTENT, corruptStoredRefCount = true); accepted = true }
        catch (failure: AssertionError) { if (!failure.message.orEmpty().contains("stored_source_refcount")) throw failure }
        assertFalse("Wrong stored refCount passed the claimed post-crash reference evidence", accepted)
    }

    private suspend fun exercise(point: IngestCrashPoint, delayedHaltMillis: Long = 0, corruptStoredRefCount: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val token = UUID.randomUUID().toString(); val root = IngestCrashSupport.root(context, token)
        var database = IngestCrashSupport.open(context, token)
        val document = RoomDocumentRepository(RoomMetadataAccess(database), RecipeAdmission { _, _ -> null }).createDocument("Synthetic process-death ingest")
        val sessions = RoomImportSessions(database); val parent = sessions.beginImport(document, 1, null).parentId
        val child = sessions.reserveImportItems(parent, 1).childIds.single()
        sessions.bindImportItem(parent, 0, android.net.Uri.parse(IngestCrashSupport.REFERENCE))
        val original = RoomIngestPreparation(database).prepare(child, IngestCrashSupport.REFERENCE)
        assertEquals("wal", IngestCrashSupport.journalMode(database))
        // Keep this existing read connection initialized before the child pauses inside its writer. Opening/schema configuration while that writer is paused could itself wait for the lock we intend to interrupt.
        val files = SourceFiles(root); val owners = FileOperationOwners(root)
        val connected = CompletableDeferred<Messenger>(); val ready = CompletableDeferred<Bundle>(); val dead = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<String>(); val acknowledged = CompletableDeferred<Bundle>(); val haltAccepted = AtomicBoolean(false)
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                IngestCrashMessages.READY -> ready.complete(message.data)
                IngestCrashMessages.FAILED -> {
                    val reason = message.data.getString("reason") ?: "ingest_crash_fixture_failed"
                    failure.complete(reason); ready.completeExceptionally(AssertionError(reason)); acknowledged.completeExceptionally(AssertionError(reason))
                }
                IngestCrashMessages.HALT_ACK -> { haltAccepted.set(true); acknowledged.complete(message.data) }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { service.linkToDeath({ dead.complete(Unit) }, 0); connected.complete(Messenger(service)) }
            override fun onServiceDisconnected(name: ComponentName) { dead.complete(Unit) }
            override fun onBindingDied(name: ComponentName) { dead.complete(Unit) }
        }
        var remote: Messenger? = null
        val bound = context.bindService(Intent(context, IngestCrashFixtureService::class.java).putExtra("token", token).putExtra("session", child.value).putExtra("point", point.name), connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue("The non-exported operation-owned ingest fixture did not bind", bound)
            remote = withTimeout(15000) { connected.await() }
            remote.send(Message.obtain(null, IngestCrashMessages.START).apply { replyTo = reply })
            val reached = withTimeout(20000) { ready.await() }
            if (delayedHaltMillis > 0) {
                // Await the actual expiry signal, not a guessed sleep-to-expiry relationship. A delayed/unarmed HALT must not produce a success ACK, even after READY was already delivered.
                val reason = withTimeout(delayedHaltMillis + 15000L) { failure.await() }
                try { remote.send(Message.obtain(null, IngestCrashMessages.HALT)) } catch (_: RemoteException) { /* Expired fixture may already have self-terminated. */ }
                withTimeout(15000) { dead.await() }
                assertFalse("Expired gate acknowledged a later HALT as armed", haltAccepted.get())
                throw AssertionError(reason)
            }
            assertFalse("Fixture failed after READY", failure.isCompleted)
            assertEquals(point.name, reached.getString("point")); assertNotEquals(android.os.Process.myPid(), reached.getInt("pid"))
            assertEquals(point == IngestCrashPoint.IN_METADATA, reached.getBoolean("inTransaction")); assertEquals("wal", reached.getString("journalMode"))
            val oldOwner = checkNotNull(reached.getString("owner")); UUID.fromString(oldOwner)
            assertEquals(LeaseOwnerState.ALIVE, owners.refresh(oldOwner)); assertEquals(LeaseOwnerState.UNKNOWN, owners.state(oldOwner))
            val before = database.ingestPreparation().fileTransaction(original.transactionId.value)!!
            val state = when (point) {
                IngestCrashPoint.OWNED_INTENT -> "INTENT"
                IngestCrashPoint.PARTIAL, IngestCrashPoint.SEALED -> "COPYING"
                IngestCrashPoint.MOVED -> "SEALED"
                IngestCrashPoint.IN_METADATA -> "MOVED"
                IngestCrashPoint.COMMITTED -> "COMMITTED"
            }
            assertEquals(state, before.state)
            assertEquals(if (point == IngestCrashPoint.COMMITTED) 1L else 0L, count(database, "Page"))
            if (point == IngestCrashPoint.IN_METADATA) for (table in listOf("Source", "SourceBlob", "RecipeRevision", "SourceReference")) assertEquals(0L, count(database, table))
            val partial = File(root, "staging/${original.transactionId.value}/original.part")
            if (point == IngestCrashPoint.PARTIAL) assertArrayEquals(byteArrayOf(1), partial.readBytes())
            if (point in setOf(IngestCrashPoint.MOVED, IngestCrashPoint.IN_METADATA, IngestCrashPoint.COMMITTED)) assertArrayEquals(IngestCrashSupport.BYTES, files.finalFile(original.sourceBlobId.value).readBytes())
            remote.send(Message.obtain(null, IngestCrashMessages.HALT))
            val ack = withTimeout(15000) { acknowledged.await() }
            assertEquals(point.name, ack.getString("point")); assertEquals(reached.getInt("pid"), ack.getInt("pid"))
            withTimeout(15000) { dead.await() }
            assertFalse("The accepted gate expired or failed before self-termination", failure.isCompleted)
            // Native exclusive lock/initialized-marker proof, not Binder death or elapsed time, authorizes the new process owner.
            withTimeout(15000) { while (owners.refresh(oldOwner) != LeaseOwnerState.ENDED) delay(25) }
            database.close(); database = IngestCrashSupport.open(context, token)
            assertEquals("wal", IngestCrashSupport.journalMode(database))
            if (point == IngestCrashPoint.IN_METADATA) for (table in listOf("Page", "Source", "SourceBlob", "RecipeRevision", "SourceReference")) assertEquals(0L, count(database, table))
            var opened = 0
            val provider = SourceInput { reference -> assertEquals(IngestCrashSupport.REFERENCE, reference); opened++; ByteArrayInputStream(IngestCrashSupport.BYTES) }
            val store = IngestCrashSupport.store(database, files, owners, provider)
            val recovery = RoomRecoveryCoordinator(database, files, owners, store)
            assertEquals(if (point == IngestCrashPoint.COMMITTED) 0L else 1L, recovery.reconcile().completed)
            if (corruptStoredRefCount) {
                // Deliberately alter only this unique synthetic fixture after real recovery. A later evidence check must reject it; no reclamation or original-file mutation is performed.
                val source = database.catalog().activePages(document.value).single().sourceId
                RoomMetadataAccess(database).transaction { assertEquals(1, database.catalog().setSourceRefCount(source, 0)) }
            }
            val current = RoomIngestLookup(database).find(child)!!
            val firstCatalog = catalogSnapshot(database, document, current)
            assertEquals(0L, recovery.reconcile().completed)
            assertEquals("Second recovery changed catalog state", firstCatalog, catalogSnapshot(database, document, current))
            assertEquals(original.pageId, current.pageId); assertEquals(1L, count(database, "Page")); assertEquals(2L, count(database, "SourceReference")); assertEquals(1L, count(database, "RecipeRevision"))
            assertEquals(2L, database.catalog().document(document.value)!!.documentRevision)
            assertArrayEquals(IngestCrashSupport.BYTES, files.finalFile(current.sourceBlobId.value).readBytes())
            val result = store.ingestBoundProvider(IngestCrashSupport.REFERENCE, child) as IngestResult.Committed
            assertEquals("Committed callback changed catalog state", firstCatalog, catalogSnapshot(database, document, current))
            assertEquals(original.pageId, result.pageId); assertEquals(IngestCrashSupport.BYTE_HASH, result.sourceByteHash)
            assertEquals(if (point in setOf(IngestCrashPoint.OWNED_INTENT, IngestCrashPoint.PARTIAL)) 1 else 0, opened)
            assertEquals("SUCCEEDED", database.jobLeases().job(current.jobId.value)!!.state)
            if (point == IngestCrashPoint.PARTIAL) {
                assertEquals(2L, current.attempt); assertNotEquals(original.transactionId, current.transactionId)
                assertEquals("QUARANTINED", database.ingestPreparation().fileTransaction(original.transactionId.value)!!.state)
                assertEquals("INTERRUPTED", database.jobLeases().job(original.jobId.value)!!.state); assertArrayEquals(byteArrayOf(1), partial.readBytes())
            } else assertEquals(original, current)
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        } finally {
            try { remote?.send(Message.obtain(null, IngestCrashMessages.HALT)) } catch (_: RemoteException) { /* The already-dead fixture is not a new kill target. */ }
            if (bound) context.unbindService(connection)
            database.close()
        }
    }

    private fun count(database: PristineDatabase, table: String): Long {
        require(table in setOf("Page", "Source", "SourceBlob", "RecipeRevision", "SourceReference"))
        return database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
    }

    /** Validate independently specified ownership/bindings, then hash every bounded row/cell (including journals/jobs/sessions) so repeated recovery/replay cannot silently change counters, history, pointers or audit fields. Private string/BLOB values are represented only by length/hash in a failing snapshot comparison. */
    private fun catalogSnapshot(database: PristineDatabase, document: DocumentId, intent: IngestJournalIntent): Map<String, List<List<String>>> {
        for (table in listOf("Page", "Source", "SourceBlob", "RecipeRevision")) assertEquals("catalog_cardinality_$table", 1L, count(database, table))
        val queries = database.catalog(); val page = queries.activePages(document.value).single()
        assertEquals(intent.pageId.value, page.pageId); assertEquals(document.value, page.documentId); assertEquals(intent.sourceId.value, page.sourceId)
        assertEquals(0, page.ordinal); assertEquals("ACTIVE", page.state); assertEquals(1L, page.currentRecipeRevision)
        val source = checkNotNull(queries.source(page.sourceId)); val blob = checkNotNull(database.ingestCompletion().sourceBlob(intent.sourceBlobId.value))
        assertEquals("stored_source_refcount", 2L, source.refCount); assertEquals(queries.countSourceReferences(source.sourceId), source.refCount)
        assertEquals("stored_blob_sourcecount", 1L, blob.sourceCount)
        val retainedSources = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM Source WHERE sourceBlobId=?", arrayOf(blob.sourceBlobId)).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        assertEquals(retainedSources, blob.sourceCount); assertEquals(blob.sourceBlobId, source.sourceBlobId)
        assertEquals("READY", source.state); assertEquals("READY", blob.state); assertEquals(IngestCrashSupport.BYTE_HASH, blob.byteSha256); assertEquals(4L, blob.byteCount)
        assertEquals(intent.transactionId.value, blob.ingestTransactionId); assertEquals("sources/${intent.sourceBlobId.value}/original.bin", blob.relativePath)
        val recipe = checkNotNull(queries.recipe(page.pageId, page.currentRecipeRevision)); val decoded = RecipeCodec.decode(recipe.canonicalRecipe)
        assertEquals(source.sourceId, recipe.sourceId); assertEquals(RecipeCodec.hash(decoded), recipe.recipeSha256); assertArrayEquals(RecipeCodec.encode(decoded), recipe.canonicalRecipe)
        assertEquals(SourceBinding(source.pixelSha256, PixelSize(source.width, source.height), source.conversionFingerprint), decoded.geometry.source)
        val expectedReferences = setOf(listOf("PAGE", page.pageId, source.sourceId), listOf("RECIPE_HISTORY", "recipe-v1/${page.pageId}/1", source.sourceId))
        val references = database.openHelper.readableDatabase.query("SELECT ownerType,ownerId,sourceId FROM SourceReference").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(List(3) { cursor.getString(it) }) }
        }
        assertEquals(expectedReferences, references); assertEquals(2L, count(database, "SourceReference"))
        val order = linkedMapOf("Document" to "documentId", "Page" to "pageId", "Source" to "sourceId", "SourceBlob" to "sourceBlobId", "RecipeRevision" to "pageId,recipeRevision", "SourceReference" to "ownerType,ownerId,sourceId", "ImportSession" to "sessionId", "FileTransaction" to "transactionId", "Job" to "jobId")
        return order.mapValues { (table, keys) ->
            database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY $keys").use { cursor ->
                val rows = mutableListOf<List<String>>()
                while (cursor.moveToNext()) {
                    check(rows.size < 4) // One generated ITEM; a partial retry retains exactly two journals/jobs, not an unbounded catalog.
                    rows += List(cursor.columnCount) { column ->
                        val type = cursor.getType(column)
                        if (type == android.database.Cursor.FIELD_TYPE_NULL) "null"
                        else {
                            val bytes = if (type == android.database.Cursor.FIELD_TYPE_BLOB) cursor.getBlob(column) else cursor.getString(column).toByteArray(Charsets.UTF_8)
                            "$type:${bytes.size}:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                        }
                    }
                }
                rows
            }
        }
    }
}
