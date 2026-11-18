package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pristinepage.data.repository.AssemblyJobIdentityRow
import app.pristinepage.data.repository.ExportSnapshotQueries
import app.pristinepage.data.repository.FrozenRenderJobIdentityRow

@Dao abstract class ExportSnapshotDao : ExportSnapshotQueries {
    // Return only a scalar before Room extracts any settings bytes. SQLite length(BLOB) reads its byte count, not a Kotlin ByteArray; type checks prevent coercion of corrupt storage classes.
    @Query("""SELECT CASE WHEN (typeof(settings)='blob' AND length(settings) BETWEEN 1 AND 524288)
        AND (typeof(snapshotId)='text' AND length(snapshotId)=36) AND (typeof(documentId)='text' AND length(documentId)=36)
        AND (typeof(snapshotSha256)='text' AND length(snapshotSha256)=64) AND (typeof(state)='text' AND length(state) BETWEEN 1 AND 128)
        AND (typeof(documentRevision)='integer' AND documentRevision>0) AND (typeof(createdAt)='integer' AND typeof(retainedUntil)='integer')
        THEN 1 ELSE 0 END FROM ExportSnapshot WHERE snapshotId=:snapshotId""")
    abstract override fun snapshotMetadataIsBounded(snapshotId: String): Boolean?

    // Validate raw SQLite INTEGER values BEFORE generated getLong().toInt() can wrap them. Charge all frozen BLOBs plus the identity helper's conservative per-page overhead, including settings, before SELECT * materializes arrays.
    @Query("""SELECT CASE WHEN COUNT(*) BETWEEN 1 AND 100
        AND COALESCE(SUM(length(recipe)+length(masks)+length(providers)+4096),0)+(SELECT length(settings)+4096 FROM ExportSnapshot WHERE snapshotId=:snapshotId)<=8388608
        AND MIN(CASE WHEN (typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 99)
            AND (typeof(snapshotId)='text' AND length(snapshotId)=36) AND (typeof(originalPageId)='text' AND length(originalPageId)=36)
            AND (typeof(sourceId)='text' AND length(sourceId)=36) AND (typeof(sourceBlobId)='text' AND length(sourceBlobId)=36)
            AND (typeof(byteSha256)='text' AND length(byteSha256)=64) AND (typeof(pixelSha256)='text' AND length(pixelSha256)=64)
            AND (typeof(recipeSha256)='text' AND length(recipeSha256)=64) AND (typeof(assetSetSha256)='text' AND length(assetSetSha256)=64)
            AND (typeof(width)='integer' AND width BETWEEN 1 AND 12000) AND (typeof(height)='integer' AND height BETWEEN 1 AND 12000 AND width*height<=48000000)
            AND (typeof(conversionFingerprint)='text' AND length(CAST(conversionFingerprint AS BLOB)) BETWEEN 1 AND 512)
            AND (typeof(rendererVersion)='text' AND length(CAST(rendererVersion AS BLOB)) BETWEEN 1 AND 512)
            AND (typeof(recipeRevision)='integer' AND recipeRevision>0) AND (typeof(recipeSchemaVersion)='integer' AND recipeSchemaVersion BETWEEN 1 AND 2147483647)
            AND (typeof(recipe)='blob' AND length(recipe) BETWEEN 1 AND 524288)
            AND (typeof(masks)='blob' AND length(masks) BETWEEN 1 AND 524288) AND (typeof(providers)='blob' AND length(providers) BETWEEN 1 AND 524288)
            AND (typeof(requestedOutputWidth)='integer' AND requestedOutputWidth BETWEEN 1 AND 2147483647)
            AND (typeof(requestedOutputHeight)='integer' AND requestedOutputHeight BETWEEN 1 AND 2147483647)
            AND (typeof(outputWidth)='integer' AND outputWidth BETWEEN 1 AND 2147483647)
            AND (typeof(outputHeight)='integer' AND outputHeight BETWEEN 1 AND 2147483647)
            AND (typeof(fallbackPolicy)='text' AND length(fallbackPolicy) BETWEEN 1 AND 128)
            AND ((acceptedRenderId IS NULL AND acceptedExecutionSha256 IS NULL) OR (typeof(acceptedRenderId)='text' AND length(acceptedRenderId)=36 AND typeof(acceptedExecutionSha256)='text' AND length(acceptedExecutionSha256)=64))
            THEN 1 ELSE 0 END)=1 THEN 1 ELSE 0 END FROM ExportPage WHERE snapshotId=:snapshotId""")
    abstract override fun exportPagesAreBounded(snapshotId: String): Boolean

