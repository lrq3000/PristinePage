package app.pristinepage.data.repository

import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentOverview
import app.pristinepage.data.model.DocumentSummary

/** Short, transactionally consistent metadata snapshots, not a global library freeze or implicit source ownership. Opaque-ID keyset pagination is explicit; a new snapshot may reflect subsequent document commands. */
interface DocumentCatalog {
    suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary>
    suspend fun load(documentId: DocumentId): DocumentOverview
}

/** Scalar/raw projections precede existing catalog row materialization within the same metadata transaction. IDs returned by pagination must themselves be bounded before they reach Kotlin. No provider/file/decode/write operation belongs to this boundary. */
interface DocumentReadQueries {
    fun activeDocumentIdsAfter(after: String?, limit: Int): List<String>
    fun documentReadIsBounded(documentId: String): Boolean?
    fun activePageCount(documentId: String): Long
    fun activePagesReadIsBounded(documentId: String): Boolean
}
