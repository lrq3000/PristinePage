package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

internal data class OcrSavedPageLocator(val documentId: String, val pageId: String)
internal data class LibraryOcrResultPointer(val generation: Long, val jobId: String, val resultJobId: String?, val projectionVersion: Long?)

@Dao
internal abstract class OcrPageWorkDao {
    // Keep Page as the outer index seek so a later cursor does not sort or revisit earlier album pages. The row limit bounds returned locators, not the length of a rejected range.
    @Query("""SELECT Page.documentId,Page.pageId FROM Page
        WHERE Page.pageId>COALESCE(:after,'')
          AND (Page.state='ACTIVE' AND Page.removedAt IS NULL AND typeof(Page.ordinal)='integer' AND Page.ordinal BETWEEN 0 AND 99)
          AND (typeof(Page.pageId)='text' AND length(CAST(Page.pageId AS BLOB))=36
            AND Page.pageId GLOB '????????-????-????-????-????????????' AND length(replace(Page.pageId,'-',''))=32
            AND Page.pageId NOT GLOB '*[^0-9a-f-]*')
          AND EXISTS (SELECT 1 FROM Document WHERE Document.documentId=Page.documentId
            AND (Document.lifecycle='ACTIVE' AND Document.deletedAt IS NULL AND NOT $HIDDEN_ATELIER_DOCUMENT)
            AND (typeof(Document.documentId)='text' AND length(CAST(Document.documentId AS BLOB))=36
              AND Document.documentId GLOB '????????-????-????-????-????????????' AND length(replace(Document.documentId,'-',''))=32
              AND Document.documentId NOT GLOB '*[^0-9a-f-]*'))
          AND EXISTS (SELECT 1 FROM Source JOIN SourceBlob ON SourceBlob.sourceBlobId=Source.sourceBlobId
            WHERE Source.sourceId=Page.sourceId AND Source.state='READY' AND SourceBlob.state='READY')
        ORDER BY Page.pageId LIMIT CASE WHEN :limit BETWEEN 1 AND 50 THEN :limit ELSE 0 END""")
    abstract fun scanSavedPages(after: String?, limit: Int): List<OcrSavedPageLocator>

    // Keep a corrupt present row distinguishable from absence and check raw fields before any Room narrowing or string materialization.
    @Query("""SELECT CASE WHEN
        (typeof(pageId)='text' AND length(CAST(pageId AS BLOB))=36)
        AND (typeof(jobId)='text' AND length(CAST(jobId AS BLOB))=36 AND length(jobId)=36)
        AND (typeof(generation)='integer' AND generation>0)
        AND (typeof(inputSha256)='text' AND length(CAST(inputSha256 AS BLOB))=64 AND length(inputSha256)=64 AND inputSha256 NOT GLOB '*[^0-9a-f]*')
        AND (typeof(inputBytes)='integer' AND inputBytes BETWEEN 1 AND 3532)
        AND ((resultJobId IS NULL AND projectionVersion IS NULL) OR
            (typeof(resultJobId)='text' AND length(CAST(resultJobId AS BLOB))=36 AND length(resultJobId)=36
             AND typeof(projectionVersion)='integer' AND projectionVersion BETWEEN 1 AND 2147483647))
        THEN 1 ELSE 0 END FROM OcrPageWork WHERE pageId=:page""")
    abstract fun pointerIsBounded(page: String): Boolean?

    @Query("SELECT * FROM OcrPageWork WHERE pageId=:page")
    abstract fun pointer(page: String): OcrPageWorkRow?

    // Backup needs the producing pointer and generation order, not the candidate rerun's input digest, extent, or job payload.
    @Query("""SELECT
        CASE WHEN typeof(generation)='integer' AND generation>0 THEN generation ELSE 0 END AS generation,
        CASE WHEN typeof(jobId)='text' AND length(CAST(jobId AS BLOB))=36 THEN jobId ELSE '' END AS jobId,
        CASE WHEN resultJobId IS NULL THEN NULL
            WHEN typeof(resultJobId)='text' AND length(CAST(resultJobId AS BLOB))=36 THEN resultJobId ELSE '' END AS resultJobId,
        CASE WHEN projectionVersion IS NULL THEN NULL
            WHEN typeof(projectionVersion)='integer' AND projectionVersion>0 THEN projectionVersion ELSE 0 END AS projectionVersion
        FROM OcrPageWork WHERE pageId=:page""")
    abstract fun libraryResultPointer(page: String): LibraryOcrResultPointer?

    /** Authorize the requested visible membership before touching an OCR pointer or job that could fail validation. */
    @Query("""SELECT EXISTS (SELECT 1 FROM Page p JOIN Document d ON d.documentId=p.documentId
        WHERE p.pageId=:page AND p.documentId=:document AND p.state='ACTIVE' AND p.removedAt IS NULL
        AND typeof(p.ordinal)='integer' AND p.ordinal BETWEEN 0 AND 99
        AND d.lifecycle='ACTIVE' AND d.deletedAt IS NULL)""")
    abstract fun visibleStatusPage(page: String, document: String): Boolean

