package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.pristinepage.data.repository.CatalogQueries
import app.pristinepage.data.repository.AlbumMetadataQueries
import app.pristinepage.data.repository.EditablePageQueries

// Room's version-1 TEXT primary key has this SQLite auto-index. Without the explicit access path SQLite prefers the lifecycle/updatedAt index and sorts every active document for each tiny page. A PK range scan preserves cursor order without a new schema/index; work can still include inactive gaps, not every distant active row.
private const val ACTIVE_DOCUMENT_IDS_SQL = """SELECT CASE WHEN (typeof(documentId)='text' AND length(documentId)=36 AND length(CAST(documentId AS BLOB))=36) THEN documentId ELSE '' END
    FROM Document INDEXED BY sqlite_autoindex_Document_1 WHERE documentId>=COALESCE(:after,'') AND (:after IS NULL OR documentId>:after) AND lifecycle='ACTIVE' AND NOT $HIDDEN_ATELIER_DOCUMENT ORDER BY documentId LIMIT :limit"""

internal data class SavedSourceMetadataBinding(
    val sourceId: String, val recipeRevision: Long, val blobId: String, val byteHash: String,
    val pixelHash: String, val recipeHash: String, val recipeBytes: Long,
)
internal data class SavedSourceMetadataLocator(val documentId: String, val pageId: String)
internal data class SavedCaptureDayLocator(val documentId: String, val pageId: String, val sourceBlobId: String)

/** Bound SQL only. Business commands run these synchronous metadata operations on IO inside Room's transaction, never across provider/decode/render work. */
@Dao abstract class CatalogDao : CatalogQueries, EditablePageQueries, AlbumMetadataQueries {
    // Keep the ordered Page key seek outermost. This limits returned locators, not how far SQLite may scan for a rare value.
    @Query("""SELECT Page.documentId,Page.pageId FROM Page
        WHERE Page.pageId>COALESCE(:after,'')
        AND (Page.state='ACTIVE' AND Page.removedAt IS NULL AND typeof(Page.ordinal)='integer' AND Page.ordinal BETWEEN 0 AND 99)
        AND (typeof(Page.pageId)='text' AND length(CAST(Page.pageId AS BLOB))=36
          AND Page.pageId GLOB '????????-????-????-????-????????????' AND length(replace(Page.pageId,'-',''))=32
          AND Page.pageId NOT GLOB '*[^0-9a-f-]*')
        AND (typeof(Page.documentId)='text' AND length(CAST(Page.documentId AS BLOB))=36
          AND Page.documentId GLOB '????????-????-????-????-????????????' AND length(replace(Page.documentId,'-',''))=32
          AND Page.documentId NOT GLOB '*[^0-9a-f-]*')
        AND EXISTS (SELECT 1 FROM Document WHERE Document.documentId=Page.documentId
          AND Document.lifecycle='ACTIVE' AND Document.deletedAt IS NULL AND NOT $HIDDEN_ATELIER_DOCUMENT)
        AND EXISTS (SELECT 1 FROM Source s JOIN SourceBlob b ON b.sourceBlobId=s.sourceBlobId
          WHERE s.sourceId=Page.sourceId AND s.state='READY' AND b.state='READY'
          AND ((:mime IS NOT NULL AND :orientation IS NULL AND b.mime=:mime)
            OR (:mime IS NULL AND :orientation IS NOT NULL AND s.exifOrientation=:orientation)))
        ORDER BY Page.pageId LIMIT CASE WHEN :limit BETWEEN 1 AND 50 THEN :limit ELSE 0 END""")
    internal abstract fun scanSourceMetadataCandidates(after: String?, limit: Int, mime: String?, orientation: Int?): List<SavedSourceMetadataLocator>

