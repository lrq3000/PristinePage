package app.pristinepage.data.repository

import android.os.SystemClock
import app.pristinepage.core.ocr.OcrError
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Metadata only. A worker obtains any required image/engine capacity before admission and uses a fresh structured operation owner for each start. */
internal class RoomOcrAttempts(
    database: PristineDatabase,
    private val liveness: LeaseOwnerLiveness,
    private val auditClock: () -> Long = System::currentTimeMillis,
    private val leaseClock: () -> Long = SystemClock::elapsedRealtime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val metadata = RoomMetadataAccess(database)
    private val jobs = database.jobLeases()
    private val pins = database.ocrPageWork()
    private val current = RoomOcrCurrentWork(database)
    private val leases = RoomJobLeases(metadata, jobs, liveness, auditClock, leaseClock, dispatcher)

    data class Request(val jobId: JobId, val binding: OcrJobInput) {
        val page = PageId(binding.input.pageId)
    }

    suspend fun admit(request: Request): Boolean = write(request) { before, active ->
        val job = before.job
        when (job.state) {
            "ADMITTED" -> true
            "PENDING" -> {
                update(request, before, job.copy(state = "ADMITTED", updatedAt = auditClock()), active)
                true
            }
            else -> false
        }
    }

    suspend fun start(request: Request, owner: String, durationMillis: Long): AcquireLeaseResult {
        lateinit var before: RoomOcrCurrentWork.Current
        return leases.acquireGuarded(
            request.jobId, owner, durationMillis,
            read = { active ->
                active()
                before = read(request)
                val job = before.job
                // Never enter the generic RUNNING takeover path. OCR first records the abandoned start and its error, even if the old deadline is expired.
                if (job.state != "ADMITTED" || job.attempt >= MAX_STARTS) {
                    refuse(request, OperationErrorCode.REVISION_CONFLICT, "ocr_job_not_admitted")
                }
                job
            },
            beforeWrite = { },
            afterWrite = { expected, active ->
                active()
                verifyWritten(request, before, expected)
            },
        )
    }

    suspend fun fail(request: Request, lease: JobLease, error: OcrError): Boolean {
        if (error == OcrError.INTERRUPTED) refuse(request, OperationErrorCode.INVALID_INPUT, "ocr_interruption_requires_end_proof")
        return finish(request, lease, error) { original ->
            liveness.state(lease.owner) == LeaseOwnerState.ALIVE && JobLeaseFence.current(original, lease, leaseClock()) != null
        }
    }

    /** The caller can refresh FileOperationOwners outside this transaction. Only its bounded, positive cached/local proof is consulted here. */
    suspend fun recoverEnded(request: Request, lease: JobLease): Boolean =
        finish(request, lease, OcrError.INTERRUPTED) { liveness.state(lease.owner) == LeaseOwnerState.ENDED }

    private suspend fun finish(
        request: Request,
        lease: JobLease,
        error: OcrError,
        mayFinish: (JobRow) -> Boolean,
    ): Boolean = write(request) { before, active ->
        val job = before.job
        if (lease.jobId != request.jobId || job.state != "RUNNING" || job.leaseOwner != lease.owner ||
            job.leaseGeneration != lease.generation || !mayFinish(job)
        ) return@write false

        val next = job.copy(state = if (job.attempt == MAX_STARTS) "FAILED" else "ADMITTED",
            leaseOwner = null, leaseUntil = null, errorCode = error.name, updatedAt = auditClock())
        update(request, before, next, active)
        // Match the original ownership at the last check: the uncommitted terminal/readmitted row no longer carries a lease.
        if (!mayFinish(job)) refuse(request, OperationErrorCode.REVISION_CONFLICT, "ocr_job_lease_lost")
        true
    }

    private fun read(request: Request): RoomOcrCurrentWork.Current {
        val saved = current.readInTransaction(request.page)
        if (saved == null || saved.intent != request.binding || saved.job.jobId != request.jobId.value) {
            refuse(request, OperationErrorCode.REVISION_CONFLICT, "ocr_job_no_longer_current")
        }
        val job = saved.job
        if (!pins.hasExactJobPin(job.jobId, saved.intent.sourceId.value)) invalid(request)
        val noLease = job.leaseOwner == null && job.leaseUntil == null
        val hasError = job.errorCode != null && OcrError.entries.any { it.name == job.errorCode }
        val valid = when (job.state) {
            "PENDING" -> noLease && job.attempt == 0L && job.errorCode == null
            "ADMITTED" -> noLease && job.attempt < MAX_STARTS && (if (job.attempt == 0L) job.errorCode == null else hasError)
            "RUNNING" -> job.leaseOwner != null && job.leaseUntil != null && job.attempt > 0L && job.errorCode == null
            "FAILED" -> noLease && job.attempt == MAX_STARTS && hasError
            "SUCCEEDED" -> noLease && job.attempt > 0L && job.errorCode == null
            "CANCELED" -> noLease && (job.errorCode == null || hasError)
            else -> false
        }
        if (!valid || job.leaseGeneration != job.attempt) invalid(request)
        return saved
    }

    private fun update(request: Request, before: RoomOcrCurrentWork.Current, next: JobRow, active: () -> Unit) {
        active()
        if (jobs.updateLease(next, before.job.leaseGeneration) != 1) {
            refuse(request, OperationErrorCode.REVISION_CONFLICT, "ocr_job_lease_lost")
        }
        verifyWritten(request, before, next)
    }

    private fun verifyWritten(request: Request, before: RoomOcrCurrentWork.Current, expected: JobRow): JobRow {
        val after = read(request)
        if (after.resultJobId != before.resultJobId || after.projectionVersion != before.projectionVersion) invalid(request)
        val actual = after.job
        // Room copies BLOBs on read, so compare their contents, not their array identities. OCR never stores a checkpoint.
        if (!actual.input.contentEquals(expected.input) || actual.copy(input = expected.input) != expected) invalid(request)
        return actual
    }

    private suspend fun write(request: Request, change: (RoomOcrCurrentWork.Current, () -> Unit) -> Boolean): Boolean = withContext(dispatcher) {
        val active = { coroutineContext.ensureActive() }
        try {
            active()
            metadata.transaction {
                val result = change(read(request), active)
                active()
                result
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { refuse(request, OperationErrorCode.STORAGE_FAILED, "ocr_attempt_write_failed", retryable = true) }
    }

    private fun invalid(request: Request): Nothing = refuse(request, OperationErrorCode.INTEGRITY_FAILED, "ocr_attempt_binding_invalid")

    private fun refuse(request: Request, code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
        throw RepositoryFailure(OperationError(code, retryable, key, request.jobId.value))

    private companion object {
        const val MAX_STARTS = 3L
    }
}