    // This checks only accepted metadata. The caller separately checks the decoded Atelier promotion receipt; no recipe BLOB, title, or original is read here.
    @Query("""SELECT EXISTS (SELECT 1 FROM Page p
        JOIN Document d ON d.documentId=p.documentId
        JOIN RecipeRevision r ON r.pageId=p.pageId AND r.recipeRevision=p.currentRecipeRevision AND r.sourceId=p.sourceId
        JOIN Source s ON s.sourceId=p.sourceId JOIN SourceBlob b ON b.sourceBlobId=s.sourceBlobId
        WHERE p.pageId=:page AND p.documentId=:document AND p.sourceId=:source
          AND p.state='ACTIVE' AND p.removedAt IS NULL AND typeof(p.ordinal)='integer' AND p.ordinal BETWEEN 0 AND 99
          AND typeof(p.currentRecipeRevision)='integer' AND p.currentRecipeRevision=:revision
          AND d.lifecycle='ACTIVE' AND d.deletedAt IS NULL
          AND typeof(r.recipeSha256)='text' AND r.recipeSha256=:recipeSha
          AND s.state='READY' AND b.state='READY' AND typeof(s.pixelSha256)='text' AND s.pixelSha256=:sourceSha)""")
    abstract fun liveInputMatches(page: String, document: String, source: String, revision: Long, recipeSha: String, sourceSha: String): Boolean

    @Query("""SELECT EXISTS (SELECT 1 FROM Page p JOIN Document d ON d.documentId=p.documentId
        JOIN RecipeRevision r ON r.pageId=p.pageId AND r.recipeRevision=p.currentRecipeRevision AND r.sourceId=p.sourceId
        JOIN Source s ON s.sourceId=p.sourceId JOIN SourceBlob b ON b.sourceBlobId=s.sourceBlobId
        WHERE p.pageId=:page AND p.documentId=:document AND p.sourceId=:source AND p.state='REMOVED'
        AND p.ordinal IS NULL AND typeof(p.removedAt)='integer'
        AND typeof(p.currentRecipeRevision)='integer' AND p.currentRecipeRevision=:revision
        AND d.lifecycle='ACTIVE' AND d.deletedAt IS NULL
        AND typeof(r.recipeSha256)='text' AND r.recipeSha256=:recipeSha
        AND s.state='READY' AND b.state='READY' AND typeof(s.pixelSha256)='text' AND s.pixelSha256=:sourceSha)""")
    abstract fun removedLibraryInputMatches(page: String, document: String, source: String, revision: Long, recipeSha: String, sourceSha: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertJob(row: JobRow)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertPointer(row: OcrPageWorkRow)

    // The producing job and projection version belong to the retained text, not the newly queued input.
    @Query("""UPDATE OcrPageWork SET jobId=:newJob,generation=:newGeneration,inputSha256=:newDigest,inputBytes=:newExtent
        WHERE pageId=:page AND jobId=:oldJob AND generation=:oldGeneration AND inputSha256=:oldDigest AND inputBytes=:oldExtent""")
    abstract fun replaceCurrent(
        page: String, oldJob: String, oldGeneration: Long, oldDigest: String, oldExtent: Int,
        newJob: String, newGeneration: Long, newDigest: String, newExtent: Int,
    ): Int

    @Query("""SELECT EXISTS (SELECT 1 FROM SourceReference WHERE ownerType='JOB' AND ownerId=:job AND sourceId=:source)
        AND NOT EXISTS (SELECT 1 FROM SourceReference WHERE ownerType='JOB' AND ownerId=:job AND sourceId IS NOT :source)""")
    abstract fun hasExactJobPin(job: String, source: String): Boolean

    @Query("SELECT EXISTS (SELECT 1 FROM SourceReference WHERE ownerType='JOB' AND ownerId=:job)")
    abstract fun hasAnyJobPin(job: String): Boolean

    @Query("""SELECT EXISTS (SELECT 1 FROM Source WHERE sourceId=:source AND typeof(refCount)='integer' AND refCount=:expected)
        AND (SELECT COUNT(*) FROM SourceReference WHERE sourceId=:source)=:expected""")
    abstract fun sourcePinCountMatches(source: String, expected: Long): Boolean

    @Query("""UPDATE OcrPageWork SET resultJobId=:job,projectionVersion=:version
        WHERE pageId=:page AND jobId=:job AND generation=:generation AND inputSha256=:digest AND inputBytes=:extent
          AND resultJobId IS :previousJob AND projectionVersion IS :previousVersion""")
    abstract fun publishResult(
        page: String, job: String, generation: Long, digest: String, extent: Int,
        previousJob: String?, previousVersion: Int?, version: Int,
    ): Int
}
