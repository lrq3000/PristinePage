package app.pristinepage.data.repository

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrError
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.library.LibrarySavedOcr
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.SavedOcrWork.PagePhase
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** ID-only result port. FTS rows are candidates; opening a displayed hit requires a fresh check of the same saved input and producer. */
internal class RoomOcrSearch(
    private val database: PristineDatabase,
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
    class TextSnapshot(val text: OcrText, val configuration: OcrConfiguration, val previousGeneration: Boolean, val binding: TextBinding)

    /** Capture a digest of the exact UTF-8 seen by the panel without retaining plaintext in its export token. */
    class TextBinding(val producer: Hit, val inputDigest: String, reviewedBytes: ByteArray) {
        private val digest = sha256(reviewedBytes)
        fun matchesInput(hit: Hit, intent: OcrJobInput): Boolean = producer == hit && inputDigest == intent.digest
        fun matches(bytes: ByteArray): Boolean = MessageDigest.isEqual(digest, sha256(bytes))
        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }

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

    suspend fun readText(document: DocumentId, page: PageId, expectedRevision: Long? = null): TextSnapshot? = withContext(dispatcher) {
        val active = coroutineContext
        metadata.transaction {
            active.ensureActive()
            val producing = checkedProducer(page)?.takeIf {
                it.hit.document == document && (expectedRevision == null || it.hit.recipeRevision == expectedRevision)
            } ?: return@transaction null
            val raw = selectVerbatim(producing) ?: return@transaction null
            val text = decodeStoredOcrText(raw) ?: return@transaction null
            val binding = TextBinding(producing.hit, producing.intent.digest, raw)
            active.ensureActive()
            TextSnapshot(text, producing.intent.configuration, producing.previousGeneration, binding)
        }
    }

    /** Snapshot the actual producer and exact recognized text, not pending rerun settings or the FTS projection. */
    internal fun captureForLibraryInTransaction(document: DocumentId, page: PageId, acceptedRevision: Long): LibrarySavedOcr? {
        check(database.inTransaction())
        require(acceptedRevision > 0)
        val storedRevision = results.libraryResultRevision(page.value)
        val pointer = pageWork.libraryResultPointer(page.value)
        val resultId = pointer?.resultJobId
        if (storedRevision == null && resultId == null && pointer?.projectionVersion == null) return null
        if (storedRevision == null || storedRevision !in 1..acceptedRevision || pointer == null || resultId == null ||
            pointer.projectionVersion != RoomOcrResults.PROJECTION_VERSION.toLong()
        ) invalidLibraryOcr(page)
        val id = try { JobId(resultId).also { JobId(pointer.jobId) } } catch (_: IllegalArgumentException) { invalidLibraryOcr(page) }
        val (intent, job) = jobs.readProducerInTransaction(id, page) ?: invalidLibraryOcr(page)
        val sameJob = pointer.jobId == resultId
        if (intent.documentId != document || intent.input.recipeRevision != storedRevision || !job.isOcrProducer() ||
            (sameJob && intent.generation != pointer.generation) || (!sameJob && intent.generation >= pointer.generation)
        ) invalidLibraryOcr(page)
        val producer = Producer(Hit(document, page, intent.sourceId, storedRevision, id), intent, job, !sameJob)
        val text = selectVerbatim(producer)?.let(::decodeStoredOcrText) ?: invalidLibraryOcr(page)
        if (storedRevision < acceptedRevision) {
            val catalog = database.catalog()
            if (catalog.recipeReadIsBounded(page.value, storedRevision) != true) invalidLibraryOcr(page)
            val old = catalog.recipe(page.value, storedRevision) ?: invalidLibraryOcr(page)
            if (old.recipeSha256 != intent.recipeSha256 || old.sourceId != intent.sourceId.value) invalidLibraryOcr(page)
            return null // A real result for a superseded edition is not copied onto the accepted one.
        }
        if (!libraryInputMatches(intent)) invalidLibraryOcr(page)
        return LibrarySavedOcr(id, intent, text, job.attempt, job.createdAt, job.updatedAt)
    }

    internal fun libraryInputMatches(intent: OcrJobInput): Boolean {
        check(database.inTransaction())
        if (work.isSavedInputInTransaction(intent)) return true
        val document = intent.documentId.value
        return pageWork.removedLibraryInputMatches(intent.input.pageId, document, intent.sourceId.value,
            intent.input.recipeRevision, intent.recipeSha256, intent.input.sourcePixelSha256) &&
            !metadata.isHiddenAtelierDraft(document)
    }

    private fun invalidLibraryOcr(page: PageId): Nothing = throw RepositoryFailure(
        OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "library_saved_ocr_invalid", page.value),
    )

    suspend fun resolveTextExport(binding: TextBinding): ByteArray? = withContext(dispatcher) {
        val active = coroutineContext
        val payload = metadata.transaction { resolveTextExportInTransaction(binding) { active.ensureActive() } }
        active.ensureActive()
        payload
    }

    @JvmSynthetic
    internal fun resolveTextExportInTransaction(binding: TextBinding, checkpoint: () -> Unit): ByteArray? {
        checkpoint()
        val producer = checkedProducer(binding.producer.page)?.takeIf { binding.matchesInput(it.hit, it.intent) } ?: return null
        // Equality with the digest of the previously accepted bytes authenticates their encoding as well as their content.
        return selectVerbatim(producer)?.takeIf(binding::matches).also { checkpoint() }
    }

    private fun selectVerbatim(producing: Producer): ByteArray? {
        val intent = producing.intent
        val config = intent.configuration
        return results.selectVerbatim(producing.hit.page.value, intent.input.recipeRevision, intent.input.sourcePixelSha256,
            config.engineId, config.engineVersion, config.layout.name)?.recognizedUtf8
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

    private class Producer(val hit: Hit, val intent: OcrJobInput, val job: JobRow, val previousGeneration: Boolean)

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
        if ((!sameJob && intent.generation >= current.intent.generation) || !job.isOcrProducer() ||
            !work.isSavedInputInTransaction(intent)
        ) return null
        val config = intent.configuration
        if (!results.matchesProducerMetadata(page.value, intent.input.recipeRevision, intent.input.sourcePixelSha256,
                config.engineId, config.engineVersion, config.layout.name)
        ) return null
        return Producer(Hit(intent.documentId, page, intent.sourceId, intent.input.recipeRevision, distinctProducer), intent, job, !sameJob)
    }

    private fun JobRow.isOcrProducer(): Boolean = state == "SUCCEEDED" && attempt in 1L..3L &&
        leaseGeneration == attempt && leaseOwner == null && leaseUntil == null && errorCode == null

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
