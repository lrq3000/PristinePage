package app.pristinepage.editor

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.data.model.*

interface EditorActions {
    suspend fun load(documentId: DocumentId, pageId: PageId): EditablePage
    suspend fun save(pageId: PageId, expectedRevision: Long, recipe: Recipe): SaveRecipeResult
}

/** Only supported immutable metadata belongs here. Image owners remain in the selected-preview scheduler; saved state may carry opaque IDs, never these pixels, provider references or private paths. */
data class EditorState(val page: EditablePage? = null, val draft: Recipe? = null, val loading: Boolean = true, val saving: Boolean = false, val dirty: Boolean = false, val canUndo: Boolean = false, val canRedo: Boolean = false, val confirmExit: Boolean = false, val exitReady: Boolean = false, val error: OperationError? = null, val view: EditorView = EditorView.RESULT)
