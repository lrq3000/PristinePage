package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** ID-only result port. FTS rows are candidates; opening a displayed hit requires a fresh check of the same saved input and producer. */
internal class RoomOcrSearch(
    database: PristineDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val metadata = RoomMetadataAccess(database)
    private val results = database.ocrResults()
    private val jobs = RoomOcrJobReader(database)
    private val work = RoomOcrCurrentWork(database)

    data class Hit(val document: DocumentId, val page: PageId, val source: SourceId, val recipeRevision: Long, val producingJob: JobId)
    data class Page(val hits: List<Hit>, val nextAfter: PageId?)

    suspend fun find(query: String, after: PageId?, limit: Int): Page = withContext(dispatcher) {
        require(limit in 1..50)
        val terms = OcrSearchTerms.create(query) ?: return@withContext Page(emptyList(), null)
        val active = coroutineContext
        metadata.transaction {
            active.ensureActive()
            val candidates = results.matchPages(terms, after, limit)
            val hits = candidates.mapNotNull { row ->
                active.ensureActive()
                checkedHit(pageId(row.pageId))?.takeIf { it.document.value == row.documentId }
            }
            // Progress follows the SQL candidates, not the accepted matches. A rejected candidate cannot starve later IDs.
            val next = if (candidates.size == limit) pageId(candidates.last().pageId) else null
            active.ensureActive()
            Page(Collections.unmodifiableList(hits), next)
        }
    }

    suspend fun canOpen(hit: Hit): Boolean = withContext(dispatcher) {
        val active = coroutineContext
        metadata.transaction {
            active.ensureActive()
            val fresh = checkedHit(hit.page)
            active.ensureActive()
            fresh == hit
        }
    }

    private fun checkedHit(page: PageId): Hit? = try {
        verifyProducer(page)
    } catch (failure: RepositoryFailure) {
        if (failure.error.code !in setOf(OperationErrorCode.INTEGRITY_FAILED, OperationErrorCode.REVISION_CONFLICT)) throw failure
        null
    }

    private fun verifyProducer(page: PageId): Hit? {
        val current = work.readStoredInTransaction(page) ?: return null
        val distinctProducer = current.resultJobId ?: return null
        if (current.projectionVersion != RoomOcrResults.PROJECTION_VERSION) return null
        val sameJob = distinctProducer.value == current.job.jobId
        val (intent, job) = if (sameJob) current.intent to current.job else
            jobs.readProducerInTransaction(distinctProducer, page) ?: return null
        if ((!sameJob && intent.generation >= current.intent.generation) ||
            job.state != "SUCCEEDED" || job.attempt !in 1L..3L || job.leaseGeneration != job.attempt ||
            job.leaseOwner != null || job.leaseUntil != null || job.errorCode != null ||
            !work.isSavedInputInTransaction(intent)
        ) return null
        val config = intent.configuration
        if (!results.matchesProducerMetadata(page.value, intent.input.recipeRevision, intent.input.sourcePixelSha256,
                config.engineId, config.engineVersion, config.layout.name)
        ) return null
        return Hit(intent.documentId, page, intent.sourceId, intent.input.recipeRevision, distinctProducer)
    }

    private fun pageId(value: String): PageId = try { PageId(value) }
    catch (_: IllegalArgumentException) {
        throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_candidate_identity_invalid", null))
    }
}
