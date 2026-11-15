package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pristinepage.data.repository.IngestPreparationQueries

@Dao abstract class IngestPreparationDao : IngestPreparationQueries {
    @Query("SELECT * FROM FileTransaction WHERE transactionId=:id") abstract override fun fileTransaction(id: String): FileTransactionRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertFileTransaction(row: FileTransactionRow)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertIngestJob(row: JobRow)
}
