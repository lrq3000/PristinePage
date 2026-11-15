package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room command/Undo/reopen with generated metadata, not source-image/decoder/process-death evidence. Retain each synthetic database and every history/reference row. */
@RunWith(AndroidJUnit4::class)
class RoomDuplicateUndoIntegrationTest {
    private fun metadataState(f: RoomRepositoryIntegrationTest.Fixture) = f.metadataState()

    private fun separateSurvivorSource(f: RoomRepositoryIntegrationTest.Fixture): String {
        val sourceId = java.util.UUID.randomUUID().toString(); val blobId = java.util.UUID.randomUUID().toString()
        val page = f.pages[1].value; val original = f.database.catalog().source(f.source)!!
        f.database.runInTransaction {
            val sql = f.database.openHelper.writableDatabase
            sql.execSQL("INSERT INTO SourceBlob(sourceBlobId,byteSha256,byteCount,mime,encodedWidth,encodedHeight,relativePath,state,sourceCount,ingestTransactionId,createdAt) SELECT ?,byteSha256,byteCount,mime,encodedWidth,encodedHeight,?,state,sourceCount,?,createdAt FROM SourceBlob WHERE sourceBlobId=?", arrayOf<Any>(blobId, "sources/$blobId/original.bin", java.util.UUID.randomUUID().toString(), original.sourceBlobId))
            sql.execSQL("INSERT INTO Source(sourceId,sourceBlobId,pixelSha256,width,height,exifOrientation,conversionFingerprint,profileSha256,refCount,state,createdAt) SELECT ?,?,pixelSha256,width,height,exifOrientation,conversionFingerprint,profileSha256,2,state,createdAt FROM Source WHERE sourceId=?", arrayOf<Any>(sourceId, blobId, f.source))
            sql.execSQL("UPDATE Page SET sourceId=? WHERE pageId=?", arrayOf<Any>(sourceId, page))
            sql.execSQL("UPDATE RecipeRevision SET sourceId=? WHERE pageId=?", arrayOf<Any>(sourceId, page))
            sql.execSQL("UPDATE SourceReference SET sourceId=? WHERE (ownerType='PAGE' AND ownerId=?) OR (ownerType='RECIPE_HISTORY' AND ownerId=?)", arrayOf<Any>(sourceId, page, "recipe-v1/$page/1"))
            assertEquals(1, f.database.catalog().setSourceRefCount(f.source, 2))
        }
        return blobId
    }