    // Start with the exact-day index, not the whole Page table. The cap bounds projected rows; one heavily shared blob can still require a local sort.
    @Query("""SELECT p.documentId,p.pageId,day.sourceBlobId
        FROM SourceCaptureDay day INDEXED BY index_SourceCaptureDay_epochDay_sourceBlobId
        CROSS JOIN SourceBlob b ON b.sourceBlobId=day.sourceBlobId
        CROSS JOIN Source s ON s.sourceBlobId=day.sourceBlobId CROSS JOIN Page p ON p.sourceId=s.sourceId
        WHERE (day.epochDay=:epochDay AND typeof(day.epochDay)='integer')
        AND day.sourceBlobId>=COALESCE(:afterBlob,'')
        AND (:afterBlob IS NULL OR day.sourceBlobId>:afterBlob OR p.pageId>:afterPage)
        AND (b.state='READY' AND s.state='READY' AND typeof(day.byteSha256)='text' AND day.byteSha256=b.byteSha256)
        AND (typeof(day.sourceBlobId)='text' AND length(CAST(day.sourceBlobId AS BLOB))=36
          AND day.sourceBlobId GLOB '????????-????-????-????-????????????' AND length(replace(day.sourceBlobId,'-',''))=32
          AND day.sourceBlobId NOT GLOB '*[^0-9a-f-]*')
        AND (p.state='ACTIVE' AND p.removedAt IS NULL AND typeof(p.ordinal)='integer' AND p.ordinal BETWEEN 0 AND 99)
        AND (typeof(p.pageId)='text' AND length(CAST(p.pageId AS BLOB))=36
          AND p.pageId GLOB '????????-????-????-????-????????????' AND length(replace(p.pageId,'-',''))=32
          AND p.pageId NOT GLOB '*[^0-9a-f-]*')
        AND (typeof(p.documentId)='text' AND length(CAST(p.documentId AS BLOB))=36
          AND p.documentId GLOB '????????-????-????-????-????????????' AND length(replace(p.documentId,'-',''))=32
          AND p.documentId NOT GLOB '*[^0-9a-f-]*')
        AND EXISTS (SELECT 1 FROM Document WHERE Document.documentId=p.documentId
          AND Document.lifecycle='ACTIVE' AND Document.deletedAt IS NULL AND NOT $HIDDEN_ATELIER_DOCUMENT)
        ORDER BY day.sourceBlobId,p.pageId LIMIT CASE WHEN :limit BETWEEN 1 AND 50 THEN :limit ELSE 0 END""")
    internal abstract fun scanCaptureDayCandidates(epochDay: Long, afterBlob: String?, afterPage: String?, limit: Int): List<SavedCaptureDayLocator>

