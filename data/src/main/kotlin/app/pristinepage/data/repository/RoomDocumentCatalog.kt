package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireWellFormedUnicode
import app.pristinepage.data.model.*
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Metadata discovery through the existing Room owner. Guards and row projections share one short snapshot; no file proof, source lease, recipe decode or write is implied by showing a library entry. */
class RoomDocumentCatalog @JvmOverloads constructor(private val metadata: MetadataAccess, private val bounds: DocumentReadQueries, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : DocumentCatalog {
    override suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary> = read(null) {
        if (limit !in 1..50) refuse(OperationErrorCode.INVALID_INPUT, "invalid_library_limit", null)
        val ids = bounds.activeDocumentIdsAfter(after?.value, limit)
        require(ids.size <= limit)
        // Validate the complete bounded ID projection before any title is read. SQL substitutes a small invalid sentinel for an oversized/raw-type ID instead of silently hiding the corrupt document.
        var previous = after?.value
        for (id in ids) { DocumentId(id); require(previous == null || id > previous); previous = id }
        owned(ids.map { summary(DocumentId(it)) })
    }

    override suspend fun load(documentId: DocumentId): DocumentOverview = read(documentId) {
        val document = summary(documentId)
        val rows = metadata.queries.activePages(documentId.value)
        require(rows.size == document.pageCount)
        val seen = HashSet<String>()
        val pages = rows.mapIndexed { ordinal, page ->
            require(page.documentId == documentId.value && page.state == "ACTIVE" && page.removedAt == null)
            require(page.ordinal == ordinal && page.currentRecipeRevision >= 1 && seen.add(page.pageId))
            DocumentPage(PageId(page.pageId), SourceId(page.sourceId), page.currentRecipeRevision, ordinal)
        }
        DocumentOverview(document, owned(pages))
    }

    /** Recover the exact unresolved insertion without UI saved state. Two candidates are enough to detect ambiguity; no URI/session row is materialized and no selection is resumed or rebound. */
    override suspend fun activeImport(documentId: DocumentId): ImportSessionId? = read(documentId) {
        val document = summary(documentId)
        val ids = bounds.activeImportIds(documentId.value)
        require(ids.size <= 1 && document.hasPendingImport == ids.isNotEmpty())
        ids.singleOrNull()?.let(::ImportSessionId)
    }

    private fun summary(id: DocumentId): DocumentSummary {
        when (bounds.documentReadIsBounded(id.value)) {
            null -> refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", id)
            false -> refuse(OperationErrorCode.INTEGRITY_FAILED, "document_metadata_invalid", id)
            true -> Unit
        }
        val row = metadata.queries.document(id.value) ?: refuse(OperationErrorCode.INTEGRITY_FAILED, "document_metadata_invalid", id)
        require(row.documentId == id.value && row.documentRevision >= 1)
        require(row.title.isNotEmpty() && row.title.length <= 160 && row.title == row.title.trim())
        requireWellFormedUnicode(row.title)
        require(row.lifecycle in setOf("ACTIVE", "TOMBSTONED", "PURGING"))
        if (row.lifecycle != "ACTIVE") refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", id)
        require(row.deletedAt == null)
        val count = bounds.activePageCount(id.value)
        require(count in 0L..100L && bounds.activePagesReadIsBounded(id.value))
        return DocumentSummary(id, row.title, row.documentRevision, count.toInt(), metadata.queries.hasPendingImport(id.value))
    }

    private suspend fun <T> read(id: DocumentId?, action: () -> T): T = withContext(dispatcher) {
        try {
            coroutineContext.ensureActive()
            val result = metadata.transaction { coroutineContext.ensureActive(); action() }
            coroutineContext.ensureActive()
            result
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INTEGRITY_FAILED, "document_metadata_invalid", id) }
        catch (_: Exception) { refuse(OperationErrorCode.STORAGE_FAILED, "document_read_failed", id, true) }
    }

    private fun <T> owned(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    private fun refuse(code: OperationErrorCode, key: String, id: DocumentId?, retryable: Boolean = false): Nothing = throw RepositoryFailure(OperationError(code, retryable, key, id?.value))
}
