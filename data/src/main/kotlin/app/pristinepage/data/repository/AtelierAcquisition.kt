package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.data.db.AtelierDraftDao
import app.pristinepage.data.model.*

data class AtelierDraftView(
    val id: String,
    val generation: Long,
    val target: DocumentId?,
    val editing: Boolean,
    val step: Int,
    val selected: PageId?,
    val pending: ImportSessionId?,
    val pages: List<DocumentPage>,
)

/** A draft-scoped port: the application receives opaque identities, never Room, private filenames or a way around the source renderer. */
class AtelierAcquisition internal constructor(
    private val draft: RoomAtelierDrafts,
    private val rows: AtelierDraftDao,
    private val pages: RoomEditablePages,
    private val documents: RoomDocumentRepository,
) {
    suspend fun pending(): AtelierDraftView? = draft.view()
    suspend fun create(title: String, target: DocumentId?, revision: Long?): AtelierDraftView {
        val created = draft.create(title, target, revision)
        return draft.view(created.draftId) ?: missing()
    }
    suspend fun begin(view: AtelierDraftView): ImportSessionId = draft.reserve(view.id, view.generation)
    suspend fun refresh(view: AtelierDraftView): AtelierDraftView = draft.view(view.id) ?: missing()
    suspend fun finish(view: AtelierDraftView): AtelierDraftView {
        draft.finish(view.id, view.generation)
        return refresh(view)
    }
    suspend fun navigate(view: AtelierDraftView, page: PageId, step: Int): AtelierDraftView {
        draft.checkpoint(view.id, view.generation, page, step)
        return refresh(view)
    }
    suspend fun addMore(view: AtelierDraftView): AtelierDraftView {
        draft.reopenAcquisition(view.id, view.generation)
        return refresh(view)
    }
    suspend fun abandon(view: AtelierDraftView) { draft.abandon(view.id, view.generation) }
    suspend fun load(view: AtelierDraftView, page: PageId): EditablePage = pages.loadForDraft(view.id, view.generation, page)
    suspend fun checkpoint(view: AtelierDraftView, page: PageId, expectedRevision: Long, recipe: Recipe, renderer: RenderCoordinator): SaveRecipeResult =
        documents.checkpointDraftRecipe(view.id, view.generation, page, expectedRevision, recipe, renderer)
    suspend fun save(view: AtelierDraftView, reviewed: List<EditablePage>, renderer: RenderCoordinator): DocumentId =
        documents.publishAtelierDraft(rows, view.id, view.generation,
            reviewed.map { AtelierPageWitness(it.pageId, it.recipeRevision, it.recipeHash) }, renderer).document

    private fun missing(): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.SESSION_EXPIRED, false, "draft_unavailable", null))
}
