package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Query
import app.pristinepage.data.repository.JobLeaseQueries

/** Restrict writes to lease/progress fields, retaining every canonical job identity byte. The surrounding Room transaction validates owner/state and the generation predicate fences a late runner. */
@Dao abstract class JobLeaseDao : JobLeaseQueries {
    @Query("SELECT * FROM Job WHERE jobId=:id") abstract override fun job(id: String): JobRow?

    @Query("UPDATE Job SET state=:state,attempt=:attempt,leaseOwner=:owner,leaseUntil=:until,leaseGeneration=:generation,checkpoint=:checkpoint,errorCode=:error,updatedAt=:updated WHERE jobId=:id AND leaseGeneration=:expectedGeneration")
    abstract fun updateLeaseState(id: String, state: String, attempt: Long, owner: String?, until: Long?, generation: Long, checkpoint: ByteArray?, error: String?, updated: Long, expectedGeneration: Long): Int

    override fun updateLease(row: JobRow, expectedGeneration: Long): Int = updateLeaseState(row.jobId, row.state, row.attempt, row.leaseOwner, row.leaseUntil, row.leaseGeneration, row.checkpoint, row.errorCode, row.updatedAt, expectedGeneration)
}
