package app.pristinepage.editor

import app.pristinepage.ApplicationImports
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.data.model.*

/** Cold adapters to the one initialized Application graph; no Activity-owned store, renderer or URI permission is manufactured by opening an editor. */
class ApplicationEditorActions(private val open: suspend () -> ApplicationImports) : EditorActions {
    override suspend fun load(documentId: DocumentId, pageId: PageId) = open().store.editablePages.load(documentId, pageId)
    override suspend fun save(pageId: PageId, expectedRevision: Long, recipe: Recipe) = open().store.documents.saveRecipe(pageId, expectedRevision, recipe)
}
