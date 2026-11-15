package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pristinepage.data.repository.IngestPreparationQueries
import app.pristinepage.data.repository.IngestLookupQueries

@Dao abstract class IngestPreparationDao : IngestPreparationQueries, IngestLookupQueries {
    @Query("SELECT * FROM FileTransaction WHERE transactionId=:id") abstract override fun fileTransaction(id: String): FileTransactionRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertFileTransaction(row: FileTransactionRow)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertIngestJob(row: JobRow)
    @Query("SELECT * FROM Job WHERE idempotenceKey=:key") abstract override fun ingestJobByKey(key: String): JobRow?
}
