package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.RoomMetadataAccess
import app.pristinepage.data.repository.RoomUndoRetention
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SQLite state-only expiry/transaction rollback on generated metadata. Retain databases, all pins/history and originals; reopen is not process-death or page-finalization evidence. */
@RunWith(AndroidJUnit4::class)
class RoomUndoRetentionIntegrationTest {
    private suspend fun edits(f: RoomRepositoryIntegrationTest.Fixture, count: Int): List<UndoId> = (0 until count).map {
        val revision = f.database.catalog().page(f.pages[0].value)!!.currentRecipeRevision
        val result = f.repository.rotatePage(f.pages[0], revision, 1) as DocumentMutationResult.Applied
        checkNotNull(result.undoId)
    }

    @Test fun recipeAndMembershipEditsKeepOnlyNewestTwentyUndoEntriesAcrossReopen() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val ids = edits(f, 21)
            val before = f.metadataState()
            val rejected = f.repository.undoDocumentEdit(ids.first(), 22) as DocumentMutationResult.Rejected
            assertEquals(OperationErrorCode.INVALID_INPUT, rejected.error.code); assertEquals(before, f.metadataState())
            val moved = f.repository.reorderPages(f.document, 22, f.pages.reversed()) as DocumentMutationResult.Applied
            f.reopen(); val q = f.database.catalog()
            assertEquals(listOf("EXPIRED", "EXPIRED"), ids.take(2).map { q.undo(it.value)!!.state })
            assertTrue(ids.drop(2).all { q.undo(it.value)!!.state == "AVAILABLE" })
            assertEquals("AVAILABLE", q.undo(checkNotNull(moved.undoId).value)!!.state)
            assertEquals(20L, q.countUnexpiredUndoEntries(f.document.value)); assertEquals(22L, f.count("UndoEntry"))
            assertEquals(23L, f.count("RecipeRevision")); assertEquals(47L, f.count("SourceReference")); assertEquals(47L, q.source(f.source)!!.refCount)
            assertEquals(f.pages.reversed().map { it.value }, q.activePages(f.document.value).map { it.pageId }); f.assertForeignKeys()
        }
    }

    @Test fun inclusiveExpiryRetainsRemovalTombstoneHistoryPinsAndEveryNonStateByte() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val removed = f.repository.removePage(f.pages[0], 1) as DocumentMutationResult.Applied
            val id = checkNotNull(removed.undoId).value
            f.database.openHelper.writableDatabase.execSQL("UPDATE UndoEntry SET expiresAt=5000 WHERE undoId=?", arrayOf<Any>(id))
            f.reopen(); val before = f.metadataState(ignoreUndoState = true)
            assertEquals(RecoveryReport(1, 0, 0, 0, 0, emptyList()), RoomUndoRetention(RoomMetadataAccess(f.database), { 4999L }).expireDocument(f.document))
            assertEquals("AVAILABLE", f.database.catalog().undo(id)!!.state)
            assertEquals(RecoveryReport(1, 1, 0, 0, 0, emptyList()), RoomUndoRetention(RoomMetadataAccess(f.database), { 5000L }).expireDocument(f.document))
            assertEquals(RecoveryReport(0, 0, 0, 0, 0, emptyList()), RoomUndoRetention(RoomMetadataAccess(f.database), { 5000L }).expireDocument(f.document))
            f.reopen(); assertEquals("EXPIRED", f.database.catalog().undo(id)!!.state)
            assertEquals("REMOVED", f.database.catalog().page(f.pages[0].value)!!.state)
            assertEquals(before, f.metadataState(ignoreUndoState = true)); assertEquals(5L, f.count("SourceReference")); f.assertForeignKeys()
        }
    }

    @Test fun realLateExpiryFailureRollsBackRecipeOrMembershipEditAndAllPins() = runBlocking {
        for (membership in listOf(false, true)) RoomRepositoryIntegrationTest.Fixture().use { f ->
            val first = edits(f, 20).first(); val before = f.metadataState()
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_undo_expiry BEFORE UPDATE ON UndoEntry WHEN OLD.undoId='${first.value}' AND NEW.state='EXPIRED' BEGIN SELECT RAISE(ABORT,'synthetic expiry write fault'); END")
            val result = if (membership) f.repository.reorderPages(f.document, 21, f.pages.reversed()) else f.repository.rotatePage(f.pages[0], 21, 1)
            val rejected = result as DocumentMutationResult.Rejected
            assertEquals(OperationErrorCode.STORAGE_FAILED, rejected.error.code); assertTrue(rejected.error.retryable)
            f.reopen(); assertEquals(before, f.metadataState())
            assertEquals(20L, f.database.catalog().countUnexpiredUndoEntries(f.document.value))
            assertEquals(21L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertEquals(44L, f.count("SourceReference")); assertEquals(20L, f.count("UndoEntry")); f.assertForeignKeys()
        }
    }

    @Test fun expiredNewerEntriesStillOccupyTheirRecordedWindowAndCannotPromoteOldToken() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val ids = edits(f, 21)
            f.database.runInTransaction {
                val sql = f.database.openHelper.writableDatabase
                for (id in ids.takeLast(10)) sql.execSQL("UPDATE UndoEntry SET state='EXPIRED' WHERE undoId=?", arrayOf<Any>(id.value))
                // Simulate pending reconciliation of an older AVAILABLE row. Counting only currently available entries would incorrectly retain this evicted token now that fewer than20are available.
                sql.execSQL("UPDATE UndoEntry SET state='AVAILABLE' WHERE undoId=?", arrayOf<Any>(ids.first().value))
            }
            f.reopen(); val before = f.metadataState(ignoreUndoState = true)
            assertEquals(RecoveryReport(11, 1, 0, 0, 0, emptyList()), RoomUndoRetention(RoomMetadataAccess(f.database), { 0L }).expireDocument(f.document))
            f.reopen(); assertEquals("EXPIRED", f.database.catalog().undo(ids.first().value)!!.state)
            assertEquals(10L, f.database.catalog().countUnexpiredUndoEntries(f.document.value))
            assertEquals(before, f.metadataState(ignoreUndoState = true)); f.assertForeignKeys()
        }
    }

    @Test fun legacyRecipeAndMembershipTokensOutsideWindowRejectWithoutStartupPass() = runBlocking {
        for (membership in listOf(false, true)) RoomRepositoryIntegrationTest.Fixture().use { f ->
            val ids = if (membership) (0 until 21).map { index ->
                val order = if (index % 2 == 0) f.pages.reversed() else f.pages
                val result = f.repository.reorderPages(f.document, index + 1L, order) as DocumentMutationResult.Applied
                checkNotNull(result.undoId)
            } else edits(f, 21)
            f.database.openHelper.writableDatabase.execSQL("UPDATE UndoEntry SET state='AVAILABLE' WHERE undoId=?", arrayOf<Any>(ids.first().value))
            f.reopen(); val before = f.metadataState()
            assertEquals(21L, f.database.catalog().countUnexpiredUndoEntries(f.document.value))
            val rejected = f.repository.undoDocumentEdit(ids.first(), 22) as DocumentMutationResult.Rejected
            assertEquals(OperationErrorCode.INVALID_INPUT, rejected.error.code)
            f.reopen(); assertEquals(before, f.metadataState())
            assertEquals("AVAILABLE", f.database.catalog().undo(ids.first().value)!!.state)
            assertTrue(f.repository.undoDocumentEdit(ids.last(), 22) is DocumentMutationResult.Applied)
            f.reopen(); assertEquals(23L, f.database.catalog().document(f.document.value)!!.documentRevision); f.assertForeignKeys()
        }
    }
}
