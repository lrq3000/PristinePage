package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Query
import app.pristinepage.data.repository.IngestRecoveryQueries

/** Opaque immutable transaction IDs provide stable pagination while phase timestamps/states advance. Terminal outcomes are already recorded; this unfinished-ingest pass neither erases them nor pretends to inspect all other recovery domains. */
@Dao abstract class IngestRecoveryDao : IngestRecoveryQueries {
    @Query("SELECT * FROM FileTransaction WHERE kind='INGEST' AND transactionId>:transactionId AND state NOT IN ('COMMITTED','CANCELED','FAILED','QUARANTINED') ORDER BY transactionId LIMIT :limit")
    abstract override fun unfinishedIngestsAfter(transactionId: String, limit: Int): List<FileTransactionRow>
}
