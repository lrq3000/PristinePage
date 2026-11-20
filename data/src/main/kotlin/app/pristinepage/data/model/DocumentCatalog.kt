package app.pristinepage.data.model

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
