package app.pristinepage.data.repository

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.OcrPageWorkRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.SourceReferenceRow
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** An observed current job, not a lease. The exact job and intent must still match inside enqueue. */
internal data class OcrRerunBinding(val job: JobId, val intent: OcrJobInput)

/** Recheck the full stored successor, including zero starts and lease, and the observed pin state. Room detaches BLOB arrays on read. */
internal fun ocrRerunReadbackMatches(expected: JobRow, actual: JobRow, wasPinned: Boolean, isPinned: Boolean): Boolean =
    wasPinned == isPinned && expected.input.contentEquals(actual.input) && expected == actual.copy(input = expected.input)

/** The caller supplies the effective configuration from its verified asset registry; the queue neither resolves files nor grants an attempt. */
internal class RoomOcrQueue(
    database: PristineDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val auditClock: () -> Long = System::currentTimeMillis,
) {
    private val metadata = RoomMetadataAccess(database)
    private val work = database.ocrPageWork()
    private val catalog = database.catalog()
    private val current = RoomOcrCurrentWork(database)

    suspend fun enqueue(page: EditablePage, configuration: OcrConfiguration, rerun: Boolean, observed: OcrRerunBinding? = null): JobId = withContext(dispatcher) {
        val active = coroutineContext
        val id = page.pageId
        try {
            active.ensureActive()
            if (configuration.languagePacks == null || RecipeCodec.hash(page.recipe) != page.recipeHash) {
                refuse(OperationErrorCode.INVALID_INPUT, "ocr_queue_input_invalid", id)
            }
            val bytes = RecipeCodec.encode(page.recipe)
            val sourceBinding = page.recipe.geometry.source
            val base = OcrJobInput(page.documentId, OcrInput(id.value, page.recipeRevision, sourceBinding.sourcePixelHash),
                page.sourceId, page.recipeHash, 1, configuration)
            val result = metadata.transaction {
                active.ensureActive()
                if (!current.isSavedInputInTransaction(base)) refuse(OperationErrorCode.REVISION_CONFLICT, "ocr_input_changed", id)
                val sourceId = page.sourceId.value
                if (catalog.sourceReadIsBounded(sourceId) != true || catalog.recipeReadIsBounded(id.value, page.recipeRevision) != true) {
                    invalid(id)
                }
                val source = catalog.source(sourceId) ?: invalid(id)
                val recipe = catalog.recipe(id.value, page.recipeRevision) ?: invalid(id)
                if (sourceBinding != SourceBinding(source.pixelSha256, PixelSize(source.width, source.height), source.conversionFingerprint) ||
                    !recipe.canonicalRecipe.contentEquals(bytes) || recipe.recipeSchemaVersion != page.recipe.schemaVersion ||
                    source.refCount != catalog.countSourceReferences(sourceId)
                ) invalid(id)

                val old = current.readStoredInTransaction(id)
                val predecessorPinned = if (observed != null) {
                    if (!rerun || old == null || old.job.jobId != observed.job.value ||
                        old.intent != observed.intent || !current.isSavedInputInTransaction(old.intent)
                    ) refuse(OperationErrorCode.REVISION_CONFLICT, "ocr_rerun_observation_changed", id)
                    val pinned = work.hasAnyJobPin(old.job.jobId)
                    if (!RoomOcrAttempts.hasConsistentStoredState(old.job) ||
                        (pinned && !work.hasExactJobPin(old.job.jobId, sourceId)) ||
                        (!pinned && old.job.state in setOf("PENDING", "ADMITTED", "RUNNING"))
                    ) invalid(id)
                    pinned
                } else false
                if (old != null && !rerun &&
                    old.intent.documentId == base.documentId && old.intent.sourceId == base.sourceId &&
                    old.intent.input == base.input && old.intent.recipeSha256 == base.recipeSha256
                ) {
                    // Default preference changes do not relabel or restart an existing input.
                    if (old.job.state in setOf("PENDING", "ADMITTED", "RUNNING") && !work.hasExactJobPin(old.job.jobId, sourceId)) {
                        invalid(id)
                    }
                    active.ensureActive()
                    return@transaction JobId(old.job.jobId)
                }
                val generation = old?.intent?.generation?.let {
                    if (it == Long.MAX_VALUE) refuse(OperationErrorCode.RESOURCE_LIMIT, "ocr_generation_limit", id)
                    it + 1
                } ?: 1L
                val intent = base.copy(generation = generation)
                val payload = intent.encode()
                val job = JobId(UUID.randomUUID().toString())
                val now = auditClock()
                val inserted = JobRow(job.value, OcrJobInput.KIND, intent.key, intent.digest, null, null, payload,
                    "PENDING", 0, null, null, 0, null, null, now, now)
                work.insertJob(inserted)
                if (catalog.insertReference(SourceReferenceRow("JOB", job.value, sourceId, now)) == -1L ||
                    !work.hasExactJobPin(job.value, sourceId)
                ) invalid(id)
                val newCount = catalog.countSourceReferences(sourceId)
                if (newCount <= source.refCount || newCount - source.refCount != 1L ||
                    catalog.setSourceRefCount(sourceId, newCount) != 1
                ) invalid(id)
                active.ensureActive()
                if (old == null) {
                    work.insertPointer(OcrPageWorkRow(id.value, job.value, generation, intent.digest, payload.size, null, null))
                } else if (work.replaceCurrent(id.value, old.job.jobId, old.intent.generation, old.job.canonicalInputSha256,
                        old.job.input.size, job.value, generation, intent.digest, payload.size) != 1
                ) refuse(OperationErrorCode.REVISION_CONFLICT, "ocr_job_no_longer_current", id)
                val written = current.readInTransaction(id) ?: invalid(id)
                if (written.job.jobId != job.value || written.intent != intent || written.job.state != "PENDING" ||
                    written.resultJobId != old?.resultJobId || written.projectionVersion != old?.projectionVersion ||
                    !work.hasExactJobPin(job.value, sourceId) || !work.sourcePinCountMatches(sourceId, newCount)
                ) invalid(id)
                if (observed != null) {
                    val pinned = work.hasAnyJobPin(observed.job.value)
                    if (!ocrRerunReadbackMatches(inserted, written.job, predecessorPinned, pinned) ||
                        (pinned && !work.hasExactJobPin(observed.job.value, sourceId))
                    ) invalid(id)
                }
                active.ensureActive()
                job
            }
            active.ensureActive()
            result
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: IllegalArgumentException) { invalid(id) }
        catch (_: Exception) { refuse(OperationErrorCode.STORAGE_FAILED, "ocr_queue_write_failed", id, retryable = true) }
    }

    private fun invalid(page: PageId): Nothing = refuse(OperationErrorCode.INTEGRITY_FAILED, "ocr_queue_binding_invalid", page)

    private fun refuse(code: OperationErrorCode, key: String, page: PageId, retryable: Boolean = false): Nothing =
        throw RepositoryFailure(OperationError(code, retryable, key, page.value))
}
