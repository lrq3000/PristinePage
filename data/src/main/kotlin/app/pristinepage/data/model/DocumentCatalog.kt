package app.pristinepage.data.model

import app.pristinepage.core.pipeline.requireWellFormedUnicode

/** Stored titles must be exactly usable by the live catalog. Unlike user entry, admission must never repair or trim them. */
internal fun requireStoredAlbumTitle(value: String) {
    require(value.length in 1..160 && value == value.trim())
    requireWellFormedUnicode(value)
}

/** Metadata discovery only: a count/state is not a source-file integrity proof, completed render or successful export. No URI, private path, recipe BLOB or borrowed image enters a library value. */
data class DocumentSummary(
    val documentId: DocumentId,
    val title: String,
    val documentRevision: Long,
    val pageCount: Int,
    val hasPendingImport: Boolean,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

data class DocumentPage(val pageId: PageId, val sourceId: SourceId, val recipeRevision: Long, val ordinal: Int)

data class DocumentOverview(val document: DocumentSummary, val pages: List<DocumentPage>)
