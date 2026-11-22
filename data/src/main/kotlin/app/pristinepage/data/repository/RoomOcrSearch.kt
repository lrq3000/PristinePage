package app.pristinepage.data.repository

import app.pristinepage.core.ocr.OcrError
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.SavedOcrWork.PagePhase
import java.nio.charset.CharacterCodingException
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
    private val pageWork = database.ocrPageWork()

    data class Hit(val document: DocumentId, val page: PageId, val source: SourceId, val recipeRevision: Long, val producingJob: JobId)
    data class Page(val hits: List<Hit>, val nextAfter: PageId?)
    class Status(val phase: PagePhase, val attemptsStarted: Int, val lastError: OcrError?, val binding: OcrRerunBinding)

    suspend fun find(query: String, after: PageId?, limit: Int): Page = withContext(dispatcher) {
        require(limit in 1..50)
        val terms = OcrSearchTerms.create(query) ?: return@withContext Page(emptyList(), null)
        val active = coroutineContext
        metadata.transaction {
            active.ensureActive()
            val candidates = results.matchPages(terms, after, limit)
            val hits = candidates.mapNotNull { row ->
                active.ensureActive()
                checkedProducer(pageId(row.pageId))?.hit?.takeIf { it.document.value == row.documentId }
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
            val fresh = checkedProducer(hit.page)?.hit
            active.ensureActive()
            fresh == hit
        }
    }

    suspend fun readText(document: DocumentId, page: PageId, expectedRevision: Long? = null): SavedOcrWork.ReadText? = withContext(dispatcher) {
        val active = coroutineContext
        metadata.transaction {
            active.ensureActive()
            val producing = checkedProducer(page)?.takeIf {
                it.hit.document == document && (expectedRevision == null || it.hit.recipeRevision == expectedRevision)
            } ?: return@transaction null
            val intent = producing.intent
            val config = intent.configuration
            val raw = results.selectVerbatim(page.value, intent.input.recipeRevision, intent.input.sourcePixelSha256,
                config.engineId, config.engineVersion, config.layout.name)?.recognizedUtf8 ?: return@transaction null
            val text = decodeStoredOcrText(raw) ?: return@transaction null
            active.ensureActive()
            SavedOcrWork.ReadText(text, config, producing.previousGeneration)
        }
    }

    suspend fun readStatus(document: DocumentId, page: PageId, expectedRevision: Long? = null): Status? = withContext(dispatcher) {
        val active = coroutineContext
        val status = metadata.transaction {
            active.ensureActive()
            if (!pageWork.visibleStatusPage(page.value, document.value) || metadata.isHiddenAtelierDraft(document.value)) return@transaction null
            val current = work.readStoredInTransaction(page) ?: return@transaction null
            if (current.intent.documentId != document || (expectedRevision != null && current.intent.input.recipeRevision != expectedRevision) ||
                !work.isSavedInputInTransaction(current.intent)) return@transaction null
            val job = current.job
            if (!RoomOcrAttempts.hasConsistentStoredState(job)) invalidStatus(page)
            val phase = when (job.state) {
                "PENDING", "ADMITTED" -> PagePhase.QUEUED
                "RUNNING" -> PagePhase.RUNNING
                "FAILED" -> PagePhase.FAILED
                "CANCELED" -> PagePhase.CANCELED
                "SUCCEEDED" -> {
                    // Check result provenance and bounded TEXT metadata, not the recognized-text projection returned to the editor.
                    if (verifyProducer(page, current)?.previousGeneration != false) invalidStatus(page)
                    PagePhase.READY
                }
                else -> invalidStatus(page)
            }
            if ((phase == PagePhase.QUEUED || phase == PagePhase.RUNNING) &&
                !pageWork.hasExactJobPin(job.jobId, current.intent.sourceId.value)) invalidStatus(page)
            Status(phase, job.attempt.toInt(), job.errorCode?.let(OcrError::valueOf), OcrRerunBinding(JobId(job.jobId), current.intent))
        }
        active.ensureActive()
        status
    }

    /** Only an unfinished exact saved generation can name a wake for an album tile. Do not authenticate or read a completed result for this observation. */
    suspend fun pendingTarget(document: DocumentId, page: PageId, revision: Long): SavedOcrWork.Target? = withContext(dispatcher) {
        val active = coroutineContext
        val target = metadata.transaction {
            active.ensureActive()
            if (!pageWork.visibleStatusPage(page.value, document.value) || metadata.isHiddenAtelierDraft(document.value)) return@transaction null
            val current = work.readStoredInTransaction(page) ?: return@transaction null
            val job = current.job
            if (current.intent.documentId != document || current.intent.input.recipeRevision != revision ||
                job.state !in setOf("PENDING", "ADMITTED", "RUNNING") || !work.isSavedInputInTransaction(current.intent)
            ) return@transaction null
            if (!RoomOcrAttempts.hasConsistentStoredState(job) || !pageWork.hasExactJobPin(job.jobId, current.intent.sourceId.value)) invalidStatus(page)
            SavedOcrWork.Target(page, JobId(job.jobId))
        }
        active.ensureActive()
        target
    }

    private fun invalidStatus(page: PageId): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_status_invalid", page.value))

    private class Producer(val hit: Hit, val intent: OcrJobInput, val previousGeneration: Boolean)

    private fun checkedProducer(page: PageId): Producer? = try {
        verifyProducer(page)
    } catch (failure: RepositoryFailure) {
        if (failure.error.code !in setOf(OperationErrorCode.INTEGRITY_FAILED, OperationErrorCode.REVISION_CONFLICT)) throw failure
        null
    }

    private fun verifyProducer(page: PageId, current: RoomOcrCurrentWork.Current? = work.readStoredInTransaction(page)): Producer? {
        current ?: return null
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
        return Producer(Hit(intent.documentId, page, intent.sourceId, intent.input.recipeRevision, distinctProducer), intent, !sameJob)
    }

    private fun pageId(value: String): PageId = try { PageId(value) }
    catch (_: IllegalArgumentException) {
        throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_candidate_identity_invalid", null))
    }
}

/** Internal second admission after the raw SQL byte bound. Never replace invalid sequences or normalize the displayed text. */
internal fun decodeStoredOcrText(raw: ByteArray): OcrText? = try {
    OcrText(raw.decodeToString(throwOnInvalidSequence = true))
} catch (_: CharacterCodingException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
