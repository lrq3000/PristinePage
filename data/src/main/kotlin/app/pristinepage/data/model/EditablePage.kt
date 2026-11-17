package app.pristinepage.data.model

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.requireSha256

/** Owned supported metadata at one read snapshot, not a file/GC lease or authorization for a future write. Apply still compares recipeRevision; preview identity includes this complete source-bound recipe and a separate generation. */
data class EditablePage(val documentId: DocumentId, val documentRevision: Long, val pageId: PageId, val sourceId: SourceId, val recipeRevision: Long, val recipeHash: String, val recipe: Recipe) {
    init { require(documentRevision > 0 && recipeRevision > 0); requireSha256(recipeHash) }
}
