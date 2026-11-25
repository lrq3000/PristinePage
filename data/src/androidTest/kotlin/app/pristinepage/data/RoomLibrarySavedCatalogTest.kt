package app.pristinepage.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.library.RoomLibrarySavedCatalog
import app.pristinepage.data.model.DocumentMutationResult
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.RecipeAdmission
import app.pristinepage.data.repository.RoomEditablePages
import app.pristinepage.data.repository.RoomDocumentRepository
import app.pristinepage.data.repository.RoomMetadataAccess
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibrarySavedCatalogTest {
    @Test fun removedScansRemainRecoverableInTheSnapshotWithTheirOriginalAndHistory() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            val child = source.reserve().single()
            val saved = source.store.ingest(source.reference, child) as IngestResult.Committed
            val command = RoomDocumentRepository(RoomMetadataAccess(source.database), RecipeAdmission { _, _ -> null })
            assertTrue(command.removePage(saved.pageId, saved.documentRevision) is DocumentMutationResult.Applied)
            val snapshot = RoomLibrarySavedCatalog(source.database).capture()
            val page = snapshot.albums.single().pages.single()
            assertEquals("REMOVED", page.row.state)
            assertEquals(null, page.row.ordinal)
            assertEquals("Scan ${saved.pageId.value}.png", page.filename)
            assertEquals(saved.sourceByteHash, snapshot.originals.single().byteSha256)
            assertEquals(1L, page.revisions.single().recipeRevision)
        }
    }

    @Test fun freshWriterRefusesAStandaloneLeftoverJournalThatHasNoForeignKeyViolation() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            val snapshot = RoomLibrarySavedCatalog(source.database).capture()
            val context = InstrumentationRegistry.getInstrumentation().context
            val destination = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
            try {
                val sql = destination.openHelper.writableDatabase
                sql.execSQL("""INSERT INTO FileTransaction(transactionId,kind,ownerId,state,payload,attempt,updatedAt)
                    VALUES ('synthetic','RENDER','owner','INTENT',X'01',1,0)""")
                val failure = runCatching { RoomLibrarySavedCatalog(destination).writeFresh(snapshot) }.exceptionOrNull() as RepositoryFailure
                assertEquals("library_stage_not_empty", failure.error.safeMessageKey)
                sql.query("SELECT COUNT(*) FROM FileTransaction").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                sql.query("SELECT COUNT(*) FROM Document").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            } finally { destination.close() }
        }
    }

    @Test fun aMalformedCommittedDraftCannotHideItsAlbumFromSnapshotAdmission() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            // Pass the scalar terminal shape but use a malformed receipt: admission must decode it, not just count bytes.
            source.database.openHelper.writableDatabase.execSQL("""INSERT INTO AtelierDraft
                (draftId,stagingDocumentId,state,generation,step,committedDocumentId,createdAt,receiptPageIds)
                VALUES (?,?,'COMMITTED',2,2,?,0,?)""",
                arrayOf(UUID.randomUUID().toString(), source.document.value, source.document.value, "a".repeat(200)))
            val failure = runCatching { RoomLibrarySavedCatalog(source.database).capture() }.exceptionOrNull() as RepositoryFailure
            assertEquals(OperationErrorCode.REVISION_CONFLICT, failure.error.code)
        }
    }

    @Test fun aPaddedAlbumTitleIsRefusedRatherThanCopiedIntoAnUnreadableWorkingCatalog() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            val title = "\u00a0Receipts "
            val sql = source.database.openHelper.writableDatabase
            sql.execSQL("UPDATE Document SET title=? WHERE documentId=?", arrayOf(title, source.document.value))
            val failure = runCatching { RoomLibrarySavedCatalog(source.database).capture() }.exceptionOrNull() as RepositoryFailure
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
            assertEquals(title, source.database.catalog().document(source.document.value)?.title)
        }
    }

    @Test fun savedAlbumProjectionKeepsScanOrderAndEveryRecipeInAFreshRoomCatalog() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            val (first, second) = source.reserve(count = 2)
            source.sourceDisplayName = "Front.png"
            val front = source.store.ingest(source.reference, first) as IngestResult.Committed
            source.sourceDisplayName = "Back.png"
            val back = source.store.ingest(source.reference, second) as IngestResult.Committed
            val sourceMetadata = RoomMetadataAccess(source.database)
            val command = RoomDocumentRepository(sourceMetadata, RecipeAdmission { _, _ -> null })
            val reordered = command.reorderPages(source.document, back.documentRevision, listOf(back.pageId, front.pageId))
            assertTrue(reordered is DocumentMutationResult.Applied)
            assertTrue(command.rotatePage(back.pageId, 1, 1) is DocumentMutationResult.Applied)
            val accepted = RoomEditablePages(sourceMetadata, source.database.catalog()).load(source.document, back.pageId)
            assertEquals(2L, accepted.recipeRevision)
            val snapshot = RoomLibrarySavedCatalog(source.database).capture()
            // Mutating an issued copy must not alter the admitted bytes handed to the writer.
            snapshot.albums.single().pages.first().revisions.first().canonicalRecipe.fill(0)

            val context = InstrumentationRegistry.getInstrumentation().context
            val name = "library-saved-catalog-${UUID.randomUUID()}.db"
            val staged = Room.databaseBuilder(context, PristineDatabase::class.java, name).build()
            try { RoomLibrarySavedCatalog(staged).writeFresh(snapshot) } finally { staged.close() }
            val reopened = Room.databaseBuilder(context, PristineDatabase::class.java, name).build()
            try {
                val sql = reopened.openHelper.readableDatabase
                sql.query("""SELECT Page.pageId,LibraryScanName.filename FROM Page JOIN LibraryScanName USING(pageId,documentId) ORDER BY ordinal""").use {
                    val order = buildList { while (it.moveToNext()) add(it.getString(0) to it.getString(1)) }
                    assertEquals(listOf(back.pageId.value to "Back.png", front.pageId.value to "Front.png"), order)
                }
                val q = reopened.catalog()
                val original = checkNotNull(q.recipe(back.pageId.value, 1))
                assertEquals(0, RecipeCodec.decode(original.canonicalRecipe).geometry.outputQuarterTurns)
                val persisted = RoomEditablePages(RoomMetadataAccess(reopened), q).load(source.document, back.pageId)
                assertEquals(accepted, persisted)
                sql.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { reopened.close() }
        }
    }
}