    @Test fun editedDuplicateCanBeUndoneAfterReopenWithoutLosingItsHistoryOrSourcePins() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val result = f.repository.duplicatePage(f.pages[0], 1) as DocumentMutationResult.Applied
            val copy = result.affectedPageIds.single(); val undoId = checkNotNull(result.undoId)
            assertTrue(f.repository.saveRecipe(copy, 1, f.turned(3)) is SaveRecipeResult.Saved)
            f.reopen()
            assertTrue(f.repository.undoDocumentEdit(undoId, 3) is DocumentMutationResult.Applied)
            f.reopen()
            val q = f.database.catalog()
            assertEquals(f.pages.map { it.value }, q.activePages(f.document.value).map { it.pageId }); assertEquals(listOf(0, 1), q.activePages(f.document.value).map { it.ordinal })
            assertEquals("REMOVED", q.page(copy.value)!!.state); assertNull(q.page(copy.value)!!.ordinal)
            assertEquals(2L, q.page(copy.value)!!.currentRecipeRevision)
            assertEquals(3, RecipeCodec.decode(q.recipe(copy.value, 2)!!.canonicalRecipe).geometry.outputQuarterTurns)
            assertNotNull(q.recipe(copy.value, 1)); assertEquals(1L, q.page(f.pages[0].value)!!.currentRecipeRevision)
            assertEquals(9L, f.count("SourceReference")); assertEquals(9L, q.source(f.source)!!.refCount); assertEquals("APPLIED", q.undo(undoId.value)!!.state)
            assertEquals(4L, q.document(f.document.value)!!.documentRevision); f.assertForeignKeys()
        }
    }

    @Test fun nestedDuplicateUndosKeepBothTombstonesAndExactlyTheOriginalActiveOrder() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val first = f.repository.duplicatePage(f.pages[0], 1) as DocumentMutationResult.Applied
            val second = f.repository.duplicatePage(first.affectedPageIds.single(), 2) as DocumentMutationResult.Applied
            f.reopen()
            assertTrue(f.repository.undoDocumentEdit(checkNotNull(second.undoId), 3) is DocumentMutationResult.Applied)
            assertTrue(f.repository.undoDocumentEdit(checkNotNull(first.undoId), 4) is DocumentMutationResult.Applied)
            f.reopen()
            val q = f.database.catalog()
            assertEquals(f.pages.map { it.value }, q.activePages(f.document.value).map { it.pageId })
            for (copy in first.affectedPageIds + second.affectedPageIds) { assertEquals("REMOVED", q.page(copy.value)!!.state); assertNotNull(q.recipe(copy.value, 1)) }
            assertEquals(10L, f.count("SourceReference")); assertEquals(10L, q.source(f.source)!!.refCount); f.assertForeignKeys()
        }
    }

    @Test fun lateDuplicateCommitFailureRollsBackNewPageHistoryOrdinalsUndoAndPins() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_duplicate_commit BEFORE UPDATE OF documentRevision ON Document WHEN NEW.documentId='${f.document.value}' AND NEW.documentRevision=2 BEGIN SELECT RAISE(ABORT,'synthetic duplicate commit fault'); END")
            val result = f.repository.duplicatePage(f.pages[0], 1) as DocumentMutationResult.Rejected
            assertEquals(OperationErrorCode.STORAGE_FAILED, result.error.code)
            f.reopen()
            val q = f.database.catalog()
            assertEquals(1L, q.document(f.document.value)!!.documentRevision); assertEquals(f.pages.map { it.value }, q.activePages(f.document.value).map { it.pageId })
            assertEquals(listOf(0, 1), q.activePages(f.document.value).map { it.ordinal }); assertEquals(2L, f.count("RecipeRevision")); assertEquals(0L, f.count("UndoEntry"))
            assertEquals(4L, f.count("SourceReference")); assertEquals(4L, q.source(f.source)!!.refCount); f.assertForeignKeys()
        }
    }

    @Test fun unavailableTargetOrSurvivorBlobRejectsDuplicationWithoutAnyMetadataMutation() = runBlocking {
        for (survivor in listOf(false, true)) for (state in listOf("QUARANTINED", "DELETING")) RoomRepositoryIntegrationTest.Fixture().use { f ->
            val blobId = if (survivor) separateSurvivorSource(f) else f.database.catalog().source(f.source)!!.sourceBlobId
            f.database.openHelper.writableDatabase.execSQL("UPDATE SourceBlob SET state=? WHERE sourceBlobId=?", arrayOf<Any>(state, blobId))
            f.reopen(); val before = metadataState(f)
            val rejected = f.repository.duplicatePage(f.pages[0], 1) as DocumentMutationResult.Rejected
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, rejected.error.code); assertFalse(rejected.error.retryable)
            f.reopen(); assertEquals(before, metadataState(f)); assertEquals(1L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertEquals(f.pages.map { it.value }, f.database.catalog().activePages(f.document.value).map { it.pageId }); assertEquals(0L, f.count("UndoEntry")); f.assertForeignKeys()
        }
    }

    @Test fun inconsistentLiveMembershipCannotCreateHistoryItsOwnUndoWouldReject() = runBlocking {
        for (index in 0..1) RoomRepositoryIntegrationTest.Fixture().use { f ->
            f.database.openHelper.writableDatabase.execSQL("UPDATE Page SET removedAt=? WHERE pageId=?", arrayOf<Any>(1000L, f.pages[index].value))
            f.reopen(); val before = metadataState(f)
            val rejected = f.repository.duplicatePage(f.pages[0], 1) as DocumentMutationResult.Rejected
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, rejected.error.code); assertFalse(rejected.error.retryable)
            f.reopen(); assertEquals(before, metadataState(f)); assertEquals(1L, f.database.catalog().document(f.document.value)!!.documentRevision)
            assertEquals(0L, f.count("UndoEntry")); assertEquals(4L, f.count("SourceReference")); f.assertForeignKeys()
        }
    }
}
