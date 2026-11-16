package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.pristinepage.data.repository.CatalogQueries
import app.pristinepage.data.repository.CommittedSourceQueries

/** Bound SQL only. Business commands run these synchronous metadata operations on IO inside Room's transaction, never across provider/decode/render work. */
@Dao abstract class CatalogDao : CatalogQueries, CommittedSourceQueries {
    // Group related predicates explicitly: this scalar guard precedes SELECT-star/Int narrowing. TEXT character length stops at NUL, so raw UTF-8 byte ceilings must also hold before any Kotlin materialization; a128-UTF16-unit fingerprint needs at most512UTF8bytes.
    @Query("""SELECT CASE WHEN
        ((typeof(s.sourceId)='text' AND length(s.sourceId)=36 AND length(CAST(s.sourceId AS BLOB))=36) AND (typeof(s.sourceBlobId)='text' AND length(s.sourceBlobId)=36 AND length(CAST(s.sourceBlobId AS BLOB))=36))
        AND ((typeof(s.pixelSha256)='text' AND length(s.pixelSha256)=64 AND length(CAST(s.pixelSha256 AS BLOB))=64) AND (typeof(s.conversionFingerprint)='text' AND length(s.conversionFingerprint) BETWEEN 1 AND 128 AND length(CAST(s.conversionFingerprint AS BLOB)) BETWEEN 1 AND 512))
        AND (s.profileSha256 IS NULL OR (typeof(s.profileSha256)='text' AND length(s.profileSha256)=64 AND length(CAST(s.profileSha256 AS BLOB))=64))
        AND ((typeof(s.width)='integer' AND s.width BETWEEN 1 AND 12000) AND (typeof(s.height)='integer' AND s.height BETWEEN 1 AND 12000) AND s.width*s.height<=48000000)
        AND ((typeof(s.exifOrientation)='integer' AND s.exifOrientation BETWEEN 1 AND 8) AND (typeof(s.state)='text' AND s.state='READY' AND length(CAST(s.state AS BLOB))=5))
        AND ((typeof(s.refCount)='integer' AND s.refCount>=0) AND typeof(s.createdAt)='integer')
        AND EXISTS(SELECT 1 FROM SourceBlob b WHERE b.sourceBlobId=s.sourceBlobId
            AND ((typeof(b.sourceBlobId)='text' AND length(b.sourceBlobId)=36 AND length(CAST(b.sourceBlobId AS BLOB))=36) AND (typeof(b.byteSha256)='text' AND length(b.byteSha256)=64 AND length(CAST(b.byteSha256 AS BLOB))=64))
            AND ((typeof(b.byteCount)='integer' AND b.byteCount BETWEEN 1 AND 104857600) AND (typeof(b.mime)='text' AND b.mime IN ('image/png','image/jpeg','image/webp') AND length(CAST(b.mime AS BLOB)) BETWEEN 9 AND 10))
            AND ((typeof(b.encodedWidth)='integer' AND b.encodedWidth BETWEEN 1 AND 12000) AND (typeof(b.encodedHeight)='integer' AND b.encodedHeight BETWEEN 1 AND 12000) AND b.encodedWidth*b.encodedHeight<=48000000)
            AND ((typeof(b.relativePath)='text' AND length(CAST(b.relativePath AS BLOB)) BETWEEN 1 AND 128 AND b.relativePath='sources/'||b.sourceBlobId||'/original.bin') AND (typeof(b.state)='text' AND b.state='READY' AND length(CAST(b.state AS BLOB))=5))
            AND ((typeof(b.sourceCount)='integer' AND b.sourceCount>=0) AND (typeof(b.ingestTransactionId)='text' AND length(b.ingestTransactionId)=36 AND length(CAST(b.ingestTransactionId AS BLOB))=36) AND typeof(b.createdAt)='integer'))
        THEN 1 ELSE 0 END FROM Source s WHERE s.sourceId=:id""")
    abstract override fun sourceReadIsBounded(id: String): Boolean?
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
