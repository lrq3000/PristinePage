package app.pristinepage.data.repository

import app.pristinepage.data.db.SavedSourceMetadataBinding
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceBlobId
import app.pristinepage.data.model.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact-page selector. This lookup uses stored source metadata, not OCR state or file access. */
internal class RoomSourceMetadataSearch(
    database: PristineDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val metadata = RoomMetadataAccess(database)
    private val catalog = database.catalog()

    internal sealed interface Hit {
        val document: DocumentId
        val page: PageId
    }

    private class IssuedHit(
        val owner: RoomSourceMetadataSearch,
        override val document: DocumentId,
        override val page: PageId,
        val query: SourceMetadataQuery,
        val binding: SavedSourceMetadataBinding,
    ) : Hit

    suspend fun match(document: DocumentId, page: PageId, raw: String): Hit? {
        currentCoroutineContext().ensureActive()
        val query = SourceMetadataQuery.parse(raw) ?: return null
        val original = read(document, page, query) ?: return null
        return IssuedHit(this, document, page, query, original)
    }

    suspend fun canOpen(hit: Hit): Boolean {
        currentCoroutineContext().ensureActive()
        val issued = hit as? IssuedHit ?: return false
        return issued.owner === this && read(issued.document, issued.page, issued.query) == issued.binding
    }

    private suspend fun read(document: DocumentId, page: PageId, query: SourceMetadataQuery): SavedSourceMetadataBinding? = withContext(dispatcher) {
        val active = coroutineContext
        try {
            val result = metadata.transaction {
                active.ensureActive()
                val match = catalog.metadataMatchForSavedPage(page.value, document.value,
                    (query as? SourceMetadataQuery.Format)?.mime, (query as? SourceMetadataQuery.Orientation)?.value)
                    ?: return@transaction null
                if (metadata.isHiddenAtelierDraft(document.value)) return@transaction null
                try {
                    SourceId(match.sourceId)
                    SourceBlobId(match.blobId)
                } catch (_: IllegalArgumentException) { return@transaction null }
                match
            }
            active.ensureActive()
            result
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "source_metadata_lookup_failed", null))
        }
    }
}