    @Query("SELECT * FROM ExportSnapshot WHERE snapshotId=:id") abstract override fun snapshot(id: String): ExportSnapshotRow?
    @Query("SELECT * FROM ExportPage WHERE snapshotId=:snapshotId ORDER BY ordinal LIMIT 101") abstract override fun exportPages(snapshotId: String): List<ExportPageRow>
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertSnapshot(row: ExportSnapshotRow)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertExportPage(row: ExportPageRow)

    // A canonical ASCII JobId matched by the binary primary key already bounds jobId. Check other projected TEXT in bytes (SQLite length(TEXT) stops at NUL), and check the raw ordinal without narrowing it. Wire grammar and binding are verified separately.
    @Query("""SELECT CASE WHEN (typeof(input)='blob' AND length(input) BETWEEN 184 AND 4144)
        AND (typeof(kind)='text' AND length(CAST(kind AS BLOB))=15 AND length(kind)=15)
        AND (typeof(idempotenceKey)='text' AND length(CAST(idempotenceKey AS BLOB))=55 AND length(idempotenceKey)=55)
        AND (typeof(canonicalInputSha256)='text' AND length(CAST(canonicalInputSha256 AS BLOB))=64 AND length(canonicalInputSha256)=64)
        AND (typeof(snapshotId)='text' AND length(CAST(snapshotId AS BLOB))=36 AND length(snapshotId)=36)
        AND pageOrdinal IS NULL THEN 1 ELSE 0 END FROM Job WHERE jobId=:jobId""")
    internal abstract fun assemblyJobReadIsBounded(jobId: String): Boolean?

    // Only use after admission in the same transaction; no state/checkpoint/lease fields or typed ordinal cross this boundary.
    @Query("SELECT jobId, kind, idempotenceKey, canonicalInputSha256, snapshotId, input FROM Job WHERE jobId=:jobId")
    internal abstract fun assemblyJobIdentity(jobId: String): AssemblyJobIdentityRow?

    // The caller binds the exact nonzero v1 byte extent rebuilt from one authenticated frozen page, not the assembly envelope or a recipe cap. CASE preserves corrupt-present versus absent.
    @Query("""SELECT CASE WHEN (:expectedInputBytes>0 AND typeof(input)='blob' AND length(input)=:expectedInputBytes)
        AND (typeof(jobId)='text' AND length(CAST(jobId AS BLOB))=36 AND length(jobId)=36)
        AND (typeof(kind)='text' AND length(CAST(kind AS BLOB))=11 AND kind='PAGE_RENDER')
        AND (typeof(idempotenceKey)='text' AND length(CAST(idempotenceKey AS BLOB))=79 AND length(idempotenceKey)=79 AND substr(idempotenceKey,1,15)='page-render-v1:')
        AND (typeof(canonicalInputSha256)='text' AND length(CAST(canonicalInputSha256 AS BLOB))=64 AND length(canonicalInputSha256)=64)
        AND (snapshotId IS NULL AND pageOrdinal IS NULL) THEN 1 ELSE 0 END FROM Job WHERE jobId=:jobId""")
    internal abstract fun renderJobReadIsBounded(jobId: String, expectedInputBytes: Int): Boolean?

    @Query("SELECT jobId, kind, idempotenceKey, canonicalInputSha256, input FROM Job WHERE jobId=:jobId")
    internal abstract fun renderJobIdentity(jobId: String): FrozenRenderJobIdentityRow?

    // The indexed JOB owner predicate fixes both owner fields. Preserve invalid source IDs as a bounded sentinel, rather than filtering away evidence of a corrupt or second pin.
    @Query("""SELECT CASE WHEN (typeof(sourceId)='text' AND length(CAST(sourceId AS BLOB))=36 AND length(sourceId)=36) THEN sourceId ELSE '' END
        FROM SourceReference WHERE ownerType='JOB' AND ownerId=:jobId LIMIT 2""")
    internal abstract fun boundedRenderJobPinSourceIds(jobId: String): List<String>

    @Query("SELECT * FROM Job WHERE idempotenceKey=:key") abstract override fun renderJobByKey(key: String): JobRow?
    // Ordinary page jobs have exactly one source owner. A second row is sufficient to expose invalid cardinality without loading an unbounded corrupt owner set.
    @Query("SELECT * FROM SourceReference WHERE ownerType='JOB' AND ownerId=:jobId LIMIT 2") abstract override fun jobSourceReferences(jobId: String): List<SourceReferenceRow>
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertExportJob(row: JobRow)
}
