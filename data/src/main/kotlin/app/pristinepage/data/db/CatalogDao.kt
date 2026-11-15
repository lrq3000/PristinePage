package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.pristinepage.data.repository.CatalogQueries

/** Bound SQL only. Business commands run these synchronous metadata operations on IO inside Room's transaction, never across provider/decode/render work. */
@Dao abstract class CatalogDao : CatalogQueries {
    @Query("SELECT * FROM Document WHERE documentId=:id") abstract override fun document(id: String): DocumentRow?
    // All retained lifecycles participate in availability expiry; select IDs only, with an indexed initial range that also exposes invalid empty IDs for rejection.
    @Query("SELECT documentId FROM Document WHERE documentId>=COALESCE(:objectId,'') AND (:objectId IS NULL OR documentId>:objectId) ORDER BY documentId LIMIT :limit")
    abstract override fun retainedDocumentIdsAfter(objectId: String?, limit: Int): List<String>
    @Query("SELECT * FROM Page WHERE documentId=:documentId AND state='ACTIVE' ORDER BY ordinal") abstract override fun activePages(documentId: String): List<PageRow>
    @Query("SELECT * FROM Page WHERE pageId=:id") abstract override fun page(id: String): PageRow?
    @Query("SELECT * FROM Source WHERE sourceId=:id") abstract override fun source(id: String): SourceRow?
    @Query("SELECT * FROM SourceBlob WHERE sourceBlobId=:id") abstract override fun sourceBlob(id: String): SourceBlobRow?
    @Query("SELECT * FROM RecipeRevision WHERE pageId=:pageId AND recipeRevision=:revision") abstract override fun recipe(pageId: String, revision: Long): RecipeRevisionRow?
    @Query("SELECT COALESCE(MAX(recipeRevision),0) FROM RecipeRevision WHERE pageId=:pageId") abstract override fun maxRecipeRevision(pageId: String): Long
    @Query("SELECT * FROM RecipeMask WHERE pageId=:pageId AND recipeRevision=:revision ORDER BY maskId") abstract override fun recipeMasks(pageId: String, revision: Long): List<RecipeMaskRow>
    @Query("SELECT * FROM Mask WHERE maskId=:id") abstract override fun mask(id: String): MaskRow?
    @Query("SELECT * FROM UndoEntry WHERE undoId=:id") abstract override fun undo(id: String): UndoEntryRow?
    @Query("SELECT EXISTS(SELECT 1 FROM ImportSession WHERE documentId=:documentId AND kind='BATCH' AND state NOT IN ('COMMITTED','CANCELED','FAILED','QUARANTINED'))") abstract override fun hasPendingImport(documentId: String): Boolean
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertDocument(row: DocumentRow)
    @Update(onConflict = OnConflictStrategy.ABORT) abstract override fun updateDocument(row: DocumentRow): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertPage(row: PageRow)
    @Update(onConflict = OnConflictStrategy.ABORT) abstract override fun updatePage(row: PageRow): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertRecipe(row: RecipeRevisionRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract override fun insertReference(row: SourceReferenceRow): Long
    @Query("SELECT COUNT(*) FROM SourceReference WHERE sourceId=:sourceId") abstract override fun countSourceReferences(sourceId: String): Long
    @Query("UPDATE Source SET refCount=:count WHERE sourceId=:sourceId") abstract override fun setSourceRefCount(sourceId: String, count: Long): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertUndo(row: UndoEntryRow)
    @Update(onConflict = OnConflictStrategy.ABORT) abstract override fun updateUndo(row: UndoEntryRow): Int
    // Count state candidates, including clock-expired AVAILABLE/APPLIED rows not yet visited by the policy. Do not load serialized before/after BLOBs or delete their ownership pins here.
    @Query("SELECT COUNT(*) FROM UndoEntry WHERE documentId=:documentId AND state IN ('AVAILABLE','APPLIED')") abstract override fun countUnexpiredUndoEntries(documentId: String): Long
    @Query("UPDATE UndoEntry SET state='EXPIRED' WHERE documentId=:documentId AND state IN ('AVAILABLE','APPLIED') AND (expiresAt<=:now OR undoId NOT IN (SELECT undoId FROM UndoEntry WHERE documentId=:documentId ORDER BY appliedDocumentRevision DESC,undoId DESC LIMIT 20))")
    abstract override fun expireUndoEntries(documentId: String, now: Long): Int
    @Query("SELECT EXISTS(SELECT 1 FROM (SELECT undoId FROM UndoEntry WHERE documentId=:documentId ORDER BY appliedDocumentRevision DESC,undoId DESC LIMIT 20) retained WHERE retained.undoId=:undoId)")
    abstract override fun undoIsWithinWindow(documentId: String, undoId: String): Boolean
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertRecipeMask(row: RecipeMaskRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract override fun insertMaskReference(row: MaskReferenceRow): Long
    @Query("SELECT (SELECT COUNT(*) FROM RecipeMask WHERE maskId=:maskId)+(SELECT COUNT(*) FROM MaskReference WHERE maskId=:maskId)") abstract override fun countMaskReferences(maskId: String): Long
    @Query("UPDATE Mask SET refCount=:count WHERE maskId=:maskId") abstract override fun setMaskRefCount(maskId: String, count: Long): Int
}
