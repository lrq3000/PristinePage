package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pristinepage.data.repository.IngestCompletionQueries

@Dao abstract class IngestCompletionDao : IngestCompletionQueries {
    @Query("SELECT * FROM SourceBlob WHERE sourceBlobId=:id") abstract override fun sourceBlob(id: String): SourceBlobRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertSourceBlob(row: SourceBlobRow)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertSource(row: SourceRow)
    @Query("UPDATE FileTransaction SET state=:state,payload=:payload,errorCode=:error,updatedAt=:updated WHERE transactionId=:id AND state=:expectedState")
    abstract fun updateCompletion(id: String, state: String, payload: ByteArray, error: String?, updated: Long, expectedState: String): Int
    override fun updateFileTransaction(row: FileTransactionRow, expectedState: String): Int = updateCompletion(row.transactionId, row.state, row.payload, row.errorCode, row.updatedAt, expectedState)
}
