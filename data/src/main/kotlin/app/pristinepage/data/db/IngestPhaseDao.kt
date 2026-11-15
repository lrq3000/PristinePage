package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Query
import app.pristinepage.data.repository.IngestPhaseQueries

/** Only execution fields change. Identity, paths and canonical input remain immutable in the surrounding same-database fenced transaction. */
@Dao abstract class IngestPhaseDao : IngestPhaseQueries {
    @Query("UPDATE FileTransaction SET state=:state,sealSha256=:seal,expectedByteSha256=:hash,expectedByteCount=:count,errorCode=:error,updatedAt=:updated WHERE transactionId=:id AND state=:expectedState")
    abstract fun updatePhase(id: String, state: String, seal: String?, hash: String?, count: Long?, error: String?, updated: Long, expectedState: String): Int
    override fun updateIngestPhase(row: FileTransactionRow, expectedState: String): Int = updatePhase(row.transactionId, row.state, row.sealSha256, row.expectedByteSha256, row.expectedByteCount, row.errorCode, row.updatedAt, expectedState)
}
