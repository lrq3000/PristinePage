package app.pristinepage.data.repository

import android.os.SystemClock
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Metadata fencing only, after separate resource admission. leaseUntil is boot-scoped monotonic elapsed milliseconds; audit fields use Unix time independently. A local liveness registry must positively confirm an old owner ended before expiry can authorize takeover; unknown/restarted owners need separate recovery proof, never comparison of a previous boot's deadline or a timeout-derived ENDED. */
class RoomJobLeases @JvmOverloads constructor(private val metadata: MetadataAccess, private val jobs: JobLeaseQueries, private val liveness: LeaseOwnerLiveness, private val auditClock: () -> Long = System::currentTimeMillis, private val leaseClock: () -> Long = SystemClock::elapsedRealtime, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : JobLeaseCommands {
    constructor(database: PristineDatabase, liveness: LeaseOwnerLiveness) : this(RoomMetadataAccess(database), database.jobLeases(), liveness)
    private val completion = JobLeaseTransactions(metadata, jobs, auditClock, leaseClock)

    override suspend fun acquireAdmitted(jobId: JobId, owner: String, durationMillis: Long): AcquireLeaseResult =
        acquire(jobId, owner, durationMillis)

    private suspend fun acquire(
        jobId: JobId,
        owner: String,
        durationMillis: Long,
        guardedRenderRead: ((active: () -> Unit) -> JobRow)? = null,
    ): AcquireLeaseResult = withContext(dispatcher) {
        try {
            try { requireOpaqueId(owner); require(durationMillis in 1..86_400_000L) }
            catch (_: IllegalArgumentException) { reject(OperationErrorCode.INVALID_INPUT, "invalid_job_lease", jobId.value) }
            coroutineContext.ensureActive()
            metadata.transaction {
                val job = if (guardedRenderRead == null) {
                    jobs.job(jobId.value) ?: reject(OperationErrorCode.INVALID_INPUT, "job_unavailable", jobId.value)
                } else guardedRenderRead { coroutineContext.ensureActive() }
                if (job.leaseGeneration < 0 || job.attempt < 0) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_job_lease", jobId.value)
                val now = leaseClock()
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
                // Admission deliberately clears the previous owner. Require a currently scoped requester even on that owner-free path, or an already-ENDED/unknown identity could gain a new generation and invalidate the terminal evidence recovery relies on.
                if (liveness.state(owner) != LeaseOwnerState.ALIVE) reject(OperationErrorCode.INVALID_INPUT, "job_owner_not_alive", jobId.value)
                val generation: Long; val attempt: Long; val until: Long
                try { generation = Math.addExact(job.leaseGeneration, 1L); attempt = Math.addExact(job.attempt, 1L); until = Math.addExact(now, durationMillis) }
                catch (_: ArithmeticException) { reject(OperationErrorCode.RESOURCE_LIMIT, "job_lease_limit", jobId.value) }
                coroutineContext.ensureActive()
                val running = job.copy(state = "RUNNING", leaseOwner = owner, leaseUntil = until, leaseGeneration = generation, attempt = attempt, errorCode = null, updatedAt = auditClock())
                // Fence known cancellation through the last clock sample for the guarded route, without changing legacy acquisition timing.
                if (guardedRenderRead != null) coroutineContext.ensureActive()
                if (jobs.updateLease(running, job.leaseGeneration) != 1) return@transaction AcquireLeaseResult.Busy(job.state)
                AcquireLeaseResult.Granted(JobLease(jobId, owner, generation))
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { AcquireLeaseResult.Rejected(failure.error) }
        catch (_: Exception) { AcquireLeaseResult.Rejected(OperationError(OperationErrorCode.STORAGE_FAILED, true, "job_lease_write_failed", jobId.value)) }
    }

    /** The ordinary render entry rechecks the first admission's exact JOB binding inside the existing same-database lease writer. The receipt is not a live JOB pin. */
    internal class FrozenRenderAcquisition(
        metadata: MetadataAccess,
        private val jobs: JobLeaseQueries,
        liveness: LeaseOwnerLiveness,
        jobBounds: (String, Int) -> Boolean?,
        jobIdentity: (String) -> FrozenRenderJobIdentityRow?,
        pinSourceIds: (String) -> List<String>,
        sourceBounds: (String) -> Boolean?,
        private val leaseBounds: (String) -> Boolean?,
        auditClock: () -> Long = System::currentTimeMillis,
        leaseClock: () -> Long = SystemClock::elapsedRealtime,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        constructor(database: PristineDatabase, liveness: LeaseOwnerLiveness) : this(
            RoomMetadataAccess(database), database.jobLeases(), liveness,
            database.exportSnapshots()::renderJobReadIsBounded,
            database.exportSnapshots()::renderJobIdentity,
            database.exportSnapshots()::boundedRenderJobPinSourceIds,
            database.catalog()::sourceReadIsBounded,
            database.exportSnapshots()::renderJobLeaseReadIsBounded,
        )

        private val catalog = metadata.queries
        private val projection = RoomFrozenRenderReads.Projection(catalog, jobBounds, jobIdentity, pinSourceIds, sourceBounds)
        private val leases = RoomJobLeases(metadata, jobs, liveness, auditClock, leaseClock, dispatcher)

        sealed interface ProducerResult {
            data class Busy(val state: String) : ProducerResult
            data class Rejected(val error: OperationError) : ProducerResult
        }

        /** The original admission and the exact delivered lease travel together; a separate read or a lookalike lease cannot reconstruct this grant. */
        class Acquired private constructor(
            val admitted: RoomFrozenRenderAdmission.Admitted,
            override val lease: JobLease,
        ) : ProducerResult, RenderAttemptAuthority {
            override val proof get() = admitted.proof

            fun belongsTo(catalog: CatalogQueries): Boolean = admitted.issuedBy(catalog)

            companion object {
                /** Perform the guarded acquisition; there is no upgrade of a separately returned legacy lease. */
                suspend fun acquire(
                    acquisition: FrozenRenderAcquisition, admitted: RoomFrozenRenderAdmission.Admitted, owner: String, durationMillis: Long,
                    firstAttemptOnly: Boolean = false,
                ): ProducerResult = when (val result = acquisition.acquire(admitted, owner, durationMillis, firstAttemptOnly)) {
                    is AcquireLeaseResult.Granted -> Acquired(admitted, result.lease)
                    is AcquireLeaseResult.Busy -> ProducerResult.Busy(result.state)
                    is AcquireLeaseResult.Rejected -> ProducerResult.Rejected(result.error)
                }
            }
        }

        suspend fun acquireForProducer(admitted: RoomFrozenRenderAdmission.Admitted, owner: String, durationMillis: Long): ProducerResult =
            Acquired.acquire(this, admitted, owner, durationMillis)

        /** A first admission receipt does not authorize taking over or readmitting a competing attempt. */
        suspend fun acquireForFreshProducer(admitted: RoomFrozenRenderAdmission.Admitted, owner: String, durationMillis: Long): ProducerResult =
            Acquired.acquire(this, admitted, owner, durationMillis, firstAttemptOnly = true)

        suspend fun acquire(admitted: RoomFrozenRenderAdmission.Admitted, owner: String, durationMillis: Long): AcquireLeaseResult =
            acquire(admitted, owner, durationMillis, firstAttemptOnly = false)

        private suspend fun acquire(
            admitted: RoomFrozenRenderAdmission.Admitted, owner: String, durationMillis: Long, firstAttemptOnly: Boolean,
        ): AcquireLeaseResult {
            val proof = admitted.proof
            if (!admitted.issuedBy(catalog)) {
                currentCoroutineContext().ensureActive()
                return AcquireLeaseResult.Rejected(OperationError(
                    OperationErrorCode.INTEGRITY_FAILED, false, "frozen_render_admission_store_mismatch", proof.jobId.value,
                ))
            }
            return leases.acquire(proof.jobId, owner, durationMillis) { active ->
                val jobId = proof.jobId
                // Revalidate the exact narrow identity and live binding before admitting the remaining fields for SELECT *.
                projection.job(jobId, proof.inputBytes, active).requireUnchangedForLease(proof)
                if (leaseBounds(jobId.value) != true) {
                    leases.reject(OperationErrorCode.INTEGRITY_FAILED, "frozen_render_job_metadata_invalid", jobId.value)
                }
                active()
                val row = jobs.job(jobId.value)
                if (row == null || !proof.matches(row)) {
                    leases.reject(OperationErrorCode.INTEGRITY_FAILED, "frozen_render_job_invalid", jobId.value)
                }
                // Check the very row the lease writer will CAS, before it can advance the counters or replace a prior owner.
                if (firstAttemptOnly && (row.state != "ADMITTED" || row.leaseGeneration != 0L || row.attempt != 0L ||
                        row.leaseOwner != null || row.leaseUntil != null || row.checkpoint != null || row.errorCode != null)) {
                    leases.reject(OperationErrorCode.REVISION_CONFLICT, "frozen_render_first_attempt_unavailable", jobId.value)
                }
                row
            }
        }
    }

    override suspend fun checkpoint(lease: JobLease, checkpoint: ByteArray): Boolean = withContext(dispatcher) {
        safeWrite(lease.jobId) {
            if (checkpoint.isEmpty() || checkpoint.size > 524288) reject(OperationErrorCode.INVALID_INPUT, "invalid_job_checkpoint", lease.jobId.value)
            val owned = checkpoint.copyOf()
            coroutineContext.ensureActive()
            metadata.transaction {
                val job = JobLeaseFence.current(jobs, lease, leaseClock()) ?: return@transaction false
                coroutineContext.ensureActive()
                jobs.updateLease(job.copy(state = "CHECKPOINTED", checkpoint = owned, updatedAt = auditClock()), lease.generation) == 1
            }
        }
    }

    /** Standalone completion publishes no domain data. Source/page/export consumers use JobLeaseTransactions directly to compose their bounded metadata writes with this exact fence in one transaction, after verifying files outside the writer lock. */
    override suspend fun complete(lease: JobLease): Boolean = withContext(dispatcher) {
        coroutineContext.ensureActive()
        completion.complete(lease) { coroutineContext.ensureActive(); Unit } != null
    }

    private fun reject(code: OperationErrorCode, key: String, id: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, id))
    private inline fun <T> safeWrite(id: JobId, block: () -> T): T = try { block() }
    catch (canceled: CancellationException) { throw canceled }
    catch (failure: RepositoryFailure) { throw failure }
    catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "job_lease_write_failed", id.value)) }
}