    // The caller separately validates the decoded Atelier receipt. No raw EXIF, paths, or recipe byte array crosses this query.
    @Query("""SELECT p.sourceId AS sourceId, p.currentRecipeRevision AS recipeRevision, b.sourceBlobId AS blobId,
        b.byteSha256 AS byteHash, s.pixelSha256 AS pixelHash, r.recipeSha256 AS recipeHash, length(r.canonicalRecipe) AS recipeBytes
        FROM Page p
        JOIN Document d ON d.documentId=p.documentId
        JOIN RecipeRevision r ON r.pageId=p.pageId AND r.recipeRevision=p.currentRecipeRevision AND r.sourceId=p.sourceId
        JOIN Source s ON s.sourceId=p.sourceId JOIN SourceBlob b ON b.sourceBlobId=s.sourceBlobId
        WHERE (p.pageId=:page AND p.documentId=:document AND p.state='ACTIVE' AND p.removedAt IS NULL)
        AND (typeof(p.ordinal)='integer' AND p.ordinal BETWEEN 0 AND 99 AND typeof(p.currentRecipeRevision)='integer' AND p.currentRecipeRevision>=1)
        AND (d.lifecycle='ACTIVE' AND d.deletedAt IS NULL AND s.state='READY' AND b.state='READY')
        AND (typeof(p.sourceId)='text' AND length(CAST(p.sourceId AS BLOB))=36)
        AND (typeof(b.sourceBlobId)='text' AND length(CAST(b.sourceBlobId AS BLOB))=36)
        AND (typeof(b.byteSha256)='text' AND length(b.byteSha256)=64 AND length(CAST(b.byteSha256 AS BLOB))=64 AND b.byteSha256 NOT GLOB '*[^0-9a-f]*')
        AND (typeof(s.pixelSha256)='text' AND length(s.pixelSha256)=64 AND length(CAST(s.pixelSha256 AS BLOB))=64 AND s.pixelSha256 NOT GLOB '*[^0-9a-f]*')
        AND (typeof(r.recipeSha256)='text' AND length(r.recipeSha256)=64 AND length(CAST(r.recipeSha256 AS BLOB))=64 AND r.recipeSha256 NOT GLOB '*[^0-9a-f]*')
        AND (typeof(r.canonicalRecipe)='blob' AND length(r.canonicalRecipe) BETWEEN 1 AND 524288)
        AND (typeof(b.mime)='text' AND b.mime IN ('image/png','image/jpeg','image/webp'))
        AND (typeof(s.exifOrientation)='integer' AND s.exifOrientation BETWEEN 1 AND 8)
        AND (typeof(b.encodedWidth)='integer' AND b.encodedWidth BETWEEN 1 AND 12000
          AND typeof(b.encodedHeight)='integer' AND b.encodedHeight BETWEEN 1 AND 12000 AND b.encodedWidth*b.encodedHeight<=48000000)
        AND (typeof(s.width)='integer' AND typeof(s.height)='integer')
        AND ((s.exifOrientation<=4 AND s.width=b.encodedWidth AND s.height=b.encodedHeight)
          OR (s.exifOrientation>=5 AND s.width=b.encodedHeight AND s.height=b.encodedWidth))
        AND ((:epochDay IS NULL AND ((:mime IS NOT NULL AND :orientation IS NULL AND b.mime=:mime)
          OR (:mime IS NULL AND :orientation IS NOT NULL AND s.exifOrientation=:orientation)))
          OR (:epochDay IS NOT NULL AND :mime IS NULL AND :orientation IS NULL AND EXISTS (
            SELECT 1 FROM SourceCaptureDay day WHERE day.sourceBlobId=b.sourceBlobId
              AND (typeof(day.epochDay)='integer' AND day.epochDay=:epochDay)
              AND (typeof(day.byteSha256)='text' AND day.byteSha256=b.byteSha256))))""")
    internal abstract fun metadataMatchForSavedPage(page: String, document: String, mime: String?, orientation: Int?, epochDay: Long?): SavedSourceMetadataBinding?

