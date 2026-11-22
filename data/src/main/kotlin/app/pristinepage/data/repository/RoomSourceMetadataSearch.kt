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
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact-page selector. This lookup uses stored source metadata, not OCR state or file access. */
class RoomSourceMetadataSearch private constructor(store: PrivateImportStore.SourceMetadataComposition) {
    private val database: PristineDatabase = store.database
    private val lifetime = store.lifetime
    private val metadata = RoomMetadataAccess(database)
    private val catalog = database.catalog()

    sealed interface Hit {
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

    sealed interface Continuation
    private class ReturningAfter(
        val owner: RoomSourceMetadataSearch, val query: SourceMetadataQuery, val lastCandidate: PageId,
        val lastBlob: SourceBlobId? = null,
    ) : Continuation

    class Page internal constructor(val hits: List<Hit>, val next: Continuation?)

    suspend fun find(raw: String, after: Continuation?, limit: Int): Page =
        find(SourceMetadataQuery.parse(raw), after, limit)

    @JvmSynthetic
    internal suspend fun findCaptureDay(epochDay: Long, after: Continuation?, limit: Int): Page =
        find(CaptureDayMetadataQuery(epochDay), after, limit)

    private suspend fun find(query: SourceMetadataQuery?, after: Continuation?, limit: Int): Page {
        currentCoroutineContext().ensureActive()
        val cursor = after as? ReturningAfter
        require(limit in 1..50 && (after == null || (cursor?.owner === this && cursor.query == query)))
        if (query == null) return Page(emptyList(), null)
        return reading { checkpoint ->
            if (query is CaptureDayMetadataQuery) return@reading scanCaptureDay(query, cursor, limit, checkpoint)
            val mime = (query as? SourceMetadataQuery.Format)?.mime
            val orientation = (query as? SourceMetadataQuery.Orientation)?.value
            val candidates = catalog.scanSourceMetadataCandidates(cursor?.lastCandidate?.value, limit, mime, orientation)
            val hits = candidates.mapNotNull {
                checkpoint()
                val document = DocumentId(it.documentId)
                val page = PageId(it.pageId)
                binding(document, page, query)?.let { saved -> IssuedHit(this, document, page, query, saved) }
            }
            Page(Collections.unmodifiableList(hits), if (candidates.size == limit)
                ReturningAfter(this, query, PageId(candidates.last().pageId)) else null)
        }
    }

    private fun scanCaptureDay(query: CaptureDayMetadataQuery, cursor: ReturningAfter?, limit: Int, checkpoint: () -> Unit): Page {
        val candidates = catalog.scanCaptureDayCandidates(query.epochDay, cursor?.lastBlob?.value, cursor?.lastCandidate?.value, limit)
        val hits = candidates.mapNotNull {
            checkpoint()
            val document = DocumentId(it.documentId)
            val page = PageId(it.pageId)
            val saved = binding(document, page, query)?.takeIf { saved -> saved.blobId == it.sourceBlobId } ?: return@mapNotNull null
            IssuedHit(this, document, page, query, saved)
        }
        val next = if (candidates.size == limit) candidates.last().let {
            ReturningAfter(this, query, PageId(it.pageId), SourceBlobId(it.sourceBlobId))
        } else null
        return Page(Collections.unmodifiableList(hits), next)
    }

    internal suspend fun match(document: DocumentId, page: PageId, raw: String): Hit? {
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

    private suspend fun read(document: DocumentId, page: PageId, query: SourceMetadataQuery): SavedSourceMetadataBinding? =
        reading { binding(document, page, query) }

    private fun binding(document: DocumentId, page: PageId, query: SourceMetadataQuery): SavedSourceMetadataBinding? {
        val match = catalog.metadataMatchForSavedPage(page.value, document.value,
            (query as? SourceMetadataQuery.Format)?.mime, (query as? SourceMetadataQuery.Orientation)?.value,
            (query as? CaptureDayMetadataQuery)?.epochDay) ?: return null
        if (metadata.isHiddenAtelierDraft(document.value)) return null
        try {
            SourceId(match.sourceId)
            SourceBlobId(match.blobId)
        } catch (_: IllegalArgumentException) { return null }
        return match
    }

    private suspend fun <T> reading(action: (() -> Unit) -> T): T =
        lifetime.retainSourceMetadata { readingRetained(action) }

    private suspend fun <T> readingRetained(action: (() -> Unit) -> T): T = withContext(Dispatchers.IO) {
        val active = coroutineContext
        try {
            val result = metadata.transaction {
                active.ensureActive()
                action { active.ensureActive() }
            }
            active.ensureActive()
            result
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "source_metadata_lookup_failed", null))
        }
    }

    companion object {
        @JvmSynthetic
        internal fun bind(store: PrivateImportStore.SourceMetadataComposition) = RoomSourceMetadataSearch(store)
    }
}
