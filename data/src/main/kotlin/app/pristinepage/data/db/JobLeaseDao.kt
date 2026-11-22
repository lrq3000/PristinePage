package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Query
import app.pristinepage.data.repository.JobLeaseQueries

/** Restrict writes to lease/progress fields, retaining every canonical job identity byte. The surrounding Room transaction validates owner/state and the generation predicate fences a late runner. */
@Dao abstract class JobLeaseDao : JobLeaseQueries {
    @Query("SELECT * FROM Job WHERE jobId=:id") abstract override fun job(id: String): JobRow?

    // Project only one scalar before the full row; CASE distinguishes corruption from absence. Exact TEXT identity comparisons are binary, and byte lengths on variable TEXT reject hidden NUL suffixes before Room reads them.
    @Query("""SELECT CASE WHEN (typeof(jobId)='text' AND length(CAST(jobId AS BLOB))=36)
        AND (typeof(kind)='text' AND kind='PAGE_OCR')
        AND (typeof(idempotenceKey)='text' AND idempotenceKey=:expectedKey)
        AND (typeof(canonicalInputSha256)='text' AND canonicalInputSha256=:expectedDigest)
        AND (:expectedInputBytes BETWEEN 1 AND 3532 AND typeof(input)='blob' AND length(input)=:expectedInputBytes)
        AND (snapshotId IS NULL AND pageOrdinal IS NULL)
        AND (typeof(state)='text' AND state IN ('PENDING','ADMITTED','RUNNING','SUCCEEDED','FAILED','CANCELED'))
        AND (typeof(attempt)='integer' AND attempt BETWEEN 0 AND 3)
        AND (leaseOwner IS NULL OR (typeof(leaseOwner)='text' AND length(CAST(leaseOwner AS BLOB))=36 AND length(leaseOwner)=36))
        AND (leaseUntil IS NULL OR (typeof(leaseUntil)='integer' AND leaseUntil>=0))
        AND (typeof(leaseGeneration)='integer' AND leaseGeneration>=0)
        AND (checkpoint IS NULL)
        AND (errorCode IS NULL OR (typeof(errorCode)='text' AND length(CAST(errorCode AS BLOB)) BETWEEN 1 AND 64
            AND length(errorCode)=length(CAST(errorCode AS BLOB)) AND errorCode GLOB '[A-Z]*' AND errorCode NOT GLOB '*[^A-Z0-9_]*'))
        AND (typeof(createdAt)='integer' AND typeof(updatedAt)='integer')
        THEN 1 ELSE 0 END FROM Job WHERE jobId=:id""")
    internal abstract fun ocrJobReadIsBounded(id: String, expectedKey: String, expectedDigest: String, expectedInputBytes: Int): Boolean?

    @Query("UPDATE Job SET state=:state,attempt=:attempt,leaseOwner=:owner,leaseUntil=:until,leaseGeneration=:generation,checkpoint=:checkpoint,errorCode=:error,updatedAt=:updated WHERE jobId=:id AND leaseGeneration=:expectedGeneration")
    abstract fun updateLeaseState(id: String, state: String, attempt: Long, owner: String?, until: Long?, generation: Long, checkpoint: ByteArray?, error: String?, updated: Long, expectedGeneration: Long): Int

    override fun updateLease(row: JobRow, expectedGeneration: Long): Int = updateLeaseState(row.jobId, row.state, row.attempt, row.leaseOwner, row.leaseUntil, row.leaseGeneration, row.checkpoint, row.errorCode, row.updatedAt, expectedGeneration)
}
