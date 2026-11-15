package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Query
import app.pristinepage.data.repository.ReferenceAuditQueries
import app.pristinepage.data.repository.ReferenceCountRow

/** No state filter: removed-page history and quarantined/deleting normalized sources still own rows. Correlated COUNT uses the existing leading owner/FK indices and never loads an unbounded owner list. */
@Dao abstract class ReferenceAuditDao : ReferenceAuditQueries {
    // The leading range retains an indexed seek on every page. Null includes empty TEXT IDs on the first page; the second predicate makes later valid-ID pages exclusive without losing that range constraint.
    @Query("SELECT s.sourceId AS objectId,s.refCount AS storedCount,(SELECT COUNT(*) FROM SourceReference r WHERE r.sourceId=s.sourceId) AS actualCount FROM Source s WHERE s.sourceId>=COALESCE(:objectId,'') AND (:objectId IS NULL OR s.sourceId>:objectId) ORDER BY s.sourceId LIMIT :limit")
    abstract override fun sourcesAfter(objectId: String?, limit: Int): List<ReferenceCountRow>

    @Query("SELECT b.sourceBlobId AS objectId,b.sourceCount AS storedCount,(SELECT COUNT(*) FROM Source s WHERE s.sourceBlobId=b.sourceBlobId) AS actualCount FROM SourceBlob b WHERE b.sourceBlobId>=COALESCE(:objectId,'') AND (:objectId IS NULL OR b.sourceBlobId>:objectId) ORDER BY b.sourceBlobId LIMIT :limit")
    abstract override fun sourceBlobsAfter(objectId: String?, limit: Int): List<ReferenceCountRow>

    @Query("SELECT m.maskId AS objectId,m.refCount AS storedCount,((SELECT COUNT(*) FROM RecipeMask r WHERE r.maskId=m.maskId)+(SELECT COUNT(*) FROM MaskReference r WHERE r.maskId=m.maskId)) AS actualCount FROM Mask m WHERE m.maskId>=COALESCE(:objectId,'') AND (:objectId IS NULL OR m.maskId>:objectId) ORDER BY m.maskId LIMIT :limit")
    abstract override fun masksAfter(objectId: String?, limit: Int): List<ReferenceCountRow>

    @Query("SELECT p.pageId AS objectId,1 AS storedCount,(SELECT COUNT(*) FROM SourceReference r WHERE r.ownerType='PAGE' AND r.ownerId=p.pageId AND r.sourceId=p.sourceId) AS actualCount FROM Page p WHERE p.pageId>=COALESCE(:objectId,'') AND (:objectId IS NULL OR p.pageId>:objectId) ORDER BY p.pageId LIMIT :limit")
    abstract override fun pageSourcePinsAfter(objectId: String?, limit: Int): List<ReferenceCountRow>

    // Count exact canonical history-key/source matches, not just one pin for the current revision. Grouping by page preserves stable UUID pagination while every retained revision participates.
    @Query("SELECT p.pageId AS objectId,(SELECT COUNT(*) FROM RecipeRevision h WHERE h.pageId=p.pageId) AS storedCount,(SELECT COUNT(*) FROM RecipeRevision h JOIN SourceReference r ON r.ownerType='RECIPE_HISTORY' AND r.ownerId=('recipe-v1/' || h.pageId || '/' || h.recipeRevision) AND r.sourceId=h.sourceId WHERE h.pageId=p.pageId) AS actualCount FROM Page p WHERE p.pageId>=COALESCE(:objectId,'') AND (:objectId IS NULL OR p.pageId>:objectId) ORDER BY p.pageId LIMIT :limit")
    abstract override fun recipeSourcePinsAfter(objectId: String?, limit: Int): List<ReferenceCountRow>

    @Query("SELECT m.maskId AS objectId,1 AS storedCount,(SELECT COUNT(*) FROM SourceReference r WHERE r.ownerType='MASK' AND r.ownerId=m.maskId AND r.sourceId=m.sourceId) AS actualCount FROM Mask m WHERE m.maskId>=COALESCE(:objectId,'') AND (:objectId IS NULL OR m.maskId>:objectId) ORDER BY m.maskId LIMIT :limit")
    abstract override fun maskSourcePinsAfter(objectId: String?, limit: Int): List<ReferenceCountRow>

    // Enumerate retained Sources through their primary key and pins through index(sourceId). History keys have a10-character prefix,36-character UUID and separator before the revision at offset48. Extract only candidates for the indexed (pageId,recipeRevision) lookup, then require complete canonical reconstruction: CAST must not legitimize leading-zero/suffix/overflow aliases. Other owner families are deliberately not counted by this direct-owner route.
    @Query("SELECT s.sourceId AS objectId,(SELECT COUNT(*) FROM SourceReference r WHERE r.sourceId=s.sourceId AND r.ownerType IN ('PAGE','RECIPE_HISTORY','MASK')) AS storedCount,(SELECT COUNT(*) FROM SourceReference r WHERE r.sourceId=s.sourceId AND ((r.ownerType='PAGE' AND EXISTS(SELECT 1 FROM Page p WHERE p.pageId=r.ownerId AND p.sourceId=r.sourceId)) OR (r.ownerType='MASK' AND EXISTS(SELECT 1 FROM Mask m WHERE m.maskId=r.ownerId AND m.sourceId=r.sourceId)) OR (r.ownerType='RECIPE_HISTORY' AND EXISTS(SELECT 1 FROM RecipeRevision h WHERE h.pageId=SUBSTR(r.ownerId,11,36) AND h.recipeRevision=CAST(SUBSTR(r.ownerId,48) AS INTEGER) AND r.ownerId=('recipe-v1/' || h.pageId || '/' || h.recipeRevision) AND h.sourceId=r.sourceId)))) AS actualCount FROM Source s WHERE s.sourceId>=COALESCE(:objectId,'') AND (:objectId IS NULL OR s.sourceId>:objectId) ORDER BY s.sourceId LIMIT :limit")
    abstract override fun directSourceOwnersAfter(objectId: String?, limit: Int): List<ReferenceCountRow>
}
