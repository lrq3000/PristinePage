package app.pristinepage.data.library

import android.database.sqlite.SQLiteDatabase
import java.io.IOException

/** The portable shape is narrower than the Room schema. Never drop an unexpected app-private or unowned row on import. */
internal object LibraryPortableCatalogShape {
    private const val REFUSED = """SELECT EXISTS (
        SELECT 1 FROM AtelierDraft UNION ALL SELECT 1 FROM ImportSession UNION ALL SELECT 1 FROM UndoEntry
        UNION ALL SELECT 1 FROM Confirmation UNION ALL SELECT 1 FROM Mask UNION ALL SELECT 1 FROM RecipeMask
        UNION ALL SELECT 1 FROM MaskReference UNION ALL SELECT 1 FROM AssetPin UNION ALL SELECT 1 FROM RenderReference
        UNION ALL SELECT 1 FROM FileTransaction UNION ALL SELECT 1 FROM RenderedPage UNION ALL SELECT 1 FROM ExportSnapshot
        UNION ALL SELECT 1 FROM ExportPage UNION ALL SELECT 1 FROM ExportArtifact UNION ALL SELECT 1 FROM Publication
        UNION ALL SELECT 1 FROM ShareLease UNION ALL SELECT 1 FROM OcrTextPublication
        UNION ALL SELECT 1 FROM Document WHERE lifecycle!='ACTIVE' OR deletedAt IS NOT NULL
        UNION ALL SELECT 1 FROM Page p WHERE NOT EXISTS (
            SELECT 1 FROM LibraryScanName n WHERE n.pageId=p.pageId AND n.documentId=p.documentId
        ) OR NOT EXISTS (
            SELECT 1 FROM SourceReference ref WHERE ref.ownerType='PAGE' AND ref.ownerId=p.pageId AND ref.sourceId=p.sourceId
        ) OR NOT EXISTS (
            SELECT 1 FROM RecipeRevision r WHERE r.pageId=p.pageId AND r.recipeRevision=p.currentRecipeRevision AND r.sourceId=p.sourceId
        )
        UNION ALL SELECT 1 FROM Page WHERE state='ACTIVE' GROUP BY documentId
            HAVING min(ordinal)!=0 OR max(ordinal)!=count(*)-1 OR count(*)>100
        UNION ALL SELECT 1 FROM RecipeRevision r JOIN Page p ON p.pageId=r.pageId WHERE r.sourceId!=p.sourceId OR NOT EXISTS (
            SELECT 1 FROM SourceReference ref WHERE ref.ownerType='RECIPE_HISTORY'
                AND ref.ownerId='recipe-v1/'||r.pageId||'/'||r.recipeRevision AND ref.sourceId=r.sourceId
        )
        UNION ALL SELECT 1 FROM SourceReference WHERE ownerType NOT IN ('PAGE','RECIPE_HISTORY') OR typeof(createdAt)!='integer'
        UNION ALL SELECT 1 FROM SourceReference WHERE (
            SELECT count(*) FROM SourceReference
        ) != (SELECT count(*) FROM Page) + (SELECT count(*) FROM RecipeRevision) LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM Source s WHERE length(s.conversionFingerprint) NOT BETWEEN 1 AND 128
            OR s.refCount!=(SELECT count(*) FROM SourceReference ref WHERE ref.sourceId=s.sourceId) OR NOT EXISTS (
            SELECT 1 FROM Page p WHERE p.sourceId=s.sourceId
        )
        UNION ALL SELECT 1 FROM SourceBlob b WHERE b.sourceCount<1 OR b.sourceCount!=(SELECT count(*) FROM Source s WHERE s.sourceBlobId=b.sourceBlobId)
        UNION ALL SELECT 1 FROM OcrResult r WHERE NOT EXISTS (SELECT 1 FROM OcrPageWork w WHERE w.pageId=r.pageId)
        UNION ALL SELECT 1 FROM OcrPageWork w WHERE w.resultJobId IS NOT w.jobId OR NOT EXISTS (SELECT 1 FROM OcrResult r WHERE r.pageId=w.pageId)
        UNION ALL SELECT 1 FROM Job j WHERE j.kind!='PAGE_OCR' OR NOT EXISTS (SELECT 1 FROM OcrPageWork w WHERE w.jobId=j.jobId)
    ) AS refused"""

    fun verify(database: SQLiteDatabase) {
        database.rawQuery(REFUSED, null).use {
            if (!it.moveToFirst() || it.getInt(0) != 0) throw IOException("Library catalog contains unsupported or unowned records")
        }
    }
}
