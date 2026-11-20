package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireWellFormedUnicode
import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.model.*
import java.text.Collator
import java.util.Collections
import java.util.Locale
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Metadata discovery through the existing Room owner. Guards and row projections share one short snapshot; no file proof, source lease, recipe decode or write is implied by showing a library entry. */
class RoomDocumentCatalog @JvmOverloads constructor(private val metadata: MetadataAccess, private val bounds: DocumentReadQueries, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : DocumentCatalog, AlbumCatalog {
    override suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary> = read(null) {
        if (limit !in 1..50) refuse(OperationErrorCode.INVALID_INPUT, "invalid_library_limit", null)
        val visible = ArrayList<DocumentSummary>(limit)
        var cursor = after?.value
        while (visible.size < limit) {
            val remaining = limit - visible.size
            val ids = orderedIds(cursor, remaining)
            for (id in ids) if (!metadata.isHiddenAtelierDraft(id)) visible.add(summary(DocumentId(id)))
            if (ids.size < remaining) break
            cursor = ids.last()
        }
        owned(visible)
    }

    override suspend fun listAlbums(order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int): AlbumPage = withContext(dispatcher) {
        val checkpoint = { coroutineContext.ensureActive() }
        read(null) {
            if (limit !in 1..50) refuse(OperationErrorCode.INVALID_INPUT, "invalid_library_limit", null)
            val comparator = albumComparator(order, locale)
            val top = PriorityQueue(limit + 1, comparator.reversed())
            var idCursor: String? = null
            // SQLite's built-in NOCASE is not locale collation. Scan bounded ID chunks in a consistent read but retain only one sorted page plus lookahead; no page/recipe/bitmap is loaded while ranking.
            while (true) {
                checkpoint()
                val rows = (bounds as? AlbumMetadataQueries)?.activeAlbumRowsAfter(idCursor, 50)
                val chunk = rows?.map { it.documentId } ?: orderedIds(idCursor, 50)
                require(chunk.size <= 50)
                for ((index, rawId) in chunk.withIndex()) {
                    val id = DocumentId(rawId)
                    require(idCursor == null || id.value > idCursor)
                    idCursor = id.value
                    if (metadata.isHiddenAtelierDraft(id.value)) continue
                    val row = rows?.get(index)?.also { validateDocument(it, id) } ?: document(id)
                    val position = AlbumPosition(id, row.title, row.createdAt, row.updatedAt)
                    if (after != null && comparator.compare(position, after) <= 0) continue
                    if (top.size < limit + 1) top.add(position)
                    else if (comparator.compare(position, top.element()) < 0) {
                        top.remove()
                        top.add(position)
                    }
                }
                if (chunk.size < 50) break
            }
            val sorted = top.sortedWith(comparator)
            val albums = owned(sorted.take(limit).map { summary(it.documentId, requirePageIntegrity = false) })
            AlbumPage(albums, if (sorted.size > limit) sorted[limit - 1] else null)
        }
    }

    private fun albumComparator(order: AlbumOrder, locale: Locale): Comparator<AlbumPosition> {
        val collator = if (order == AlbumOrder.TITLE_AZ || order == AlbumOrder.TITLE_ZA) Collator.getInstance(locale).apply {
            strength = Collator.SECONDARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        } else null
        return Comparator { a, b ->
            val key = when (order) {
                AlbumOrder.MODIFIED -> b.updatedAt.compareTo(a.updatedAt)
                AlbumOrder.CREATED -> b.createdAt.compareTo(a.createdAt)
                AlbumOrder.TITLE_AZ -> checkNotNull(collator).compare(a.title, b.title)
                AlbumOrder.TITLE_ZA -> checkNotNull(collator).compare(b.title, a.title)
            }
            if (key == 0) a.documentId.value.compareTo(b.documentId.value) else key
        }
    }

    private fun orderedIds(after: String?, limit: Int): List<String> {
        val ids = bounds.activeDocumentIdsAfter(after, limit)
        require(ids.size <= limit)
        // Validate the complete bounded ID projection before any title is read. SQL substitutes a small invalid sentinel for an oversized/raw-type ID instead of silently hiding the corrupt document.
        var previous = after
        for (id in ids) { DocumentId(id); require(previous == null || id > previous); previous = id }
        return ids
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

    private fun document(id: DocumentId): DocumentRow {
        if (metadata.isHiddenAtelierDraft(id.value)) refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", id)
        when (bounds.documentReadIsBounded(id.value)) {
            null -> refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", id)
            false -> refuse(OperationErrorCode.INTEGRITY_FAILED, "document_metadata_invalid", id)
            true -> Unit
        }
        val row = metadata.queries.document(id.value) ?: refuse(OperationErrorCode.INTEGRITY_FAILED, "document_metadata_invalid", id)
        validateDocument(row, id)
        return row
    }

    private fun validateDocument(row: DocumentRow, id: DocumentId) {
        require(row.documentId == id.value && row.documentRevision >= 1)
        require(row.title.isNotEmpty() && row.title.length <= 160 && row.title == row.title.trim())
        requireWellFormedUnicode(row.title)
        require(row.lifecycle in setOf("ACTIVE", "TOMBSTONED", "PURGING"))
        if (row.lifecycle != "ACTIVE") refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", id)
        require(row.deletedAt == null)
    }

    private fun summary(id: DocumentId, requirePageIntegrity: Boolean = true): DocumentSummary {
        val row = document(id)
        val count = bounds.activePageCount(id.value)
        require(count in 0L..100L && (!requirePageIntegrity || bounds.activePagesReadIsBounded(id.value)))
        return DocumentSummary(id, row.title, row.documentRevision, count.toInt(), metadata.queries.hasPendingImport(id.value), row.createdAt, row.updatedAt)
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
