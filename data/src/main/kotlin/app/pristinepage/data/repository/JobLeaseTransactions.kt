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

    override fun <T : Any> complete(lease: JobLease, commitMetadata: () -> T): T? {
        try {
            return metadata.transaction {
                if (JobLeaseFence.current(jobs, lease, leaseClock()) == null) return@transaction null
                val result = commitMetadata()
                val now = leaseClock()
                // The bounded writer may still fail, expire or invalidate its own destination. Do not commit its domain changes unless the same attempt remains publishable at the final fence.
                val job = JobLeaseFence.current(jobs, lease, now) ?: lostLease(lease)
                if (jobs.updateLease(job.copy(state = "SUCCEEDED", leaseOwner = null, leaseUntil = null, updatedAt = auditClock()), lease.generation) != 1) lostLease(lease)
                result
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "job_lease_write_failed", lease.jobId.value)) }
    }

    private fun lostLease(lease: JobLease): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "job_lease_lost", lease.jobId.value))
}

/** One shared predicate for checkpoint, standalone completion and joint domain completion. The caller supplies the surrounding short metadata transaction and the same boot-scoped monotonic lease clock used at acquisition, never an audit timestamp. */
internal object JobLeaseFence {
    fun current(jobs: JobLeaseQueries, lease: JobLease, now: Long): JobRow? {
        val job = jobs.job(lease.jobId.value) ?: return null
        return job.takeIf { it.leaseOwner == lease.owner && it.leaseGeneration == lease.generation && it.state in setOf("RUNNING", "CHECKPOINTED") && (it.leaseUntil ?: Long.MIN_VALUE) > now }
    }
}
