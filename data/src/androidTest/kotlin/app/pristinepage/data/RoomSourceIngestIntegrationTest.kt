package app.pristinepage.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.files.InspectedSourceForIngest
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Current SourceStore and real Android Room/private filesystem. Provider bytes/normalized inspection are synthetic; these four bytes are not a PNG. Close/reopen is not process death, this is not decoder/provider acceptance, and no retained database/file is deleted. */
@RunWith(AndroidJUnit4::class)
class RoomSourceIngestIntegrationTest {
    @Test fun currentSourceStoreCommitsReopensAndReplaysWithoutProviderOrDuplicatePins() = runBlocking {
        Fixture().use { f ->
            val child = f.reserve().single()
            val first = f.store.ingest(f.reference, child) as IngestResult.Committed
            val intent = RoomIngestLookup(f.database).find(child)!!
            assertEquals(0L, f.count("SourceCaptureDay"))
            // A later projection must not retroactively change or invalidate a v1 ingest receipt.
            f.database.openHelper.writableDatabase.execSQL("INSERT INTO SourceCaptureDay VALUES (?,?,19782)",
                arrayOf(intent.sourceBlobId.value, first.sourceByteHash))
            assertEquals(1, f.opened); assertEquals(1, f.closed)
            f.reopen()
            assertEquals(first, f.store.ingest(f.reference, child))
            assertTrue(f.database.ingestCompletion().captureProjectionMatches(intent.sourceBlobId.value, first.sourceByteHash, 19782))
            assertEquals(1, f.opened); assertEquals(1L, f.count("Page")); assertEquals(2L, f.count("SourceReference"))
            assertEquals("COMMITTED", f.database.importSessions().session(child.value)!!.state)
            assertNull(f.database.importSessions().session(child.value)!!.uriText)
            assertEquals(2L, f.database.catalog().source(first.sourceId.value)!!.refCount)
            val job = f.database.jobLeases().job(intent.jobId.value)!!
            assertEquals("SUCCEEDED", job.state); assertNull(job.leaseOwner)
            assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
            f.assertForeignKeys()
        }
    }

    @Test fun actualSqliteRecipeFailureRollsBackEveryVisibilityRowAndKeepsMovedBytes() = runBlocking {
        Fixture().use { f ->
            val child = f.reserve().single()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_ingest_recipe BEFORE INSERT ON RecipeRevision BEGIN SELECT RAISE(ABORT,'synthetic ingest write fault'); END")
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            f.reopen()
            for (table in listOf("Page", "Source", "SourceBlob", "RecipeRevision", "SourceReference")) assertEquals(0L, f.count(table))
            assertEquals(1L, f.database.catalog().document(f.document.value)!!.documentRevision)
            val intent = RoomIngestLookup(f.database).find(child)!!
            assertEquals("MOVED", f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!.state)
            assertEquals("RUNNING", f.database.jobLeases().job(intent.jobId.value)!!.state)
            assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            assertEquals(1, f.opened)
            f.assertForeignKeys()
        }
    }

