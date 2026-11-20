package app.pristinepage.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.AtelierDraftRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.AtelierDraftMigration
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentMutationResult
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.RoomAtelierDrafts
import app.pristinepage.data.repository.RoomDocumentCatalog
import app.pristinepage.data.repository.RoomDocumentRepository
import app.pristinepage.data.repository.RoomEditablePages
import app.pristinepage.data.repository.RoomImportSessions
import app.pristinepage.data.repository.RoomMetadataAccess
import app.pristinepage.data.repository.RecipeAdmission
import app.pristinepage.data.repository.AtelierPageWitness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomAtelierDraftIntegrationTest {
    @get:Rule
    val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PristineDatabase::class.java)

    @Test fun roomOpensVersionOneWithARealMigrationAndKeepsTheExistingAlbum() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "atelier-migration-${UUID.randomUUID()}"
        val albumId = UUID.randomUUID().toString()
        try {
            migration.createDatabase(database, 1).use { original ->
                original.execSQL("INSERT INTO Document VALUES (?, 'Existing album', 9, 'ACTIVE', 5, 7, NULL)", arrayOf(albumId))
            }
            migration.runMigrationsAndValidate(database, 2, true, AtelierDraftMigration).use { upgraded ->
                upgraded.query("SELECT title, documentRevision, createdAt, updatedAt FROM Document WHERE documentId=?", arrayOf(albumId)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Existing album", cursor.getString(0))
                    assertEquals(9L, cursor.getLong(1))
                    assertEquals(5L, cursor.getLong(2))
                    assertEquals(7L, cursor.getLong(3))
                }
                upgraded.query("SELECT * FROM AtelierDraft").use { assertEquals(0, it.count) }
            }
        } finally {
            context.deleteDatabase(database)
        }
    }

    @Test fun stagingIsPrivateAndASingleTransactionalFenceControlsReservationAndAbandonment() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try {
            val metadata = RoomMetadataAccess(db)
            val sessions = RoomImportSessions(db)
            val drafts = RoomAtelierDrafts(metadata, db.atelierDrafts(), sessions)
            val catalog = RoomDocumentCatalog(metadata, db.catalog())
            val editable = RoomEditablePages(metadata, db.catalog())

            val draft = drafts.create("Scan", null, null)
            val staging = DocumentId(draft.stagingDocumentId)
            assertEquals(draft, drafts.resume())
            assertTrue(catalog.list(null, 50).isEmpty())
            rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { catalog.load(staging) }
            rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { editable.load(staging, PageId(UUID.randomUUID().toString())) }
            rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { sessions.beginImport(staging, 1, null) }
            val ordinary = RoomDocumentRepository(metadata, RecipeAdmission { _, _ -> null })
            val rename = ordinary.renameDocument(staging, 1, "Not a saved album")
            assertEquals(OperationErrorCode.SOURCE_UNAVAILABLE, (rename as? DocumentMutationResult.Rejected)?.error?.code)

            rejected(OperationErrorCode.INVALID_INPUT) { drafts.finish(draft.draftId, draft.generation) }
            val batch = drafts.reserve(draft.draftId, draft.generation)
            rejected(OperationErrorCode.REVISION_CONFLICT) { drafts.finish(draft.draftId, draft.generation) }
            rejected(OperationErrorCode.REVISION_CONFLICT) { drafts.abandon(draft.draftId, draft.generation) }
            sessions.cancelBatch(batch)
            assertEquals("ABANDONED", drafts.abandon(draft.draftId, draft.generation).state)
            assertNull(drafts.resume())
            assertTrue(catalog.list(null, 50).isEmpty())
            rejected(OperationErrorCode.REVISION_CONFLICT) { drafts.reserve(draft.draftId, draft.generation) }
        } finally { db.close() }
    }

    @Test fun publishedNewAlbumKeepsTheOriginalPageIdsAndAcknowledgesTheSameRequestAfterRoomReopen() {
        RoomRepositoryIntegrationTest.Fixture().use { fixture ->
            runBlocking {
                val db = fixture.database
                val draftId = UUID.randomUUID().toString()
                val document = fixture.document
                val dao = db.atelierDrafts()
                val row = AtelierDraftRow(draftId, document.value, null, null, "EDITING", 3, 2, fixture.pages.first().value, null, 0)
                val recipe = fixture.turned(0)
                val input = fixture.pages.map { AtelierPageWitness(it, 1, RecipeCodec.hash(recipe)) }
                var calls = 0
                val renderer = object : RenderCoordinator {
                    override suspend fun render(request: RenderRequest): RenderResult {
                        calls++
                        return RenderResult(request, RenderStatus.OK, RgbImage(3, 2, 9, ByteArray(18)), emptyList(), emptyList())
                    }
                }
                db.runInTransaction { dao.insert(row) }
                val metadata = RoomMetadataAccess(db)
                val catalog = RoomDocumentCatalog(metadata, db.catalog())
                assertTrue(catalog.list(null, 10).isEmpty())
                val repository = RoomDocumentRepository(metadata, RecipeAdmission { _, _ -> null }, clock = { 300L })
                val receipt = repository.publishAtelierDraft(dao, draftId, 3, input, renderer)
                assertEquals(document, receipt.document)
                assertEquals(fixture.pages, receipt.saved)
                assertEquals(4L, fixture.count("SourceReference"))
                val saved = catalog.load(document)
                assertEquals(fixture.pages, saved.pages.map { it.pageId })
                assertEquals(300L, saved.document.createdAt)
                fixture.reopen()
                val reopened = fixture.database
                val command = RoomDocumentRepository(RoomMetadataAccess(reopened), RecipeAdmission { _, _ -> null })
                assertEquals(receipt, command.publishAtelierDraft(reopened.atelierDrafts(), draftId, 3, input, renderer))
                rejected(OperationErrorCode.REVISION_CONFLICT) { command.publishAtelierDraft(reopened.atelierDrafts(), draftId, 3, input.reversed(), renderer) }
                assertEquals(2, calls)
                fixture.assertForeignKeys()
            }
        }
    }

    private suspend fun rejected(code: OperationErrorCode, action: suspend () -> Any?) {
        try { action(); fail("An invalid draft action succeeded") }
        catch (failure: RepositoryFailure) { assertEquals(code, failure.error.code) }
    }
}
