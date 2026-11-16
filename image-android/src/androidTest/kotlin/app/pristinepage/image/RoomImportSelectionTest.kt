package app.pristinepage.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.RoomImportSessions
import app.pristinepage.image.EncodedImageFixtures.rgbaPng
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room selection transactions and real file/codec ingestion; provider stream/revocation are injected, not a native picker/grant test. Unique fixture databases/private files are retained. App-controller scheduling is separately host-tested. */
@RunWith(AndroidJUnit4::class)
class RoomImportSelectionTest {
    @Test fun mixedOutOfOrderCompletionKeepsSelectionOrderAndReplaysAfterReopen() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val commands = RoomImportSessions(f.database); val parent = commands.beginImport(f.document, 1, null).parentId
            val refs = List(3) { f.reference.toString() }; val selected = commands.prepareSelection(parent, refs)
            val last = f.store.ingest(f.reference, selected.childIds[2]) as IngestResult.Committed
            f.revokeAfterFirstRead = true
            val failed = f.store.ingest(f.reference, selected.childIds[1]) as IngestResult.Rejected
            assertEquals(OperationErrorCode.PERMISSION_LOST, failed.error.code); f.revokeAfterFirstRead = false
            val first = f.store.ingest(f.reference, selected.childIds[0]) as IngestResult.Committed
            assertEquals(listOf(first.pageId.value, last.pageId.value), f.database.catalog().activePages(f.document.value).map { it.pageId })
            assertNotEquals(first.pageId, last.pageId); assertNotEquals(first.sourceId, last.sourceId)
            assertEquals(3, f.opened); assertEquals(3, f.closed); assertEquals(2, f.inspected)
            val parentRow = f.database.importSessions().session(parent.value)!!; assertEquals("COMMITTED", parentRow.state)
            assertTrue(parentRow.uriText!!.matches(Regex("photo-selection-v1:[0-9a-f]{64}")))
            assertTrue(f.database.importSessions().children(parent.value).all { it.uriText == null })
            val before = f.inventory(); f.reopen(); f.forbidInputs = true
            val replay = RoomImportSessions(f.database).prepareSelection(parent, refs); assertEquals(selected, replay)
            val outcomes = replay.childIds.map { f.store.ingest(f.reference, it) }
            assertEquals(listOf(first, failed, last), outcomes); assertEquals(before, f.inventory()); assertEquals(3, f.opened)
            try { RoomImportSessions(f.database).prepareSelection(parent, listOf(refs[0], "content://fixture.documents/changed", refs[2])); fail("Changed selection aliased a completed callback") }
            catch (error: RepositoryFailure) { assertEquals(OperationErrorCode.INVALID_INPUT, error.error.code) }
            assertEquals(before, f.inventory()); assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    @Test fun realSqliteAbortOnSecondBindingRollsBackEveryReservationAndToken() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val commands = RoomImportSessions(f.database); val parent = commands.beginImport(f.document, 1, null).parentId; val before = f.inventory()
            // A fixture-owned SQL failure AFTER the first child bind proves rollback, not just validation-before-write.
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_selection_abort BEFORE UPDATE OF uriText ON ImportSession WHEN NEW.kind='ITEM' AND NEW.selectionOrdinal=1 BEGIN SELECT RAISE(ABORT,'synthetic selection write refusal'); END")
            try { commands.prepareSelection(parent, List(3) { f.reference.toString() }); fail("Interrupted selection committed") }
            catch (error: RepositoryFailure) { assertEquals(OperationErrorCode.STORAGE_FAILED, error.error.code); assertTrue(error.error.retryable) }
            assertEquals(before, f.inventory()); assertTrue(f.database.importSessions().children(parent.value).isEmpty())
            assertEquals(0, f.opened); f.assertNoVisibleSource(); f.assertForeignKeys()
        }
    }

    @Test fun emptySelectionCancellationSurvivesReopenAndUnblocksANewBatch() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val commands = RoomImportSessions(f.database); val parent = commands.beginImport(f.document, 1, null).parentId
            assertTrue(commands.prepareSelection(parent, emptyList()).childIds.isEmpty()); val before = f.inventory(); f.reopen()
            val reopened = RoomImportSessions(f.database); assertTrue(reopened.prepareSelection(parent, emptyList()).childIds.isEmpty())
            assertEquals(before, f.inventory()); assertEquals("CANCELED", f.database.importSessions().session(parent.value)!!.state)
            assertNotEquals(parent, reopened.beginImport(f.document, 1, null).parentId)
            assertEquals(0, f.opened); f.assertNoVisibleSource(); f.assertForeignKeys()
        }
    }

    @Test fun concurrentSameCallbacksShareOneReservationAndFallbackOverSelectionIsNotTruncated() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val commands = RoomImportSessions(f.database); val parent = commands.beginImport(f.document, 1, null).parentId; val before = f.inventory()
            try { commands.prepareSelection(parent, List(101) { f.reference.toString() }); fail("Over-selection was truncated") }
            catch (error: RepositoryFailure) { assertEquals(OperationErrorCode.RESOURCE_LIMIT, error.error.code) }
            assertEquals(before, f.inventory())
            val refs = List(3) { f.reference.toString() }
            val pair = coroutineScope { val first = async { commands.prepareSelection(parent, refs) }; val second = async { commands.prepareSelection(parent, refs) }; first.await() to second.await() }
            assertEquals(pair.first, pair.second); assertEquals(3, f.database.importSessions().children(parent.value).size)
            assertEquals(0, f.opened); f.assertNoVisibleSource(); f.assertForeignKeys()
        }
    }
}
