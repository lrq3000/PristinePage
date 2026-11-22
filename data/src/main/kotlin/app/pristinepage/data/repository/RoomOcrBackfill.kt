package app.pristinepage.data.repository

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Discover durable work independently of save callbacks. The same database guards discovery, enqueue, and the ID-only hints; none of these grants an attempt. */
internal class RoomOcrBackfill(
    database: PristineDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val metadata = RoomMetadataAccess(database)
    private val editable = RoomEditablePages(metadata, database.catalog(), dispatcher)
    private val work = database.ocrPageWork()
    private val current = RoomOcrCurrentWork(database)
    private val queue = RoomOcrQueue(database, dispatcher)

    data class Target(val page: PageId, val job: JobId)
    /** nextAfter is an internal scan position, not a result; it may name a locator rejected after decoding its visibility receipt. Do not display or navigate to it. */
    data class Batch(val targets: List<Target>, val nextAfter: PageId?)

    /** A null configuration suppresses new writes, not discovery of still-current jobs. Non-null settings must come from the caller's verified local runtime/pack registry. */
    suspend fun reconcile(configuration: OcrConfiguration?, after: PageId?, limit: Int): Batch = withContext(dispatcher) {
        require(limit in 1..50 && (configuration == null || configuration.languagePacks != null))
        val candidates = work.scanSavedPages(after?.value, limit)
        val targets = candidates.mapNotNull { row ->
            coroutineContext.ensureActive()
            val page = PageId(row.pageId)
            val document = DocumentId(row.documentId)
            try {
                val existing = liveWork(document, page)
                if (existing != null) {
                    existing.target()
                } else if (configuration != null) {
                    val saved = editable.load(document, page)
                    queue.enqueue(saved, configuration, rerun = false)
                    liveWork(document, page)?.target()
                } else {
                    null
                }
            } catch (failure: RepositoryFailure) {
                // A missing, invalid or unsupported page must not hold up the rest of the batch. Storage failures must surface so a caller can retry the batch.
                if (failure.error.code !in SKIP_PAGE) throw failure
                null
            }
        }
        coroutineContext.ensureActive()
        // Carry the scan position even if this batch found only terminal or rejected pages.
        Batch(Collections.unmodifiableList(targets), if (candidates.size == limit) PageId(candidates.last().pageId) else null)
    }

    private fun liveWork(document: DocumentId, page: PageId): RoomOcrCurrentWork.Current? = metadata.transaction {
        val saved = current.readStoredInTransaction(page) ?: return@transaction null
        if (saved.intent.documentId != document || !current.isSavedInputInTransaction(saved.intent)) return@transaction null
        if (saved.job.state in DISCOVERABLE &&
            (!RoomOcrAttempts.hasConsistentStoredState(saved.job) || !work.hasExactJobPin(saved.job.jobId, saved.intent.sourceId.value))
        ) {
            throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_backfill_job_invalid", page.value))
        }
        saved
    }

    private fun RoomOcrCurrentWork.Current.target(): Target? =
        if (job.state in DISCOVERABLE) Target(PageId(intent.input.pageId), JobId(job.jobId)) else null

    private companion object {
        val DISCOVERABLE = setOf("PENDING", "ADMITTED", "RUNNING")
        val SKIP_PAGE = setOf(OperationErrorCode.SOURCE_UNAVAILABLE, OperationErrorCode.REVISION_CONFLICT,
            OperationErrorCode.UNSUPPORTED_VERSION, OperationErrorCode.PROVIDER_UNAVAILABLE, OperationErrorCode.INTEGRITY_FAILED)
    }
}
