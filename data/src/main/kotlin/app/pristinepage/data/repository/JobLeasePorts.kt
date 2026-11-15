package app.pristinepage.data.repository

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.requireOpaqueId

/** Same-DB transaction participant. Lease writes change only scheduling/checkpoint fields; the canonical job input/key remain immutable. */
interface JobLeaseQueries {
    fun job(id: String): JobRow?
    fun updateLease(row: JobRow, expectedGeneration: Long): Int
}

enum class LeaseOwnerState { ALIVE, ENDED, UNKNOWN }

/** Must be a bounded local-registry lookup, not OS/provider IO under a transaction. UNKNOWN never authorizes expired-lease takeover. */
fun interface LeaseOwnerLiveness { fun state(owner: String): LeaseOwnerState }

data class JobLease(val jobId: JobId, val owner: String, val generation: Long) {
    init { requireOpaqueId(owner); require(generation >= 1) }
}

sealed class AcquireLeaseResult {
    data class Granted(val lease: JobLease) : AcquireLeaseResult()
    data class Busy(val state: String) : AcquireLeaseResult()
    data class Rejected(val error: OperationError) : AcquireLeaseResult()
}

interface JobLeaseCommands {
    suspend fun acquireAdmitted(jobId: JobId, owner: String, durationMillis: Long): AcquireLeaseResult
    suspend fun checkpoint(lease: JobLease, checkpoint: ByteArray): Boolean
    suspend fun complete(lease: JobLease): Boolean
}
