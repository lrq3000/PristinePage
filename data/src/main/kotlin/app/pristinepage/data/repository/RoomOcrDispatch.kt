package app.pristinepage.data.repository

import android.os.SystemClock
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Resolve a scheduler hint against this store. Ready carries the original persisted input, not a grant to start or read source bytes. */
internal class RoomOcrDispatch(
    database: PristineDatabase,
    private val liveness: LeaseOwnerLiveness,
    private val proof: OperationOwnerProof,
    leaseClock: () -> Long = SystemClock::elapsedRealtime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val metadata = RoomMetadataAccess(database)
    private val current = RoomOcrCurrentWork(database)
    private val attempts = RoomOcrAttempts(database, liveness, leaseClock = leaseClock, dispatcher = dispatcher)

    sealed interface Decision {
        data object Obsolete : Decision
        data object Terminal : Decision
        data object WaitingForOwner : Decision
        data class Ready(val request: RoomOcrAttempts.Request) : Decision
    }

    suspend fun resolve(page: PageId, job: JobId): Decision = withContext(dispatcher) {
        val observed = read(page, job) ?: return@withContext Decision.Obsolete
        val lease = observed.lease ?: return@withContext observed.decision()
        // A foreign native lock probe can perform I/O. Only the exact attempt's bounded liveness cache is consulted by the write transaction.
        val ended = when (liveness.state(lease.owner)) {
            LeaseOwnerState.ENDED -> true
            LeaseOwnerState.ALIVE -> false
            LeaseOwnerState.UNKNOWN -> proof.refresh(lease.owner) == LeaseOwnerState.ENDED
        }
        coroutineContext.ensureActive()
        val refused = if (ended) {
            try {
                !attempts.recoverEnded(observed.request, lease)
            } catch (failure: RepositoryFailure) {
                // A concurrent rerun or a late fence can invalidate the observation while the probe is outside Room. Re-read; never apply it to a successor.
                if (failure.error.code != OperationErrorCode.REVISION_CONFLICT) throw failure
                true
            }
        } else false
        val fresh = read(page, job) ?: return@withContext Decision.Obsolete
        if (refused && fresh.lease == lease && liveness.state(lease.owner) == LeaseOwnerState.ENDED) {
            // The same positively ended attempt survived a refused write. Waiting for its owner would hide a storage problem that liveness cannot resolve.
            storageFailure(job, "ocr_dispatch_recovery_refused")
        }
        fresh.decision()
    }

    private data class Observation(val request: RoomOcrAttempts.Request, val terminal: Boolean, val lease: JobLease? = null) {
        fun decision(): Decision = when {
            terminal -> Decision.Terminal
            lease != null -> Decision.WaitingForOwner
            else -> Decision.Ready(request)
        }
    }

    /** Report supersession only. Do not refresh a process owner or recover a lease while classifying a failed execution. */
    suspend fun isObsolete(page: PageId, job: JobId): Boolean = withContext(dispatcher) {
        readSnapshot(job) { matchingSavedWork(page, job) == null }
    }

    private fun matchingSavedWork(page: PageId, job: JobId): RoomOcrCurrentWork.Current? {
        val saved = current.readStoredInTransaction(page) ?: return null
        return saved.takeIf { it.job.jobId == job.value && current.isSavedInputInTransaction(it.intent) }
    }

    private suspend fun read(page: PageId, job: JobId): Observation? = readSnapshot(job) {
        val saved = matchingSavedWork(page, job) ?: return@readSnapshot null
        val request = RoomOcrAttempts.Request(job, saved.intent)
        if (!RoomOcrAttempts.hasConsistentStoredState(saved.job)) throw RepositoryFailure(
            OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_dispatch_state_invalid", job.value),
        )
        when (saved.job.state) {
            "PENDING", "ADMITTED", "RUNNING" -> {
                val pinned = attempts.readPinnedInTransaction(request).job
                val lease = if (pinned.state == "RUNNING") JobLease(job, checkNotNull(pinned.leaseOwner), pinned.leaseGeneration) else null
                Observation(request, terminal = false, lease = lease)
            }
            else -> Observation(request, terminal = true)
        }
    }

    private suspend fun <T> readSnapshot(job: JobId, lookup: () -> T): T {
        val active = currentCoroutineContext()
        val observation = try {
            metadata.transaction {
                active.ensureActive()
                lookup()
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            throw failure
        } catch (_: Exception) {
            active.ensureActive()
            storageFailure(job, "ocr_dispatch_read_failed")
        }
        // This also fences early absent, mismatched, and hidden lookups, not just deliveries of a non-null observation.
        active.ensureActive()
        return observation
    }

    private fun storageFailure(job: JobId, key: String): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, key, job.value))
}
