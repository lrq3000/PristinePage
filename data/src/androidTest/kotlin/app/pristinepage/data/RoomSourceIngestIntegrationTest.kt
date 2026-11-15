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
            assertEquals(1, f.opened); assertEquals(1, f.closed)
            f.reopen()
            assertEquals(first, f.store.ingest(f.reference, child))
            assertEquals(1, f.opened); assertEquals(1L, f.count("Page")); assertEquals(2L, f.count("SourceReference"))
            assertEquals("COMMITTED", f.database.importSessions().session(child.value)!!.state)
            assertNull(f.database.importSessions().session(child.value)!!.uriText)
            assertEquals(2L, f.database.catalog().source(first.sourceId.value)!!.refCount)
            val intent = RoomIngestLookup(f.database).find(child)!!
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

    internal class Fixture : AutoCloseable {
        private val context: Context = InstrumentationRegistry.getInstrumentation().context
        private val name = "t03-ingest-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, "t03-ingest-${UUID.randomUUID()}")
        val files = SourceFiles(root)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val reference = Uri.parse("content://fixture.documents/synthetic-source")
        val owners = LocalOperationOwners()
        var opened = 0; var closed = 0
        var inspectionFailure: RepositoryFailure? = null
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
                object : ByteArrayInputStream(bytes) { override fun close() { closed++; super.close() } }
            }
            val inspector = SourceInspector { file, hash ->
                assertFalse(database.inTransaction()); assertEquals(opened, closed)
                assertArrayEquals(bytes, file.readBytes()); assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", hash)
                inspectionFailure?.let { throw it }
                SourceInspection("image/png", PixelSize(3, 2), PixelSize(3, 2), 1, null, "synthetic-v1", "a".repeat(64))
            }
            val completion = RoomIngestCompletion(database, files)
            return RoomSourceStore(files, provider, inspector, RoomIngestPreparation(database), RoomIngestLookup(database), completion, completion, RoomIngestPhases(database), RoomJobLeases(database, owners), owners)
        }
        fun reopen() { database.close(); database = open(); store = store() }
        fun count(table: String): Long {
            require(table in setOf("Page", "Source", "SourceBlob", "RecipeRevision", "SourceReference"))
            return database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        }
        fun assertForeignKeys() { database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) } }
        override fun close() { database.close() }
    }
}
