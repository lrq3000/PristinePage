package app.pristinepage.editor

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.data.model.*

interface EditorActions {
    suspend fun load(documentId: DocumentId, pageId: PageId): EditablePage
    suspend fun save(pageId: PageId, expectedRevision: Long, recipe: Recipe): SaveRecipeResult
}

/** Only supported immutable metadata belongs here. Image owners remain in the selected-preview scheduler; saved state may carry opaque IDs, never these pixels, provider references or private paths. */
data class EditorState(val page: EditablePage? = null, val draft: Recipe? = null, val loading: Boolean = true, val saving: Boolean = false, val dirty: Boolean = false, val canUndo: Boolean = false, val canRedo: Boolean = false, val confirmExit: Boolean = false, val exitReady: Boolean = false, val error: OperationError? = null, val view: EditorView = EditorView.RESULT)

internal val EditorState.saveNeedsReopen: Boolean get() = error?.safeMessageKey == "editor_save_delivery_unconfirmed"

internal fun EditorState.shapeBlocksApply(preview: PreviewState): Boolean {
    val page = page ?: return false
    val draft = draft ?: return false
    val accepted = page.recipe.geometry
    // Keep the existing save behavior for Looks and quarter turns on an already saved shape. A different shape requires proof of the entire draft, including its new turns and Look.
    if (draft.geometry.withOutputQuarterTurns(accepted.outputQuarterTurns) == accepted) return false
    val request = preview.request ?: return true
    // An Input request can equal the draft when finishing is off. Its separately tracked view is not Result proof even in that case.
    val result = preview.preview ?: return true
    return view != EditorView.RESULT || preview.view != EditorView.RESULT || preview.processing || preview.previous || preview.failure != null ||
        result.status != RenderStatus.OK || result.request != request ||
        request.pageId != page.pageId.value || request.sourceId != page.sourceId.value ||
        request.sourcePixelHash != draft.geometry.source.sourcePixelHash ||
        request.recipeRevision != page.recipeRevision || request.recipe != draft
}
