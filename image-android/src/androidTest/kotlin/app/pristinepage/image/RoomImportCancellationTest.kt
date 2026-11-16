package app.pristinepage.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.RoomImportSessions
import app.pristinepage.data.repository.RoomIngestLookup
import app.pristinepage.image.EncodedImageFixtures.rgbaPng
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room/file/codec cancellation fence, not real picker/grant/OS process death or proof of interrupting blocked provider IO. Every synthetic source/journal/private file is retained. */
@RunWith(AndroidJUnit4::class)
class RoomImportCancellationTest {
    @Test fun abandonmentAfterActualDecodeCannotPublishALatePageOrDeleteStaging() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val sessions = RoomImportSessions(f.database); val parent = sessions.beginImport(f.document, 1, null).parentId
            val selected = sessions.prepareSelection(parent, listOf(f.reference.toString())); val child = selected.childIds.single()
            f.afterInspection = { assertEquals(selected, sessions.cancelBatch(parent)) }
            val result = f.store.ingest(f.reference, child) as IngestResult.Rejected
            assertEquals(OperationErrorCode.CANCELED, result.error.code); f.assertNoVisibleSource()
            val intent = RoomIngestLookup(f.database).find(child)!!
            val staged = File(f.root, "staging/${intent.transactionId.value}/original.part")
            assertArrayEquals(f.bytes, staged.readBytes()); assertFalse(f.files.finalFile(intent.sourceBlobId.value).exists())
            assertEquals(1, f.opened); assertEquals(1, f.closed); assertEquals(1, f.inspected); assertEquals(0L, f.budget.usedBytes)
            val before = f.inventory(); f.reopen(); f.forbidInputs = true
            assertEquals(result, f.store.ingest(f.reference, child)); assertEquals(before, f.inventory()); assertArrayEquals(f.bytes, staged.readBytes())
            f.assertForeignKeys()
        }
    }

    @Test fun cancelingRemainingItemsRetainsCommittedPageOriginalAndReplayAndFreesBatchSlot() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val sessions = RoomImportSessions(f.database); val parent = sessions.beginImport(f.document, 1, null).parentId
            val selected = sessions.prepareSelection(parent, List(2) { f.reference.toString() })
            val first = f.store.ingest(f.reference, selected.childIds[0]) as IngestResult.Committed
            val source = f.database.catalog().source(first.sourceId.value)!!; val page = f.database.catalog().page(first.pageId.value)!!
            assertEquals(selected, sessions.cancelBatch(parent)); assertEquals("CANCELED", f.database.importSessions().session(parent.value)!!.state)
            assertEquals(source, f.database.catalog().source(first.sourceId.value)); assertEquals(page, f.database.catalog().page(first.pageId.value))
            assertEquals(listOf(first.pageId.value), f.database.catalog().activePages(f.document.value).map { it.pageId })
            assertArrayEquals(f.bytes, f.files.finalFile(source.sourceBlobId).readBytes()); assertEquals(2L, source.refCount)
            val before = f.inventory(); f.reopen(); f.forbidInputs = true
            assertEquals(first, f.store.ingest(f.reference, selected.childIds[0]))
            val late = f.store.ingest(f.reference, selected.childIds[1]) as IngestResult.Rejected
            assertEquals(OperationErrorCode.CANCELED, late.error.code); assertEquals(before, f.inventory()); assertEquals(1, f.opened)
            assertNotEquals(parent, RoomImportSessions(f.database).beginImport(f.document, first.documentRevision, null).parentId)
            assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    @Test fun realSqliteFailureRollsBackEarlierItemCancellationAndParentOutcome() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val sessions = RoomImportSessions(f.database); val parent = sessions.beginImport(f.document, 1, null).parentId
            sessions.prepareSelection(parent, List(3) { f.reference.toString() }); val before = f.inventory()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_cancel_abort BEFORE UPDATE OF state ON ImportSession WHEN NEW.kind='ITEM' AND NEW.selectionOrdinal=1 AND NEW.state='CANCELED' BEGIN SELECT RAISE(ABORT,'synthetic cancel write refusal'); END")
            try { sessions.cancelBatch(parent); fail("Partial cancellation escaped SQLite rollback") }
            catch (error: RepositoryFailure) { assertEquals(OperationErrorCode.STORAGE_FAILED, error.error.code) }
            assertEquals(before, f.inventory()); assertEquals(0, f.opened); f.assertNoVisibleSource(); f.assertForeignKeys()
        }
    }
}
