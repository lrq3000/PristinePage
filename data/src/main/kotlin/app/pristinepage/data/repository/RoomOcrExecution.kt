package app.pristinepage.data.repository

import android.os.SystemClock
import app.pristinepage.core.ocr.OcrError
import app.pristinepage.data.db.OcrProjectionLimit
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.SavedOcrWork.Handoff
import app.pristinepage.data.repository.SavedOcrWork.Outcome
import app.pristinepage.data.repository.SavedOcrWork.Prepared
import app.pristinepage.data.repository.SavedOcrWork.Preparer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One retained executor per application store. An external scheduler can offer hints, but source preparation, attempt ownership and publication are separately fenced. */
internal class RoomOcrExecution(
    database: PristineDatabase,
    private val owners: FileOperationOwners,
    leaseClock: () -> Long = SystemClock::elapsedRealtime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val editable = RoomEditablePages(RoomMetadataAccess(database), database.catalog(), dispatcher)
    private val dispatch = RoomOcrDispatch(database, owners, owners, leaseClock, dispatcher)
    private val attempts = RoomOcrAttempts(database, owners, leaseClock = leaseClock, dispatcher = dispatcher)
    private val results = RoomOcrResults(database, owners, leaseClock = leaseClock, dispatcher = dispatcher)

    suspend fun run(
        page: PageId,
        job: JobId,
        prepare: Preparer,
    ): Outcome = withContext(dispatcher) {
        owners.tryOcrExecutionLane { runInLane(page, job, prepare) } ?: Outcome.BUSY
    }

    private suspend fun runInLane(
        page: PageId,
        job: JobId,
        prepare: Preparer,
    ): Outcome {
        val request = when (val decision = dispatch.resolve(page, job)) {
            RoomOcrDispatch.Decision.Obsolete -> return Outcome.OBSOLETE
            RoomOcrDispatch.Decision.Terminal -> return Outcome.TERMINAL
            RoomOcrDispatch.Decision.WaitingForOwner -> return Outcome.WAITING_FOR_OWNER
            is RoomOcrDispatch.Decision.Ready -> decision.request
        }
        return try {
            val input = request.binding
            val saved = editable.load(input.documentId, page)
            if (saved.sourceId != input.sourceId || saved.recipeRevision != input.input.recipeRevision ||
                saved.recipeHash != input.recipeSha256 || saved.recipe.geometry.source.sourcePixelHash != input.input.sourcePixelSha256
            ) return Outcome.OBSOLETE
            // Use the same process registry for the entire operation, including preparation and all resource cleanup on cancellation. Do not count a start until preparation succeeded.
            owners.run { owner ->
                PreparedSlot().use { slot ->
                    prepare.prepare(saved, input.configuration, slot)
                    currentCoroutineContext().ensureActive()
                    val prepared = slot.requireResource()
                    if (!attempts.admit(request)) return@use Outcome.BUSY
                    val lease = when (val acquired = attempts.start(request, owner, LEASE_MILLIS)) {
                        is AcquireLeaseResult.Granted -> acquired.lease
                        is AcquireLeaseResult.Busy -> return@use Outcome.BUSY
                        is AcquireLeaseResult.Rejected -> throw RepositoryFailure(acquired.error)
                    }
                    recognize(request, lease, prepared)
                }
            }
        } catch (failure: RepositoryFailure) {
            if (failure.error.code in setOf(OperationErrorCode.REVISION_CONFLICT, OperationErrorCode.SOURCE_UNAVAILABLE) &&
                stillObsolete(page, job)
            ) Outcome.OBSOLETE else throw failure
        }
    }

    private suspend fun stillObsolete(page: PageId, job: JobId): Boolean =
        try { dispatch.isObsolete(page, job) }
        catch (_: RepositoryFailure) { false } // If the read cannot establish obsolescence, report the original failure.

    private class PreparedSlot : Handoff, AutoCloseable {
        private var resource: Prepared? = null
        private var closed = false

        @Synchronized override fun retain(resource: Prepared) {
            check(!closed && this.resource == null) { "Expected one prepared OCR resource" }
            this.resource = resource
        }

        @Synchronized fun requireResource(): Prepared = checkNotNull(resource) { "The OCR preparation did not hand off a resource" }

        override fun close() {
            val retained = synchronized(this) {
                closed = true
                resource.also { resource = null }
            }
            retained?.close()
        }
    }

    private suspend fun recognize(request: RoomOcrAttempts.Request, lease: JobLease, prepared: Prepared): Outcome {
        val text = try {
            currentCoroutineContext().ensureActive()
            prepared.recognize()
        } catch (failure: RepositoryFailure) {
            val error = when (failure.error.code) {
                OperationErrorCode.RESOURCE_LIMIT -> OcrError.RESOURCE_LIMIT
                OperationErrorCode.INVALID_INPUT -> OcrError.INVALID_OUTPUT
                OperationErrorCode.PROCESSING_FAILED -> OcrError.ENGINE_FAILED
                else -> throw failure
            }
            return recordFailure(request, lease, error)
        }
        // Publication faults are not evidence of engine failure. A lost lease or failed database commit must not rewrite the error or report a result.
        try { results.publish(request, lease, text) }
        catch (_: OcrProjectionLimit) { return recordFailure(request, lease, OcrError.INVALID_OUTPUT) }
        return Outcome.PUBLISHED
    }

    private suspend fun recordFailure(request: RoomOcrAttempts.Request, lease: JobLease, error: OcrError): Outcome {
        currentCoroutineContext().ensureActive()
        if (!attempts.fail(request, lease, error)) throw RepositoryFailure(
            OperationError(OperationErrorCode.REVISION_CONFLICT, false, "ocr_execution_failure_not_owned", request.jobId.value),
        )
        return Outcome.FAILURE_RECORDED
    }

    private companion object {
        const val LEASE_MILLIS = 600_000L
    }
}
