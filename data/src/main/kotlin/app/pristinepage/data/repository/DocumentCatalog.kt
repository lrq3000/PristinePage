package app.pristinepage.data.repository

import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentOverview
import app.pristinepage.data.model.DocumentSummary
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.AlbumOrder
import app.pristinepage.data.model.AlbumPage
import app.pristinepage.data.model.AlbumPosition
import java.util.Locale

/** Short, transactionally consistent metadata snapshots, not a global library freeze or implicit source ownership. Opaque-ID keyset pagination is explicit; a new snapshot may reflect subsequent document commands. */
interface DocumentCatalog {
    suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary>
    suspend fun load(documentId: DocumentId): DocumentOverview
    suspend fun activeImport(documentId: DocumentId): ImportSessionId?
}

/** Globally sorted metadata pages. Each call is a consistent read, not a snapshot spanning the user's whole scrolling session. */
interface AlbumCatalog {
    suspend fun listAlbums(order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int): AlbumPage
}

interface SearchableAlbumCatalog : AlbumCatalog {
    suspend fun searchAlbums(query: AlbumSearchQuery, order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int): AlbumPage
}

/** A production Room projection validates raw storage before any unbounded string can cross the database boundary. */
interface AlbumMetadataQueries {
    fun activeAlbumRowsAfter(after: String?, limit: Int): List<DocumentRow>
}

/** Scalar/raw projections precede existing catalog row materialization within the same metadata transaction. IDs returned by pagination must themselves be bounded before they reach Kotlin. No provider/file/decode/write operation belongs to this boundary. */
interface DocumentReadQueries {
    fun activeDocumentIdsAfter(after: String?, limit: Int): List<String>
    fun documentReadIsBounded(documentId: String): Boolean?
    fun activePageCount(documentId: String): Long
    fun activePagesReadIsBounded(documentId: String): Boolean
    fun activeImportIds(documentId: String): List<String>
}
