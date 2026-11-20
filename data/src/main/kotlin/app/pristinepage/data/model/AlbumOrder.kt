package app.pristinepage.data.model

enum class AlbumOrder { MODIFIED, CREATED, TITLE_AZ, TITLE_ZA }

/** Values captured together from the ordered catalog; a caller must discard the cursor on a known mutation or order/locale change. */
data class AlbumPosition(val documentId: DocumentId, val title: String, val createdAt: Long, val updatedAt: Long)

data class AlbumPage(val albums: List<DocumentSummary>, val next: AlbumPosition?)
