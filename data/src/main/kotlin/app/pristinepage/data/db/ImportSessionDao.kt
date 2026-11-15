package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.pristinepage.data.repository.ImportSessionQueries

@Dao abstract class ImportSessionDao : ImportSessionQueries {
    @Query("SELECT * FROM ImportSession WHERE sessionId=:id") abstract override fun session(id: String): ImportSessionRow?
    @Query("SELECT * FROM ImportSession WHERE parentSessionId=:parentId ORDER BY selectionOrdinal") abstract override fun children(parentId: String): List<ImportSessionRow>
    @Query("SELECT * FROM ImportSession WHERE documentId=:documentId AND kind='BATCH' AND state NOT IN ('COMMITTED','CANCELED','FAILED','QUARANTINED') ORDER BY createdAt,sessionId") abstract override fun activeBatches(documentId: String): List<ImportSessionRow>
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertSession(row: ImportSessionRow)
    @Update(onConflict = OnConflictStrategy.ABORT) abstract override fun updateSession(row: ImportSessionRow): Int
}
