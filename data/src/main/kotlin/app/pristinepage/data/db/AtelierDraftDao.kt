package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

@Entity(tableName = "AtelierDraft", foreignKeys = [
    ForeignKey(entity = DocumentRow::class, parentColumns = ["documentId"], childColumns = ["stagingDocumentId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["stagingDocumentId"], unique = true)])
data class AtelierDraftRow(
    @PrimaryKey val draftId: String,
    val stagingDocumentId: String,
    val targetDocumentId: String?,
    val targetRevision: Long?,
    val state: String,
    val generation: Long,
    val step: Int,
    val selectedPageId: String?,
    val committedDocumentId: String?,
    val createdAt: Long,
    val activeBatchId: String? = null,
    val receiptPageIds: String? = null,
)

/** Cheap discovery filter. RoomMetadataAccess additionally decodes the bounded receipt before use as a saved document. */
internal const val HIDDEN_ATELIER_DOCUMENT = """EXISTS (
    SELECT 1 FROM AtelierDraft d WHERE d.stagingDocumentId=Document.documentId
      AND NOT (d.state='COMMITTED' AND d.targetDocumentId IS NULL AND d.targetRevision IS NULL
        AND d.committedDocumentId IS Document.documentId AND d.activeBatchId IS NULL
        AND typeof(d.generation)='integer' AND d.generation>1 AND typeof(d.step)='integer' AND d.step=2
        AND typeof(d.receiptPageIds)='text' AND length(CAST(d.receiptPageIds AS BLOB)) BETWEEN 197 AND 1400
        AND instr(d.receiptPageIds,'atelier-publish-v1'||char(10)||(d.generation-1)||char(10)||Document.documentId||char(10))=1)
)"""

@Dao
abstract class AtelierDraftDao {
    @Query("SELECT $HIDDEN_ATELIER_DOCUMENT FROM Document WHERE documentId=:id")
    abstract fun savedDocumentIsHidden(id: String): Boolean?
    /** Caller has excluded structurally hidden rows in the same transaction. Bound the projection independently. */
    @Query("""SELECT receiptPageIds FROM AtelierDraft WHERE stagingDocumentId=:id
        AND typeof(receiptPageIds)='text' AND length(CAST(receiptPageIds AS BLOB)) BETWEEN 197 AND 1400""")
    abstract fun savedPromotionReceipt(id: String): String?

    @Query("SELECT * FROM AtelierDraft WHERE draftId=:id") abstract fun draft(id: String): AtelierDraftRow?
    @Query("SELECT * FROM AtelierDraft WHERE stagingDocumentId=:id") abstract fun forStagingDocument(id: String): AtelierDraftRow?
    @Query("""SELECT stagingDocumentId FROM AtelierDraft
        WHERE draftId=:id AND state='EDITING' AND typeof(generation)='integer' AND generation=:generation
          AND step IN (0,1,2) AND activeBatchId IS NULL AND committedDocumentId IS NULL AND receiptPageIds IS NULL
          AND typeof(stagingDocumentId)='text' AND length(CAST(stagingDocumentId AS BLOB))=36""")
    abstract fun editingDocument(id: String, generation: Long): String?
    @Query("""SELECT EXISTS (SELECT 1 FROM SourceReference WHERE ownerType='PAGE' AND ownerId=:page AND sourceId=:source)
        AND EXISTS (SELECT 1 FROM SourceReference WHERE ownerType='RECIPE_HISTORY' AND ownerId=:history AND sourceId=:source)""")
    abstract fun publicationHasPins(source: String, page: String, history: String): Boolean
    @Query("""SELECT CASE WHEN
        (typeof(generation)='integer' AND generation>0 AND typeof(step)='integer' AND step IN (0,1,2) AND typeof(createdAt)='integer')
        AND (typeof(draftId)='text' AND length(CAST(draftId AS BLOB))=36 AND typeof(stagingDocumentId)='text' AND length(CAST(stagingDocumentId AS BLOB))=36)
        AND (typeof(state)='text' AND state IN ('ACQUIRING','EDITING','ABANDONED','COMMITTED') AND length(CAST(state AS BLOB))<=9)
        AND ((targetDocumentId IS NULL AND targetRevision IS NULL) OR (typeof(targetDocumentId)='text' AND length(CAST(targetDocumentId AS BLOB))=36 AND typeof(targetRevision)='integer' AND targetRevision>0))
        AND (selectedPageId IS NULL OR (typeof(selectedPageId)='text' AND length(CAST(selectedPageId AS BLOB))=36))
        AND (activeBatchId IS NULL OR (typeof(activeBatchId)='text' AND length(CAST(activeBatchId AS BLOB))=36))
        AND (committedDocumentId IS NULL OR (typeof(committedDocumentId)='text' AND length(CAST(committedDocumentId AS BLOB))=36))
        AND (receiptPageIds IS NULL OR (typeof(receiptPageIds)='text' AND length(CAST(receiptPageIds AS BLOB)) BETWEEN 1 AND :limit))
        THEN 1 ELSE 0 END FROM AtelierDraft WHERE draftId=:id""")
    abstract fun publicationReceiptIsBounded(id: String, limit: Int): Boolean?
    @Query("""SELECT $HIDDEN_ATELIER_DOCUMENT AND NOT EXISTS
        (SELECT 1 FROM AtelierDraft WHERE stagingDocumentId=:id AND state='ACQUIRING' AND activeBatchId=:batchId)
        FROM Document WHERE documentId=:id""")
    abstract fun refusesDraftIngest(id: String, batchId: String): Boolean
    @Query("SELECT COUNT(*) FROM ImportSession WHERE documentId=:id AND kind='ITEM'")
    abstract fun attemptedItems(id: String): Long
    @Query("""SELECT COUNT(*) FROM ImportSession item
        JOIN FileTransaction attempt ON attempt.kind='INGEST' AND attempt.ownerId=item.sessionId
        WHERE item.documentId=:id AND item.kind='ITEM'""")
    abstract fun retainedCopyAttempts(id: String): Long
    @Query("SELECT * FROM AtelierDraft WHERE state IN ('ACQUIRING','EDITING') ORDER BY createdAt,draftId LIMIT 2")
    abstract fun unfinished(): List<AtelierDraftRow>
    @Query("""SELECT COUNT(*) FROM AtelierDraft d WHERE d.state NOT IN ('ABANDONED','COMMITTED') OR EXISTS (
        SELECT 1 FROM ImportSession item JOIN FileTransaction attempt ON attempt.kind='INGEST' AND attempt.ownerId=item.sessionId
        WHERE item.documentId=d.stagingDocumentId AND item.kind='ITEM'
          AND (d.state='ABANDONED' OR item.state!='COMMITTED' OR attempt.state!='COMMITTED')
    )""")
    abstract fun hiddenCount(): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract fun insert(row: AtelierDraftRow)
    @Update(onConflict = OnConflictStrategy.ABORT) abstract fun update(row: AtelierDraftRow): Int
}
