package app.pristinepage.data.repository

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Metadata fencing only, after separate resource admission. A local liveness registry must positively confirm an old owner ended before expiry can authorize takeover; this component never interprets a timeout as proof that native work stopped. */
class RoomJobLeases @JvmOverloads constructor(private val metadata: MetadataAccess, private val jobs: JobLeaseQueries, private val liveness: LeaseOwnerLiveness, private val clock: () -> Long = System::currentTimeMillis, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : JobLeaseCommands {
    constructor(database: PristineDatabase, liveness: LeaseOwnerLiveness) : this(RoomMetadataAccess(database), database.jobLeases(), liveness)

    override suspend fun acquireAdmitted(jobId: JobId, owner: String, durationMillis: Long): AcquireLeaseResult = withContext(dispatcher) {
        try {
            try { requireOpaqueId(owner); require(durationMillis in 1..86_400_000L) }
            catch (_: IllegalArgumentException) { reject(OperationErrorCode.INVALID_INPUT, "invalid_job_lease", jobId.value) }
            coroutineContext.ensureActive()
            metadata.transaction {
                val job = jobs.job(jobId.value) ?: reject(OperationErrorCode.INVALID_INPUT, "job_unavailable", jobId.value)
                if (job.leaseGeneration < 0 || job.attempt < 0) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_job_lease", jobId.value)
                val now = clock()
                when (job.state) {
                    "ADMITTED" -> if (job.leaseOwner != null || job.leaseUntil != null) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_job_lease", jobId.value)
                    "RUNNING", "CHECKPOINTED" -> {
                        val oldOwner = job.leaseOwner ?: reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_job_lease", jobId.value)
                        val until = job.leaseUntil ?: reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_job_lease", jobId.value)
                        // A registry's ENDED identity is terminal. A delayed duplicate callback cannot reuse it for new work, even after expiry; only a distinct runner may take over a confirmed-ended attempt.
                        if (oldOwner == owner || now < until || liveness.state(oldOwner) != LeaseOwnerState.ENDED) return@transaction AcquireLeaseResult.Busy(job.state)
                    }
                    else -> reject(OperationErrorCode.INVALID_INPUT, "job_not_admitted", jobId.value)
                }
                val generation: Long; val attempt: Long; val until: Long
                try { generation = Math.addExact(job.leaseGeneration, 1L); attempt = Math.addExact(job.attempt, 1L); until = Math.addExact(now, durationMillis) }
                catch (_: ArithmeticException) { reject(OperationErrorCode.RESOURCE_LIMIT, "job_lease_limit", jobId.value) }
                coroutineContext.ensureActive()
                val running = job.copy(state = "RUNNING", leaseOwner = owner, leaseUntil = until, leaseGeneration = generation, attempt = attempt, errorCode = null, updatedAt = now)
                if (jobs.updateLease(running, job.leaseGeneration) != 1) return@transaction AcquireLeaseResult.Busy(job.state)
                AcquireLeaseResult.Granted(JobLease(jobId, owner, generation))
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { AcquireLeaseResult.Rejected(failure.error) }
        catch (_: Exception) { AcquireLeaseResult.Rejected(OperationError(OperationErrorCode.STORAGE_FAILED, true, "job_lease_write_failed", jobId.value)) }
    }

    override suspend fun checkpoint(lease: JobLease, checkpoint: ByteArray): Boolean = withContext(dispatcher) {
        safeWrite(lease.jobId) {
            if (checkpoint.isEmpty() || checkpoint.size > 524288) reject(OperationErrorCode.INVALID_INPUT, "invalid_job_checkpoint", lease.jobId.value)
            val owned = checkpoint.copyOf()
            coroutineContext.ensureActive()
            metadata.transaction {
                val job = current(lease) ?: return@transaction false
                coroutineContext.ensureActive()
                jobs.updateLease(job.copy(state = "CHECKPOINTED", checkpoint = owned, updatedAt = clock()), lease.generation) == 1
            }
        }
    }

    /** The consuming file/metadata commit must verify its own durable inputs and share the writer transaction with this transition. A successful lease transition alone is not a ready raster, source page or export. */
    override suspend fun complete(lease: JobLease): Boolean = withContext(dispatcher) {
        safeWrite(lease.jobId) {
            coroutineContext.ensureActive()
            metadata.transaction {
                val job = current(lease) ?: return@transaction false
                coroutineContext.ensureActive()
                jobs.updateLease(job.copy(state = "SUCCEEDED", leaseOwner = null, leaseUntil = null, updatedAt = clock()), lease.generation) == 1
            }
        }
    }

    private fun current(lease: JobLease): JobRow? {
        val job = jobs.job(lease.jobId.value) ?: return null
        return job.takeIf { it.leaseOwner == lease.owner && it.leaseGeneration == lease.generation && it.state in setOf("RUNNING", "CHECKPOINTED") && (it.leaseUntil ?: Long.MIN_VALUE) > clock() }
    }
    private fun reject(code: OperationErrorCode, key: String, id: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, id))
    private inline fun <T> safeWrite(id: JobId, block: () -> T): T = try { block() }
    catch (canceled: CancellationException) { throw canceled }
    catch (failure: RepositoryFailure) { throw failure }
    catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "job_lease_write_failed", id.value)) }
}