    @Test fun captureProjectionKeepsExactDigestAcrossRoomReopenAndRefusesChangedDay() = runBlocking {
        Fixture(captureDate = SourceCaptureDate(19782)).use { f ->
            val child = f.reserve().single()
            val saved = f.store.ingest(f.reference, child) as IngestResult.Committed
            val intent = RoomIngestLookup(f.database).find(child)!!
            val id = intent.sourceBlobId.value
            f.reopen()
            assertEquals(saved, f.store.ingest(f.reference, child))
            val sql = f.database.openHelper.writableDatabase
            sql.query("SELECT byteSha256,epochDay FROM SourceCaptureDay WHERE sourceBlobId=?", arrayOf(id)).use {
                assertTrue(it.moveToFirst()); assertEquals(saved.sourceByteHash, it.getString(0)); assertEquals(19782L, it.getLong(1))
            }
            sql.execSQL("UPDATE SourceCaptureDay SET epochDay=19783 WHERE sourceBlobId=?", arrayOf(id))
            val refused = f.store.ingest(f.reference, child) as IngestResult.Rejected
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, refused.error.code)
            assertEquals("ingest_capture_date_mismatch", refused.error.safeMessageKey)
            assertEquals(1, f.opened)
            assertArrayEquals(f.bytes, f.files.finalFile(id).readBytes())
        }
    }

    @Test fun lateSqliteCorruptionOfCoverageRollsBackBothProjectionAndPage() = runBlocking {
        Fixture(captureDate = SourceCaptureDate(null)).use { f ->
            val child = f.reserve().single()
            f.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER corrupt_source_date AFTER UPDATE OF state ON Job
                WHEN NEW.state='SUCCEEDED' BEGIN UPDATE SourceCaptureDay SET epochDay=19782; END""")
            val refused = f.store.ingest(f.reference, child) as IngestResult.Rejected
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, refused.error.code)
            assertEquals("ingest_capture_date_mismatch", refused.error.safeMessageKey)
            val intent = RoomIngestLookup(f.database).find(child)!!
            assertEquals("MOVED", f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!.state)
            assertEquals("RUNNING", f.database.jobLeases().job(intent.jobId.value)!!.state)
            for (table in listOf("Page", "Source", "SourceBlob", "RecipeRevision", "SourceReference", "SourceCaptureDay"))
                assertEquals(0L, f.count(table))
            assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER corrupt_source_date")
            f.reopen()
            val recovery = RoomRecoveryCoordinator(f.database, f.files, f.owners)
            assertEquals(1L, recovery.reconcile().completed)
            assertEquals(0L, recovery.reconcile().completed)
            val saved = f.store.ingest(f.reference, child) as IngestResult.Committed
            assertEquals(1L, f.count("Page"))
            assertEquals(1L, f.count("SourceCaptureDay"))
            assertEquals(1, f.opened)
            assertTrue(f.database.ingestCompletion().captureProjectionMatches(intent.sourceBlobId.value, saved.sourceByteHash, null))
            assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
            f.assertForeignKeys()

            val sql = f.database.openHelper.writableDatabase
            for (assignment in listOf("byteSha256=CAST(byteSha256 AS BLOB)", "epochDay=2.5", "byteSha256='${"e".repeat(64)}'")) {
                sql.execSQL("UPDATE SourceCaptureDay SET $assignment WHERE sourceBlobId=?", arrayOf(intent.sourceBlobId.value))
                val replay = f.store.ingest(f.reference, child) as IngestResult.Rejected
                assertEquals("ingest_capture_date_mismatch", replay.error.safeMessageKey)
                sql.execSQL("UPDATE SourceCaptureDay SET byteSha256=?,epochDay=NULL WHERE sourceBlobId=?",
                    arrayOf(saved.sourceByteHash, intent.sourceBlobId.value))
            }
            sql.execSQL("DELETE FROM SourceCaptureDay WHERE sourceBlobId=?", arrayOf(intent.sourceBlobId.value))
            assertEquals("ingest_capture_date_mismatch", (f.store.ingest(f.reference, child) as IngestResult.Rejected).error.safeMessageKey)
        }
    }

    @Test fun reverseCompletionInNonemptyAnchoredBatchKeepsReservedOrderWithRealConstraints() = runBlocking {
        Fixture().use { f ->
            val a = f.store.ingest(f.reference, f.reserve().single()) as IngestResult.Committed
            val b = f.store.ingest(f.reference, f.reserve().single()) as IngestResult.Committed
            val batch = f.reserve(2, a.pageId)
            val last = f.store.ingest(f.reference, batch[1]) as IngestResult.Committed
            val first = f.store.ingest(f.reference, batch[0]) as IngestResult.Committed
            f.reopen()
            val ordered = f.database.catalog().activePages(f.document.value)
            assertEquals(listOf(a.pageId.value, first.pageId.value, last.pageId.value, b.pageId.value), ordered.map { it.pageId })
            assertEquals(listOf(0, 1, 2, 3), ordered.map { it.ordinal })
            assertEquals(5L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertEquals(4L, f.count("SourceBlob")); assertEquals(8L, f.count("SourceReference"))
            f.assertForeignKeys()
        }
    }

    @Test fun ignoredSealJournalUpdateRetainsStageAndRepeatedCallbackNeverReopensProvider() = runBlocking {
        Fixture().use { f ->
            val child = f.reserve().single()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER ignore_ingest_seal BEFORE UPDATE ON FileTransaction WHEN NEW.state='SEALED' BEGIN SELECT RAISE(IGNORE); END")
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            val intent = RoomIngestLookup(f.database).find(child)!!
            val row = f.database.ingestPreparation().fileTransaction(intent.transactionId.value)!!
            assertEquals("COPYING", row.state)
            assertFalse(f.files.finalFile(intent.sourceBlobId.value).exists())
            assertTrue(File(f.root, row.stagingPath!!).isFile)
            f.reopen()
            assertTrue(f.store.ingest(f.reference, child) is IngestResult.Pending)
            assertEquals(1, f.opened); assertEquals(1, f.closed); assertEquals(0L, f.count("Page"))
            f.assertForeignKeys()
        }
    }

    @Test fun terminalAndMixedFailuresSettleRealRoomSessionsAndPermitTheNextBatch() = runBlocking {
        for (mixed in listOf(false, true)) Fixture().use { f ->
            val children = f.reserve(if (mixed) 2 else 1)
            f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.UNSUPPORTED_FORMAT, false, "synthetic_unsupported_input", children[0].value))
            assertTrue(f.store.ingest(f.reference, children[0]) is IngestResult.Rejected)
            f.inspectionFailure = null
            if (mixed) assertTrue(f.store.ingest(f.reference, children[1]) is IngestResult.Committed)
            f.reopen()
            val first = f.database.importSessions().session(children[0].value)!!
            assertEquals("FAILED", first.state); assertNull(first.uriText)
            assertEquals(if (mixed) "COMMITTED" else "FAILED", f.database.importSessions().session(first.parentSessionId!!)!!.state)
            assertEquals(if (mixed) 1L else 0L, f.count("Page"))
            assertEquals(1, f.reserve().size)
            f.assertForeignKeys()
        }
    }

    internal class Fixture(private val enableRetry: Boolean = false, directorySync: DirectorySync? = null,
        private val captureDate: SourceCaptureDate? = null,
    ) : AutoCloseable {
        private val context: Context = InstrumentationRegistry.getInstrumentation().context
        private val name = "t03-ingest-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, "t03-ingest-${UUID.randomUUID()}")
        val files = if (directorySync == null) SourceFiles(root) else SourceFiles(root, directorySync)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val reference = Uri.parse("content://fixture.documents/synthetic-source")
        val owners = LocalOperationOwners()
        var opened = 0; var closed = 0
        var inspectionFailure: RepositoryFailure? = null
        var providerFailure: RuntimeException? = null
        var database = open()
            private set
        val document = runBlocking { RoomDocumentRepository(RoomMetadataAccess(database), RecipeAdmission { _, _ -> null }).createDocument("Synthetic ingest integration") }
        var store = store()
            private set

        suspend fun reserve(count: Int = 1, anchor: PageId? = null): List<ImportSessionId> {
            val commands = RoomImportSessions(database)
            val revision = database.catalog().document(document.value)!!.documentRevision
            val parent = commands.beginImport(document, revision, anchor).parentId
            val batch = commands.reserveImportItems(parent, count)
            for (ordinal in batch.childIds.indices) commands.bindImportItem(parent, ordinal, reference)
            return batch.childIds
        }

        private fun open() = Room.databaseBuilder(context, PristineDatabase::class.java, name).build()
        private fun store(): SourceStore {
            val provider = SourceInput { bound ->
                assertFalse(database.inTransaction()); assertEquals(reference.toString(), bound); opened++
                providerFailure?.let { throw it }
                object : ByteArrayInputStream(bytes) { override fun close() { closed++; super.close() } }
            }
            val normalizedInspector = SourceInspector { file, hash ->
                assertFalse(database.inTransaction()); assertEquals(opened, closed)
                assertArrayEquals(bytes, file.readBytes()); assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", hash)
                inspectionFailure?.let { throw it }
                SourceInspection("image/png", PixelSize(3, 2), PixelSize(3, 2), 1, null, "synthetic-v1", "a".repeat(64))
            }
            val inspector = if (captureDate == null) normalizedInspector else object : SourceInspector {
                override suspend fun inspect(file: File, expectedByteSha256: String) = normalizedInspector.inspect(file, expectedByteSha256)
                override suspend fun inspectForIngest(file: File, expectedByteSha256: String): InspectedSourceForIngest =
                    InspectedSourceForIngest(inspect(file, expectedByteSha256), captureDate)
            }
            val completion = RoomIngestCompletion(database, files)
            return RoomSourceStore(files, provider, inspector, RoomIngestPreparation(database), RoomIngestLookup(database), completion, completion, RoomIngestPhases(database), RoomJobLeases(database, owners), owners, restarts = if (enableRetry) RoomIngestRestarts(database, files, owners) else null)
        }
        fun reopen() { database.close(); database = open(); store = store() }
        fun count(table: String): Long {
            require(table in setOf("Page", "Source", "SourceBlob", "RecipeRevision", "SourceReference", "SourceCaptureDay"))
            return database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        }
        fun assertForeignKeys() { database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) } }
        override fun close() { database.close() }
    }
}
