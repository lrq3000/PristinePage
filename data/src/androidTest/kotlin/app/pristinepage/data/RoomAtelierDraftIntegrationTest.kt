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
import app.pristinepage.data.repository.RoomIngestPreparation
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

    @Test fun appendChecksCopiedNamesAfterWritingThePublicationReceipt() = runBlocking<Unit> {
        RoomRepositoryIntegrationTest.Fixture().use { fixture ->
            val db = fixture.database
            val target = DocumentId(UUID.randomUUID().toString())
            val draftId = UUID.randomUUID().toString()
            val dao = db.atelierDrafts()
            val metadata = RoomMetadataAccess(db)
            metadata.transaction {
                db.catalog().insertDocument(app.pristinepage.data.db.DocumentRow(target.value, "Target album", 1, "ACTIVE", 0, 0, null))
                dao.insert(AtelierDraftRow(draftId, fixture.document.value, target.value, 1L, "EDITING", 3, 2, fixture.pages.first().value, null, 0))
                fixture.pages.forEachIndexed { index, id ->
                    val name = "Page${index + 1}.png"
                    db.libraryScanNames().insert(app.pristinepage.data.db.LibraryScanNameRow(id.value, fixture.document.value, name, name.lowercase()))
                }
            }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("""CREATE TRIGGER flip_append_name AFTER UPDATE OF state ON AtelierDraft
                WHEN NEW.state='COMMITTED' BEGIN
                UPDATE LibraryScanName SET filename='Wrong.png',comparisonKey='wrong.png'
                WHERE pageId=(SELECT pageId FROM Page WHERE documentId='${target.value}' AND ordinal=1); END""")
            val inputs = fixture.pages.map { AtelierPageWitness(it, 1, RecipeCodec.hash(fixture.turned(0))) }
            val repository = RoomDocumentRepository(metadata, RecipeAdmission { _, _ -> null })
            val renderer = object : RenderCoordinator {
                override suspend fun render(request: RenderRequest) =
                    RenderResult(request, RenderStatus.OK, RgbImage(3, 2, 9, ByteArray(18)), emptyList(), emptyList())
            }
            rejected(OperationErrorCode.INTEGRITY_FAILED) { repository.publishAtelierDraft(dao, draftId, 3, inputs, renderer) }
            assertEquals("EDITING", dao.draft(draftId)?.state)
            assertTrue(db.catalog().activePages(target.value).isEmpty())
            assertEquals(2L, fixture.count("LibraryScanName"))
        }
    }

    @Test fun twoRetiredSingleCopyDraftsDoNotPreventStartingAnotherImport() = runBlocking<Unit> {
        withMemoryDatabase { db ->
            val metadata = RoomMetadataAccess(db)
            val sessions = RoomImportSessions(db)
            val drafts = RoomAtelierDrafts(metadata, db.atelierDrafts(), sessions)
            val prepare = RoomIngestPreparation(db)
            val uri = "content://fixture.documents/one"
            val previous = List(2) {
                val draft = drafts.create("Canceled scan", null, null)
                val batch = drafts.reserve(draft.draftId, draft.generation)
                val child = sessions.prepareSelection(batch, listOf(uri)).childIds.single()
                val intent = prepare.prepare(child, uri)
                sessions.cancelBatch(batch)
                drafts.abandon(draft.draftId, draft.generation)
                draft to intent
            }

            val fresh = drafts.create("New scan", null, null)
            assertTrue(drafts.view(fresh.draftId)!!.pages.isEmpty())
            val next = drafts.reserve(fresh.draftId, fresh.generation)
            val newChild = sessions.prepareSelection(next, listOf(uri)).childIds.single()
            assertEquals(fresh.stagingDocumentId, prepare.prepare(newChild, uri).documentId.value)
            assertTrue(RoomDocumentCatalog(metadata, db.catalog()).list(null, 10).isEmpty())
            previous.forEach { (draft, intent) ->
                assertEquals("ABANDONED", db.atelierDrafts().draft(draft.draftId)!!.state)
                assertNotNull(db.ingestPreparation().fileTransaction(intent.transactionId.value))
            }
        }
    }

    @Test fun aFullStagingAllowanceDoesNotDisableTheViewfinderDraftOrReserveNewItems() = runBlocking<Unit> {
        withMemoryDatabase { db ->
            val metadata = RoomMetadataAccess(db)
            val sessions = RoomImportSessions(db)
            val drafts = RoomAtelierDrafts(metadata, db.atelierDrafts(), sessions)
            val prepare = RoomIngestPreparation(db)
            val uri = "content://fixture.documents/one"
            repeat(2) {
                val old = drafts.create("Retired scan", null, null)
                val batch = drafts.reserve(old.draftId, old.generation)
                // Reserve at most eight attempts per draft; nothing in this test opens a URI or allocates a payload.
                val children = sessions.prepareSelection(batch, List(8) { uri }).childIds
                children.forEach { prepare.prepare(it, uri) }
                sessions.cancelBatch(batch)
                drafts.abandon(old.draftId, old.generation)
            }
            val now = drafts.create("New scan", null, null)
            assertTrue(drafts.view(now.draftId)!!.pages.isEmpty())
            rejected(OperationErrorCode.RESOURCE_LIMIT) { drafts.reserve(now.draftId, now.generation, camera = true) }
            assertNull("Refusing the shutter must not reserve a batch", drafts.view(now.draftId)!!.pending)
            val refused = drafts.reserve(now.draftId, now.generation)
            try {
                sessions.prepareSelection(refused, listOf(uri))
                fail("A seventeenth staging copy was admitted")
            } catch (failure: RepositoryFailure) {
                assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code)
                assertEquals("draft_staging_full", failure.error.safeMessageKey)
                db.runInTransaction { assertTrue(db.importSessions().children(refused.value).isEmpty()) }
            }
            assertTrue(sessions.cancelUnboundBatch(refused))
            assertNull(drafts.view(now.draftId)!!.pending)
            assertNotEquals(refused, drafts.reserve(now.draftId, now.generation))
        }
    }

    @Test fun conditionalRefusalCleanupCannotCancelABatchWithABoundSelection() = runBlocking<Unit> {
        withMemoryDatabase { db ->
            val sessions = RoomImportSessions(db)
            val drafts = RoomAtelierDrafts(RoomMetadataAccess(db), db.atelierDrafts(), sessions)
            val draft = drafts.create("Scan", null, null)
            val batch = drafts.reserve(draft.draftId, draft.generation)
            val uri = "content://fixture.documents/one"
            val accepted = sessions.prepareSelection(batch, listOf(uri))
            assertFalse(sessions.cancelUnboundBatch(batch))
            assertEquals(accepted, sessions.prepareSelection(batch, listOf(uri)))
            assertEquals(batch, drafts.view(draft.draftId)!!.pending)
        }
    }

    @Test fun anOversizedSelectionDoesNotSpendTheLastCopySlot() = runBlocking<Unit> {
        withMemoryDatabase { db ->
            val metadata = RoomMetadataAccess(db)
            val sessions = RoomImportSessions(db)
            val drafts = RoomAtelierDrafts(metadata, db.atelierDrafts(), sessions)
            val prepare = RoomIngestPreparation(db)
            val uri = "content://fixture.documents/one"
            for (count in listOf(8, 7)) {
                val prior = drafts.create("Retired scan", null, null)
                val batch = drafts.reserve(prior.draftId, prior.generation)
                sessions.prepareSelection(batch, List(count) { uri }).childIds.forEach { prepare.prepare(it, uri) }
                sessions.cancelBatch(batch)
                drafts.abandon(prior.draftId, prior.generation)
            }
            val fresh = drafts.create("Scan", null, null)
            val first = drafts.reserve(fresh.draftId, fresh.generation)
            rejected(OperationErrorCode.RESOURCE_LIMIT) { sessions.prepareSelection(first, listOf(uri, uri)) }
            assertTrue(sessions.cancelUnboundBatch(first))
            val next = drafts.reserve(fresh.draftId, fresh.generation)
            val selected = sessions.prepareSelection(next, listOf(uri)).childIds.single()
            assertEquals(fresh.stagingDocumentId, prepare.prepare(selected, uri).documentId.value)
        }
    }

    private suspend fun withMemoryDatabase(test: suspend (PristineDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PristineDatabase::class.java).build()
        try { test(db) } finally { db.close() }
    }

    private suspend fun rejected(code: OperationErrorCode, action: suspend () -> Any?) {
        try { action(); fail("An invalid draft action succeeded") }
        catch (failure: RepositoryFailure) { assertEquals(code, failure.error.code) }
    }
}
