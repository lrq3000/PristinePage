package app.pristinepage.data

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AtelierAlbumPublicationTest {
    @Test fun readOnlyPublicationRecoveryDoesNotRenderOrPublishAnUncommittedDraft() = runBlocking {
        val f = Fixture()
        val before = f.snapshot()
        assertNull(f.observe())
        assertEquals(before, f.snapshot())
        assertEquals(0, f.renders)
        val committed = f.publish()
        val after = f.snapshot()
        assertEquals(committed, f.observe())
        f.rejected(OperationErrorCode.REVISION_CONFLICT) { f.observe(f.witnesses.reversed()) }
        assertEquals(after, f.snapshot())
        assertEquals(2, f.renders)
    }

    @Test fun acquisitionSaveReturnsOnlyTheOrderedDestinationPagesEvenAfterReplay() = runBlocking {
        val f = Fixture()
        val staged = f.base.pageIds
        val prior = f.seedDestinationPage()
        val result = f.saveAcquisition()
        val committed = f.base.metadata.activePages(f.target.value).map { PageId(it.pageId) }
        assertEquals(f.target, result.document)
        assertEquals(3, committed.size)
        assertEquals(listOf(prior) + result.pageIds, committed)
        assertTrue(result.pageIds.none { it in staged })
        f.duplicateCommittedPage(checkNotNull(result.pageIds.firstOrNull()))
        assertEquals(4, f.base.metadata.activePages(f.target.value).size)
        val beforeReplay = f.snapshot()
        val replayed = f.saveAcquisition()
        assertEquals(result.document, replayed.document)
        assertEquals(result.pageIds, replayed.pageIds)
        f.rejected(OperationErrorCode.REVISION_CONFLICT) { f.saveAcquisition(f.witnesses.reversed()) }
        assertEquals(beforeReplay, f.snapshot())
        assertEquals(2, f.renders)
    }

    @Test fun acquisitionSaveKeepsTheSamePageIdentitiesWhenPublishingANewAlbum() = runBlocking {
        val f = Fixture(newAlbum = true)
        val saved = f.saveAcquisition()
        assertEquals(f.base.document, saved.document)
        assertEquals(f.base.pageIds, saved.pageIds)
        assertEquals(false, f.dao.savedDocumentIsHidden(saved.document.value))
    }

    @Test fun appendRetainsOriginalIngestIdsAndReplaysExactOrderedReceiptWithoutRenderingAgain() = runBlocking {
        val f = Fixture()
        val before = f.base.metadata.activePages(f.base.document.value)
        val result = f.publish()
        val appended = f.base.metadata.activePages(f.target.value)
        assertEquals(result.saved, appended.map { PageId(it.pageId) })
        assertEquals(listOf(0, 1), appended.map { it.ordinal })
        assertTrue(appended.all { it.sourceId == f.base.source.value && it.currentRecipeRevision == 1L })
        assertEquals(before, f.base.metadata.activePages(f.base.document.value))
        assertEquals(8L, f.base.metadata.countSourceReferences(f.base.source.value))
        assertEquals(2L, f.base.metadata.document(f.target.value)?.documentRevision)
        assertEquals(2, f.renders)
        assertEquals(result, f.publish())
        f.rejected(OperationErrorCode.REVISION_CONFLICT) { f.publish(f.witnesses.reversed()) }
        assertEquals(2, f.renders)
        assertEquals(appended, f.base.metadata.activePages(f.target.value))
    }

    @Test fun pendingTargetImportAtSameRevisionRefusesWithoutWriting() = runBlocking {
        val f = Fixture()
        f.base.metadata.pendingImports.add(f.target.value)
        val before = f.snapshot()
        f.rejected(OperationErrorCode.REVISION_CONFLICT) { f.publish() }
        assertEquals(before, f.snapshot())
    }

    @Test fun publicationCannotUseAReceiptDaoOutsideItsTransactionOwner() = runBlocking {
        val primary = Fixture()
        val separate = Fixture(primary = primary)
        val before = primary.snapshot()
        val external = separate.snapshot()
        primary.rejected(OperationErrorCode.INVALID_INPUT) { primary.publish(drafts = separate.dao) }
        assertEquals(0, primary.renders)
        assertEquals(before, primary.snapshot())
        assertEquals(external, separate.snapshot())
    }

    @Test fun secondRecipeInsertFailureRollsBackTheFirstPageAndBothPins() = runBlocking {
        val f = Fixture()
        f.base.metadata.failRecipeInsertAt = 2
        val before = f.snapshot()
        f.rejected(OperationErrorCode.STORAGE_FAILED) { f.publish() }
        assertEquals(before, f.snapshot())
        assertEquals(2, f.renders)
    }

    @Test fun changedOriginalHashAtTheSameBlobIdCannotPublishReviewedPages() = runBlocking {
        val f = Fixture()
        val before = f.snapshot()
        f.beforeReturn = { _ ->
            val blobs = f.base.metadata.blobs
            val original = blobs.values.single()
            blobs[original.sourceBlobId] = original.copy(byteSha256 = "c".repeat(64))
        }
        f.rejected(OperationErrorCode.INTEGRITY_FAILED) { f.publish() }
        assertEquals(before, f.snapshot())
    }

    @Test fun missingExactHistoryPinRefusesBeforeOpeningTheRenderer() = runBlocking {
        val f = Fixture()
        f.base.metadata.references.remove(Triple("RECIPE_HISTORY", "recipe-v1/${f.witnesses.first().page.value}/1", f.base.source.value))
        val before = f.snapshot()
        f.rejected(OperationErrorCode.INTEGRITY_FAILED) { f.publish() }
        assertEquals(0, f.renders)
        assertEquals(before, f.snapshot())
    }

    @Test fun targetMarkedDeletedCannotBeAppendedEvenWithTheRightRevision() = runBlocking {
        val f = Fixture()
        f.base.metadata.documents[f.target.value] = f.base.metadata.documents.getValue(f.target.value).copy(deletedAt = 7)
        val before = f.snapshot()
        f.rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { f.publish() }
        assertEquals(0, f.renders)
        assertEquals(before, f.snapshot())
    }

    @Test fun identicalInFlightAttemptRecoversTheReceiptBeforeLoadingItsNextPage() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        f.beforeReturn = { ordinal ->
            if (ordinal == 0) {
                f.beforeReturn = {}
                entered.complete(Unit)
                resume.await()
            }
        }
        val first = async { f.publish() }
        entered.await()
        try {
            val winner = f.publish()
            resume.complete(Unit)
            assertEquals(winner, first.await())
            assertEquals(winner.saved.map { it.value }, f.base.metadata.activePages(f.target.value).map { it.pageId })
        } finally { resume.complete(Unit) }
    }

    @Test fun retiredSingleCopyDraftsDoNotPreventOpeningANewScan() = runBlocking {
        val fixture = Fixture(onlyRetiredDrafts = true)
        val draft = fixture.openFreshDraft()
        assertEquals("ACQUIRING", draft.state)
        assertNull(draft.activeBatchId)
        assertTrue(fixture.base.metadata.activePages(draft.stagingDocumentId).isEmpty())
    }

    private class Fixture(primary: Fixture? = null, newAlbum: Boolean = false, private val onlyRetiredDrafts: Boolean = false) {
        val base: RepositoryFixture = primary?.base ?: RepositoryFixture()
        val target: DocumentId = primary?.target ?: DocumentId(UUID.randomUUID().toString())
        val id: String = primary?.id ?: UUID.randomUUID().toString()
        val witnesses = base.pageIds.map { AtelierPageWitness(it, 1, RecipeCodec.hash(base.initial)) }
        var renders = 0
        var beforeReturn: suspend (Int) -> Unit = {}
        private var record = AtelierDraftRow(id, base.document.value, target.value.takeUnless { newAlbum }, 1L.takeUnless { newAlbum },
            if (onlyRetiredDrafts) "ABANDONED" else "EDITING", 4, 2, base.pageIds.first().value, null, 0)
        private val q = base.metadata
        val dao: AtelierDraftDao = object : AtelierDraftDao() {
            override fun savedDocumentIsHidden(id: String) = if (q.document(id) == null) null else
                id == record.stagingDocumentId && (record.state != "COMMITTED" || record.targetDocumentId != null)
            override fun savedPromotionReceipt(id: String) = record.receiptPageIds.takeIf { record.stagingDocumentId == id }
            override fun draft(id: String) = record.takeIf { it.draftId == id }
            override fun forStagingDocument(id: String) = record.takeIf { it.stagingDocumentId == id }
            override fun editingDocument(id: String, generation: Long) = record.stagingDocumentId.takeIf { record.draftId == id && record.generation == generation && record.state == "EDITING" }
            override fun publicationHasPins(source: String, page: String, history: String) =
                listOf(Triple("PAGE", page, source), Triple("RECIPE_HISTORY", history, source)).all(q.references::containsKey)
            override fun publicationReceiptIsBounded(id: String, limit: Int) =
                record.takeIf { it.draftId == id }?.let { (it.receiptPageIds?.length ?: 0) <= limit }
            override fun refusesDraftIngest(id: String, batchId: String) = true
            override fun attemptedItems(id: String) = 0L
            override fun retainedCopyAttempts(id: String) = 0L
            override fun unfinished() = listOf(record).filter { it.state == "EDITING" || it.state == "ACQUIRING" }
            override fun hasCopyOwner(id: String) = id == record.stagingDocumentId
            override fun retainedStagingCopyAttempts(limit: Int) = if (onlyRetiredDrafts) 2 else 0
            override fun insert(row: AtelierDraftRow) {
                check(onlyRetiredDrafts && q.inTransaction && record.state == "ABANDONED")
                record = row
            }
            override fun update(row: AtelierDraftRow): Int {
                check(q.inTransaction)
                record = row
                return 1
            }
        }
        private val access = object : MetadataAccess by q {
            override fun ownsAtelierPublication(drafts: AtelierDraftDao) = drafts === dao
            override fun atelierEditingDocument(draftId: String, generation: Long) = dao.editingDocument(draftId, generation)
            override fun <T> transaction(action: () -> T): T {
                val before = record
                return try { q.transaction(action) } catch (failure: Throwable) { record = before; throw failure }
            }
        }
        private val repository = RoomDocumentRepository(access, RecipeAdmission { _, _ -> null }, dispatcher = Dispatchers.Unconfined)
        private val renderer = object : RenderCoordinator {
            override suspend fun render(request: RenderRequest): RenderResult {
                assertFalse(q.inTransaction)
                assertTrue(witnesses.any { it.page.value == request.pageId })
                beforeReturn(renders++)
                return RenderResult(request, RenderStatus.OK, RgbImage(3, 2, 9, ByteArray(18)), emptyList(), emptyList())
            }
        }
        init {
            q.documents[target.value] = DocumentRow(target.value, "Saved album", 1, "ACTIVE", 5, 5, null)
            q.hiddenDraftDocuments.add(base.document.value)
        }

        fun snapshot() = listOf(record, q.documents.toMap(), q.pages.toMap(), q.recipes.toMap(), q.references.toMap(), q.sources.toMap())

        suspend fun openFreshDraft() = RoomAtelierDrafts(access, dao, RoomImportSessions(access, IngestIntentTest.Fixture()))
            .create("New scan", null, null)

        suspend fun publish(witness: List<AtelierPageWitness> = witnesses, drafts: AtelierDraftDao = dao): AtelierPublicationReceipt =
            repository.publishAtelierDraft(drafts, id, 4L, witness, renderer)

        suspend fun observe(witness: List<AtelierPageWitness> = witnesses): AtelierPublicationReceipt? =
            repository.inspectAtelierPublication(dao, id, 4L, witness)

        suspend fun saveAcquisition(witness: List<AtelierPageWitness> = witnesses) = RoomEditablePages(access, q, Dispatchers.Unconfined).let { pages ->
            val sessions = RoomImportSessions(access, ImportSessionTest.Fixture(0), dispatcher = Dispatchers.Unconfined, drafts = dao)
            val acquisition = AtelierAcquisition(RoomAtelierDrafts(access, dao, sessions), dao, pages, repository)
            val view = AtelierDraftView(id, 4L, record.targetDocumentId?.let(::DocumentId), true, 2, base.pageIds.first(), null, emptyList())
            val reviewed = witness.map { EditablePage(base.document, 1L, it.page, base.source, it.revision, it.recipeHash, base.initial) }
            acquisition.save(view, reviewed, renderer)
        }

        fun seedDestinationPage(): PageId {
            val id = PageId(UUID.randomUUID().toString())
            q.transaction {
                val sample = checkNotNull(q.page(base.pageIds.first().value))
                q.insertPage(sample.copy(pageId = id.value, documentId = target.value))
                q.insertRecipe(checkNotNull(q.recipe(sample.pageId, 1)).copy(pageId = id.value))
                q.insertReference(SourceReferenceRow("PAGE", id.value, base.source.value, 0))
                q.insertReference(SourceReferenceRow("RECIPE_HISTORY", "recipe-v1/${id.value}/1", base.source.value, 0))
                q.setSourceRefCount(base.source.value, q.countSourceReferences(base.source.value))
            }
            return id
        }

        suspend fun duplicateCommittedPage(page: PageId) {
            val currentRevision = checkNotNull(q.document(target.value)).documentRevision
            assertTrue(repository.duplicatePage(page, currentRevision) is DocumentMutationResult.Applied)
        }

        suspend fun rejected(code: OperationErrorCode, action: suspend () -> Any?) {
            try { action(); fail("An invalid publication succeeded") }
            catch (failure: RepositoryFailure) { assertEquals(code, failure.error.code) }
        }
    }
}
