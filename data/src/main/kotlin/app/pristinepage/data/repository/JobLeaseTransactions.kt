package app.pristinepage.data.repository

import android.os.SystemClock
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.CancellationException

/** Composes only metadata, never file/provider/native IO. Construct with participants from one database; the native constructor guarantees that binding. A failed final fence throws rather than returning null after the action, so Room rolls back its complete metadata effect. */
class JobLeaseTransactions @JvmOverloads constructor(private val metadata: MetadataAccess, private val jobs: JobLeaseQueries, private val auditClock: () -> Long = System::currentTimeMillis, private val leaseClock: () -> Long = SystemClock::elapsedRealtime) : FencedJobCompletion {
    constructor(database: PristineDatabase) : this(RoomMetadataAccess(database), database.jobLeases())

    override fun <T : Any> complete(lease: JobLease, commitMetadata: () -> T): T? =
        completeWithReadback(lease, commitMetadata, null)

    /** Read back an additional domain invariant after the final job UPDATE but before COMMIT; a refused read rolls back both. */
    internal fun <T : Any> completeWithReadback(lease: JobLease, commitMetadata: () -> T, verifyMetadata: (() -> Unit)?): T? {
        try {
            return metadata.transaction {
                if (JobLeaseFence.current(jobs, lease, leaseClock()) == null) return@transaction null
                val result = commitMetadata()
                val now = leaseClock()
                // The bounded writer may still fail, expire or invalidate its own destination. Do not commit its domain changes unless the same attempt remains publishable at the final fence.
                val job = JobLeaseFence.current(jobs, lease, now) ?: lostLease(lease)
                val succeeded = job.copy(state = "SUCCEEDED", leaseOwner = null, leaseUntil = null, updatedAt = auditClock())
                if (jobs.updateLease(succeeded, lease.generation) != 1) lostLease(lease)
                if (verifyMetadata != null) {
                    verifyMetadata()
                    if (!sameJob(succeeded, jobs.job(lease.jobId.value) ?: lostLease(lease))) lostLease(lease)
                    // The success update cleared the live deadline; observe the pre-update lease after the readback.
                    if (JobLeaseFence.current(job, lease, leaseClock()) == null) lostLease(lease)
                }
                result
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "job_lease_write_failed", lease.jobId.value)) }
    }

    /** Specialization for a bounded, exact reader. The last check uses the original live row because the still-uncommitted success write consumes its owner/deadline. No old caller changes ordering or read semantics. */
    internal fun <T : Any> completeGuarded(
        lease: JobLease,
        readAdmitted: () -> JobRow,
        live: () -> Boolean,
        active: () -> Unit,
        commitMetadata: () -> T,
        verifyMetadata: (T) -> Unit,
    ): T {
        fun fence(row: JobRow) {
            if (!live() || JobLeaseFence.current(row, lease, leaseClock()) == null) lostLease(lease)
            active()
        }
        try {
            return metadata.transaction {
                active()
                val original = readAdmitted()
                fence(original)
                val result = commitMetadata()
                val current = readAdmitted()
                fence(current)
                if (!sameJob(original, current)) lostLease(lease)
                val succeeded = current.copy(state = "SUCCEEDED", leaseOwner = null, leaseUntil = null, updatedAt = auditClock())
                active()
                if (jobs.updateLease(succeeded, lease.generation) != 1) lostLease(lease)
                verifyMetadata(result)
                if (!sameJob(succeeded, readAdmitted())) lostLease(lease)
                // After all SQL, including final bounded row and pin verification. SQLite COMMIT still follows this local observation.
                fence(original)
                result
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "job_lease_write_failed", lease.jobId.value)) }
    }

    private fun sameJob(expected: JobRow, actual: JobRow): Boolean =
        actual.input.contentEquals(expected.input) && actual.checkpoint.contentEquals(expected.checkpoint) &&
            actual.copy(input = expected.input, checkpoint = expected.checkpoint) == expected

    private fun lostLease(lease: JobLease): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "job_lease_lost", lease.jobId.value))
}

/** One shared predicate for checkpoint, standalone completion and joint domain completion. The caller supplies the surrounding short metadata transaction and the same boot-scoped monotonic lease clock used at acquisition, never an audit timestamp. */
internal object JobLeaseFence {
    fun current(jobs: JobLeaseQueries, lease: JobLease, now: Long): JobRow? {
        val job = jobs.job(lease.jobId.value) ?: return null
        return current(job, lease, now)
    }

    fun current(job: JobRow, lease: JobLease, now: Long): JobRow? =
        job.takeIf { it.leaseOwner == lease.owner && it.leaseGeneration == lease.generation && it.state in setOf("RUNNING", "CHECKPOINTED") && (it.leaseUntil ?: Long.MIN_VALUE) > now }
}