    // Only bounded identities cross the first projection. A malformed row remains observable as a tiny invalid sentinel; filtering it out would falsely present an intact empty library.
    @Query(ACTIVE_DOCUMENT_IDS_SQL)
    abstract override fun activeDocumentIdsAfter(after: String?, limit: Int): List<String>
    // SQL emits a tiny malformed sentinel instead of projecting or silently hiding unsafe metadata. The same app-side Unicode and identity validation applies after the chunk arrives.
    @Query("""SELECT CASE WHEN
        ((typeof(documentId)='text' AND length(documentId)=36 AND length(CAST(documentId AS BLOB))=36)
        AND (typeof(title)='text' AND length(CAST(title AS BLOB)) BETWEEN 1 AND 640)
        AND (typeof(documentRevision)='integer' AND documentRevision>=1 AND typeof(createdAt)='integer' AND typeof(updatedAt)='integer')
        AND (deletedAt IS NULL)) THEN documentId ELSE '' END AS documentId,
        CASE WHEN typeof(title)='text' AND length(CAST(title AS BLOB)) BETWEEN 1 AND 640 THEN title ELSE '' END AS title,
        CAST(documentRevision AS INTEGER) AS documentRevision, 'ACTIVE' AS lifecycle,
        CAST(createdAt AS INTEGER) AS createdAt, CAST(updatedAt AS INTEGER) AS updatedAt, NULL AS deletedAt
        FROM Document INDEXED BY sqlite_autoindex_Document_1
        WHERE documentId>=COALESCE(:after,'') AND (:after IS NULL OR documentId>:after) AND lifecycle='ACTIVE' AND NOT $HIDDEN_ATELIER_DOCUMENT
        ORDER BY Document.documentId LIMIT :limit""")
    abstract override fun activeAlbumRowsAfter(after: String?, limit: Int): List<DocumentRow>
    // Existing title writers accept bounded well-formed NULs. SQLite length(TEXT) stops there, possibly at zero; byte bounds remain mandatory and the reader checks the entire materialized UTF-16 value without rewriting it.
    @Query("""SELECT CASE WHEN
        ((typeof(documentId)='text' AND length(documentId)=36 AND length(CAST(documentId AS BLOB))=36) AND (typeof(title)='text' AND length(title)<=160 AND length(CAST(title AS BLOB)) BETWEEN 1 AND 640))
        AND ((typeof(documentRevision)='integer' AND documentRevision>=1) AND (typeof(lifecycle)='text' AND lifecycle IN ('ACTIVE','TOMBSTONED','PURGING') AND length(CAST(lifecycle AS BLOB)) BETWEEN 6 AND 10))
        AND (typeof(createdAt)='integer' AND typeof(updatedAt)='integer' AND (deletedAt IS NULL OR typeof(deletedAt)='integer'))
        THEN 1 ELSE 0 END FROM Document WHERE documentId=:documentId""")
    abstract override fun documentReadIsBounded(documentId: String): Boolean?
    @Query("SELECT COUNT(*) FROM Page WHERE documentId=:documentId AND state='ACTIVE'")
    abstract override fun activePageCount(documentId: String): Long
    // Guard every SELECT-star field before Room's String allocation/Int narrowing. The indexed active set must be bounded and contiguous, not merely have a plausible count; empty documents are valid metadata.
    @Query("""SELECT CASE WHEN
        (SELECT COUNT(*) FROM Page WHERE documentId=:documentId AND state='ACTIVE')<=100
        AND NOT EXISTS(SELECT 1 FROM Page WHERE documentId=:documentId AND state='ACTIVE' AND NOT (
            ((typeof(pageId)='text' AND length(pageId)=36 AND length(CAST(pageId AS BLOB))=36) AND (typeof(documentId)='text' AND length(documentId)=36 AND length(CAST(documentId AS BLOB))=36))
            AND ((typeof(sourceId)='text' AND length(sourceId)=36 AND length(CAST(sourceId AS BLOB))=36) AND (typeof(currentRecipeRevision)='integer' AND currentRecipeRevision>=1))
            AND ((typeof(state)='text' AND length(CAST(state AS BLOB))=6) AND (ordinal IS NOT NULL AND typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 99))
            AND (removedAt IS NULL AND typeof(createdAt)='integer')))
        AND (SELECT COALESCE(MIN(ordinal),0) FROM Page WHERE documentId=:documentId AND state='ACTIVE')=0
        AND (SELECT COALESCE(MAX(ordinal),-1)+1 FROM Page WHERE documentId=:documentId AND state='ACTIVE')=(SELECT COUNT(*) FROM Page WHERE documentId=:documentId AND state='ACTIVE')
        THEN 1 ELSE 0 END""")
    abstract override fun activePagesReadIsBounded(documentId: String): Boolean
    // A save starts with one page ID, before its document is known. Bound every SELECT-star field without projecting a potentially huge identity or narrowing an ordinal first; inactive rows remain observable for an explicit refusal.
    @Query("""SELECT CASE WHEN
        ((typeof(pageId)='text' AND length(pageId)=36 AND length(CAST(pageId AS BLOB))=36) AND (typeof(documentId)='text' AND length(documentId)=36 AND length(CAST(documentId AS BLOB))=36))
        AND ((typeof(sourceId)='text' AND length(sourceId)=36 AND length(CAST(sourceId AS BLOB))=36) AND (typeof(currentRecipeRevision)='integer' AND currentRecipeRevision>=1))
        AND ((typeof(state)='text' AND ((state='ACTIVE' AND length(CAST(state AS BLOB))=6 AND typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 99 AND removedAt IS NULL)
            OR (state='REMOVED' AND length(CAST(state AS BLOB))=7 AND ordinal IS NULL AND typeof(removedAt)='integer'))) AND typeof(createdAt)='integer')
        THEN 1 ELSE 0 END FROM Page WHERE pageId=:pageId""")
    abstract override fun pageReadIsBounded(pageId: String): Boolean?
    // Match the existing pending predicate exactly; no ordering is needed when a valid document has at most one active BATCH. LIMIT2 exposes ambiguity without a history sort or URI-bearing row projection.
    @Query("""SELECT CASE WHEN (typeof(sessionId)='text' AND length(sessionId)=36 AND length(CAST(sessionId AS BLOB))=36) THEN sessionId ELSE '' END
        FROM ImportSession WHERE documentId=:documentId AND kind='BATCH' AND state NOT IN ('COMMITTED','CANCELED','FAILED','QUARANTINED') LIMIT 2""")
    abstract override fun activeImportIds(documentId: String): List<String>
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
    // Diagnose a false joined guard without reading either row. A present Blob at the exact key still leaves a failed guard invalid; only a canonical lowercase UUID link may report a missing Blob.
    @Query("""SELECT CASE WHEN
        ((typeof(s.sourceBlobId)='text' AND length(s.sourceBlobId)=36 AND length(CAST(s.sourceBlobId AS BLOB))=36)
        AND (s.sourceBlobId GLOB '????????-????-????-????-????????????'
            AND length(replace(s.sourceBlobId,'-',''))=32 AND replace(s.sourceBlobId,'-','') NOT GLOB '*[^0-9a-f]*'))
        THEN NOT EXISTS(SELECT 1 FROM SourceBlob b WHERE b.sourceBlobId=s.sourceBlobId)
        ELSE 0 END FROM Source s WHERE s.sourceId=:id""")
    abstract override fun sourceBlobLinkIsMissing(id: String): Boolean?
    // Bound the retained recipe before Room allocates its BLOB or narrows schemaVersion to Int. Future positive Int-range schemas reach explicit unsupported handling; aliases such as 2^32+1 never masquerade as v1. NUL-terminated TEXT length is not a byte bound.
    @Query("""SELECT CASE WHEN
        ((typeof(pageId)='text' AND length(pageId)=36 AND length(CAST(pageId AS BLOB))=36) AND (typeof(sourceId)='text' AND length(sourceId)=36 AND length(CAST(sourceId AS BLOB))=36))
        AND ((typeof(recipeRevision)='integer' AND recipeRevision>=1) AND (typeof(recipeSchemaVersion)='integer' AND recipeSchemaVersion BETWEEN 1 AND 2147483647))
        AND ((typeof(canonicalRecipe)='blob' AND length(canonicalRecipe) BETWEEN 1 AND 524288) AND (typeof(recipeSha256)='text' AND length(recipeSha256)=64 AND length(CAST(recipeSha256 AS BLOB))=64))
        AND ((parentRevision IS NULL OR (typeof(parentRevision)='integer' AND parentRevision>=1 AND parentRevision<recipeRevision)) AND typeof(createdAt)='integer')
        THEN 1 ELSE 0 END FROM RecipeRevision WHERE pageId=:pageId AND recipeRevision=:revision""")
    abstract override fun recipeReadIsBounded(pageId: String, revision: Long): Boolean?
    @Query("SELECT CASE WHEN typeof(canonicalRecipe)='blob' THEN length(canonicalRecipe) ELSE -1 END FROM RecipeRevision WHERE pageId=:pageId AND recipeRevision=:revision")
    abstract override fun recipeBlobByteCount(pageId: String, revision: Long): Long?
    @Query("SELECT EXISTS(SELECT 1 FROM RecipeMask WHERE pageId=:pageId AND recipeRevision=:revision)")
    abstract override fun recipeHasMasks(pageId: String, revision: Long): Boolean
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
    @Query("UPDATE Document SET documentRevision=:revision,updatedAt=:updatedAt WHERE documentId=:id AND documentRevision=:expected")
    abstract override fun advanceDocumentRevision(id: String, expected: Long, revision: Long, updatedAt: Long): Int
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
