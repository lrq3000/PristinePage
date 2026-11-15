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
}
