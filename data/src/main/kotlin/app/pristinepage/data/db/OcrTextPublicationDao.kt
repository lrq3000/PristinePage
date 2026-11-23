package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "OcrTextPublication",
    foreignKeys = [ForeignKey(entity = FileTransactionRow::class, parentColumns = ["transactionId"],
        childColumns = ["transactionId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["transactionId"]), Index(value = ["state", "consentId"])])
internal data class OcrTextPublicationRow(
    @PrimaryKey val consentId: String,
    val transactionId: String,
    val ownerId: String,
    val byteSha256: String,
    val byteCount: Long,
    val state: String,
    val targetUri: String?,
    val updatedAt: Long,
)

internal data class OcrTextPublicationOwner(val consentId: String, val ownerId: String, val state: String)
internal class OcrTextPublicationRecoveryRow(val transactionId: String, val targetUri: String?, val byteSha256: String, val byteCount: Long)
internal class OcrTextPublicationSeal(val payload: ByteArray, val sealSha256: String)
internal data class OcrTextPublicationTarget(val consentId: String, val ownerId: String, val byteSha256: String,
    val byteCount: Long, val state: String, val updatedAt: Long)

@Dao
internal abstract class OcrTextPublicationDao {
    @Insert internal abstract fun insert(row: OcrTextPublicationRow)

    @Query("""SELECT NOT EXISTS(SELECT 1 FROM OcrTextPublication WHERE transactionId=:tx
        AND state NOT IN ('CANCELED','PARTIAL_EXTERNAL','NEEDS_VERIFICATION','DESTINATION_REFUSED','PERMISSION_LOST'))""")
    internal abstract fun canStart(tx: String): Boolean

    @Query("""SELECT consentId,ownerId,byteSha256,byteCount,state,updatedAt FROM OcrTextPublication
        WHERE transactionId=:tx AND targetUri=:uri AND consentId<>:exclude
        AND length(CAST(consentId AS BLOB))=36 AND length(CAST(ownerId AS BLOB))=36
        AND length(CAST(byteSha256 AS BLOB))=64 AND length(CAST(state AS BLOB)) BETWEEN 1 AND 24
        ORDER BY consentId LIMIT 1""")
    internal abstract fun recordedTarget(tx: String, uri: String, exclude: String): OcrTextPublicationTarget?

    @Query("""SELECT EXISTS(SELECT 1 FROM OcrTextPublication
        WHERE transactionId=:tx AND targetUri=:uri AND consentId<>:exclude)""")
    internal abstract fun hasRecordedTarget(tx: String, uri: String, exclude: String): Boolean

    /** A terminal acknowledgement is not replayable, and a result for another attempt cannot change the bound target. */
    @Query("""UPDATE OcrTextPublication SET state=:next, targetUri=:uri, updatedAt=:now
        WHERE consentId=:id AND transactionId=:tx AND ownerId=:owner AND state=:previous
        AND (byteSha256=:digest AND typeof(byteCount)='integer' AND byteCount=:size AND byteCount BETWEEN 0 AND 393216)
        AND targetUri IS :previousUri
        AND ((:previous='WAITING_DESTINATION' AND :previousUri IS NULL AND
            ((:next='CANCELED' AND :uri IS NULL) OR
              (:next='WRITING' AND :uri GLOB 'content://?*' AND length(CAST(:uri AS BLOB))<=4096
                  AND NOT EXISTS(SELECT 1 FROM OcrTextPublication AS prior WHERE prior.transactionId=:tx AND prior.targetUri=:uri))))
          OR (:previous='WRITING' AND :uri=:previousUri AND :next IN
            ('READBACK_VERIFIED','OUTPUT_COMPLETED','PARTIAL_EXTERNAL','NEEDS_VERIFICATION','DESTINATION_REFUSED','PERMISSION_LOST')))""")
    internal abstract fun advance(id: String, tx: String, owner: String, digest: String, size: Long,
        previous: String, next: String, previousUri: String?, uri: String?, now: Long): Int

    @Query("""SELECT EXISTS(SELECT 1 FROM OcrTextPublication WHERE consentId=:id AND transactionId=:tx AND ownerId=:owner
        AND (byteSha256=:digest AND typeof(byteCount)='integer' AND byteCount=:size) AND state=:phase AND targetUri IS :uri)""")
    internal abstract fun matches(id: String, tx: String, owner: String, digest: String, size: Long, phase: String, uri: String?): Boolean

    // Recovery never projects a URI, never touches the provider, and acts only after positive exact owner termination.
    @Query("""SELECT consentId,ownerId,state FROM OcrTextPublication
        WHERE state=:phase AND consentId>:after
        AND (length(CAST(consentId AS BLOB))=36 AND length(CAST(ownerId AS BLOB))=36)
        ORDER BY consentId LIMIT 32""")
    internal abstract fun pending(phase: String, after: String): List<OcrTextPublicationOwner>

    @Query("""UPDATE OcrTextPublication SET state='NEEDS_VERIFICATION', updatedAt=:now
        WHERE consentId=:id AND ownerId=:owner AND state=:previous AND
          ((state='WAITING_DESTINATION' AND targetUri IS NULL) OR (state='WRITING' AND targetUri IS NOT NULL))""")
    internal abstract fun endAbandoned(id: String, owner: String, previous: String, now: Long): Int

    @Query("""SELECT transactionId, targetUri, byteSha256, byteCount FROM OcrTextPublication
        WHERE state='NEEDS_VERIFICATION' AND
        (length(CAST(transactionId AS BLOB))=36 AND typeof(byteSha256)='text' AND length(CAST(byteSha256 AS BLOB))=64
            AND typeof(byteCount)='integer' AND byteCount BETWEEN 0 AND 393216)
        AND (targetUri IS NULL OR (typeof(targetUri)='text' AND length(CAST(targetUri AS BLOB)) BETWEEN 11 AND 4096))
        ORDER BY updatedAt DESC, consentId LIMIT 1""")
    internal abstract fun latestUncertain(): OcrTextPublicationRecoveryRow?

    @Query("""SELECT payload, sealSha256 FROM FileTransaction WHERE transactionId=:tx AND kind='OCR_TEXT' AND state='COMMITTED'
        AND (expectedByteSha256=:digest AND typeof(expectedByteCount)='integer' AND expectedByteCount=:size)
        AND (typeof(payload)='blob' AND length(payload) BETWEEN 274 AND 279)
        AND (typeof(sealSha256)='text' AND length(CAST(sealSha256 AS BLOB))=64 AND sealSha256 NOT GLOB '*[^0-9a-f]*')""")
    internal abstract fun frozenReceipt(tx: String, digest: String, size: Long): OcrTextPublicationSeal?
}
