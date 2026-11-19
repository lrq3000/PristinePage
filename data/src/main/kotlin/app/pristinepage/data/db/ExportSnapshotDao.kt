package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pristinepage.data.repository.AssemblyJobIdentityRow
import app.pristinepage.data.repository.ExportItemIntentRow
import app.pristinepage.data.repository.ExportSnapshotQueries
import app.pristinepage.data.repository.FrozenRenderJobIdentityRow
import app.pristinepage.data.repository.RenderCompletionQueries
import app.pristinepage.data.repository.RenderIntentQueries

@Dao abstract class ExportSnapshotDao : ExportSnapshotQueries, RenderIntentQueries {
    // Return only a scalar before Room extracts any settings bytes. SQLite length(BLOB) reads its byte count, not a Kotlin ByteArray; type checks prevent coercion of corrupt storage classes.
    // Both queries match the caller's canonical snapshotId by exact key. Other projected IDs and hashes need both extents because length(TEXT) stops at NUL.
    // Bound state and fallback text to four UTF-8 bytes per allowed character without deciding which values the typed reader supports.
    @Query("""SELECT CASE WHEN (typeof(settings)='blob' AND length(settings) BETWEEN 1 AND 524288)
        AND (typeof(snapshotId)='text' AND length(snapshotId)=36)
        AND (typeof(documentId)='text' AND length(documentId)=36 AND length(CAST(documentId AS BLOB))=36)
        AND (typeof(snapshotSha256)='text' AND length(snapshotSha256)=64 AND length(CAST(snapshotSha256 AS BLOB))=64)
        AND (typeof(state)='text' AND length(state) BETWEEN 1 AND 128 AND length(CAST(state AS BLOB)) BETWEEN 1 AND 512)
        AND (typeof(documentRevision)='integer' AND documentRevision>0) AND (typeof(createdAt)='integer' AND typeof(retainedUntil)='integer')
        THEN 1 ELSE 0 END FROM ExportSnapshot WHERE snapshotId=:snapshotId""")
    abstract override fun snapshotMetadataIsBounded(snapshotId: String): Boolean?

    // Validate raw SQLite INTEGER values BEFORE generated getLong().toInt() can wrap them. Charge all frozen BLOBs plus the identity helper's conservative per-page overhead, including settings, before SELECT * materializes arrays.
    @Query("""SELECT CASE WHEN COUNT(*) BETWEEN 1 AND 100
        AND COALESCE(SUM(length(recipe)+length(masks)+length(providers)+4096),0)+(SELECT length(settings)+4096 FROM ExportSnapshot WHERE snapshotId=:snapshotId)<=8388608
        AND MIN(CASE WHEN (typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 99)
            AND (typeof(snapshotId)='text' AND length(snapshotId)=36)
            AND (typeof(originalPageId)='text' AND length(originalPageId)=36 AND length(CAST(originalPageId AS BLOB))=36)
            AND (typeof(sourceId)='text' AND length(sourceId)=36 AND length(CAST(sourceId AS BLOB))=36)
            AND (typeof(sourceBlobId)='text' AND length(sourceBlobId)=36 AND length(CAST(sourceBlobId AS BLOB))=36)
            AND (typeof(byteSha256)='text' AND length(byteSha256)=64 AND length(CAST(byteSha256 AS BLOB))=64)
            AND (typeof(pixelSha256)='text' AND length(pixelSha256)=64 AND length(CAST(pixelSha256 AS BLOB))=64)
            AND (typeof(recipeSha256)='text' AND length(recipeSha256)=64 AND length(CAST(recipeSha256 AS BLOB))=64)
            AND (typeof(assetSetSha256)='text' AND length(assetSetSha256)=64 AND length(CAST(assetSetSha256 AS BLOB))=64)
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
            AND (typeof(fallbackPolicy)='text' AND length(fallbackPolicy) BETWEEN 1 AND 128 AND length(CAST(fallbackPolicy AS BLOB)) BETWEEN 1 AND 512)
            AND ((acceptedRenderId IS NULL AND acceptedExecutionSha256 IS NULL) OR (
                (typeof(acceptedRenderId)='text' AND length(acceptedRenderId)=36 AND length(CAST(acceptedRenderId AS BLOB))=36)
                AND (typeof(acceptedExecutionSha256)='text' AND length(acceptedExecutionSha256)=64 AND length(CAST(acceptedExecutionSha256 AS BLOB))=64)))
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

    // Call only after the separate assemblyJobReadIsBounded statement admits this exact row in the same transaction. A scalar result does not bound the BLOB comparison.
    @Query("""SELECT CASE WHEN (kind='EXPORT_ASSEMBLY' AND snapshotId=:snapshotId AND pageOrdinal IS NULL)
        AND (idempotenceKey=:key AND canonicalInputSha256=:digest AND typeof(input)='blob' AND input=:input)
        THEN 1 ELSE 0 END FROM Job WHERE jobId=:jobId""")
    internal abstract fun freshAssemblyInputMatches(jobId: String, snapshotId: String, key: String, digest: String, input: ByteArray): Boolean?

    // Run alongside the existing generic lease-storage bounds. The virgin phases bind zero/nulls; the post-CAS check binds the actual assigned attempt, owner and elapsed deadline.
    @Query("""SELECT CASE WHEN (state=:state AND typeof(attempt)='integer' AND attempt=:attempt)
        AND (typeof(leaseGeneration)='integer' AND leaseGeneration=:attempt)
        AND (leaseOwner IS :owner AND leaseUntil IS :until AND checkpoint IS NULL AND errorCode IS NULL)
        THEN 1 ELSE 0 END FROM Job WHERE jobId=:jobId""")
    internal abstract fun freshAssemblyPhaseMatches(jobId: String, state: String, attempt: Long, owner: String?, until: Long?): Boolean?

    // The caller has already rechecked the retained frozen input and bounded the full Job row in this same transaction.
    @Query("""UPDATE Job SET state='ADMITTED', updatedAt=:updatedAt WHERE jobId=:jobId
        AND (kind='EXPORT_ASSEMBLY' AND snapshotId=:snapshotId AND pageOrdinal IS NULL)
        AND (idempotenceKey=:key AND canonicalInputSha256=:digest AND typeof(input)='blob' AND input=:input)
        AND (state='PENDING' AND typeof(attempt)='integer' AND attempt=0 AND typeof(leaseGeneration)='integer' AND leaseGeneration=0)
        AND (leaseOwner IS NULL AND leaseUntil IS NULL AND checkpoint IS NULL AND errorCode IS NULL)
        AND (typeof(createdAt)='integer' AND typeof(updatedAt)='integer' AND updatedAt=:previousUpdatedAt)""")
    internal abstract fun admitPendingAssembly(
        jobId: String, snapshotId: String, key: String, digest: String, input: ByteArray, previousUpdatedAt: Long, updatedAt: Long,
    ): Int

    // Only a missing exact key may authorize an insert. Project an invalid ID as a sentinel rather than filtering the match or returning SQL NULL.
    @Query("""SELECT CASE WHEN (typeof(jobId)='text' AND length(CAST(jobId AS BLOB))=36 AND length(jobId)=36) THEN jobId ELSE '' END
        FROM Job WHERE idempotenceKey=:key""")
    abstract override fun renderJobIdByKey(key: String): String?

    // The caller binds the exact nonzero v1 byte extent rebuilt from one authenticated frozen page, not the assembly envelope or a recipe cap. CASE preserves corrupt-present versus absent.
    @Query("""SELECT CASE WHEN (:expectedInputBytes>0 AND typeof(input)='blob' AND length(input)=:expectedInputBytes)
        AND (typeof(jobId)='text' AND length(CAST(jobId AS BLOB))=36 AND length(jobId)=36)
        AND (typeof(kind)='text' AND length(CAST(kind AS BLOB))=11 AND kind='PAGE_RENDER')
        AND (typeof(idempotenceKey)='text' AND length(CAST(idempotenceKey AS BLOB))=79 AND length(idempotenceKey)=79 AND substr(idempotenceKey,1,15)='page-render-v1:')
        AND (typeof(canonicalInputSha256)='text' AND length(CAST(canonicalInputSha256 AS BLOB))=64 AND length(canonicalInputSha256)=64)
        AND (snapshotId IS NULL AND pageOrdinal IS NULL) THEN 1 ELSE 0 END FROM Job WHERE jobId=:jobId""")
    abstract override fun renderJobReadIsBounded(jobId: String, expectedInputBytes: Int): Boolean?

    @Query("SELECT jobId, kind, idempotenceKey, canonicalInputSha256, input FROM Job WHERE jobId=:jobId")
    abstract override fun renderJobIdentity(jobId: String): FrozenRenderJobIdentityRow?

    // Bind the canonical UUID, v1 key/hash and detached input rebuilt from the frozen page. Require renderJobReadIsBounded to pass on that exact ID and input extent in the same writer transaction before comparing the BLOB; SQL AND order is not an extent guard. JOB pin and resource/lease proofs are separate.
    @Query("""UPDATE Job SET state='ADMITTED', updatedAt=:updatedAt
        WHERE jobId=:jobId AND (kind='PAGE_RENDER' AND state='PENDING')
        AND (idempotenceKey=:idempotenceKey AND canonicalInputSha256=:canonicalInputSha256 AND typeof(input)='blob' AND input=:input)
        AND (snapshotId IS NULL AND pageOrdinal IS NULL)
        AND (typeof(attempt)='integer' AND attempt=0 AND typeof(leaseGeneration)='integer' AND leaseGeneration=0)
        AND (leaseOwner IS NULL AND leaseUntil IS NULL AND checkpoint IS NULL AND errorCode IS NULL)
        AND NOT EXISTS(SELECT 1 FROM RenderedPage WHERE jobId=:jobId)
        AND NOT EXISTS(SELECT 1 FROM FileTransaction WHERE kind='RENDER' AND ownerId=:jobId)""")
    internal abstract fun admitPendingRender(
        jobId: String, idempotenceKey: String, canonicalInputSha256: String, input: ByteArray, updatedAt: Long,
    ): Int

    // The indexed JOB owner predicate fixes both owner fields. Preserve invalid source IDs as a bounded sentinel, rather than filtering away evidence of a corrupt or second pin.
    @Query("""SELECT CASE WHEN (typeof(sourceId)='text' AND length(CAST(sourceId AS BLOB))=36 AND length(sourceId)=36) THEN sourceId ELSE '' END
        FROM SourceReference WHERE ownerType='JOB' AND ownerId=:jobId LIMIT 2""")
    abstract override fun boundedRenderJobPinSourceIds(jobId: String): List<String>

    // Supplement renderJobReadIsBounded before invoking the existing full JobLeaseDao projection. That guard owns input, immutable TEXT and the raw-null ordinal; every other projected field is admitted here.
    @Query("""SELECT CASE WHEN (typeof(state)='text' AND length(CAST(state AS BLOB)) BETWEEN 1 AND 32)
        AND (typeof(attempt)='integer' AND attempt>=0)
        AND (leaseOwner IS NULL OR (typeof(leaseOwner)='text' AND length(CAST(leaseOwner AS BLOB))=36 AND length(leaseOwner)=36))
        AND (leaseUntil IS NULL OR (typeof(leaseUntil)='integer' AND leaseUntil>=0))
        AND (typeof(leaseGeneration)='integer' AND leaseGeneration>=0)
        AND (checkpoint IS NULL OR (typeof(checkpoint)='blob' AND length(checkpoint) BETWEEN 1 AND 524288))
        AND (errorCode IS NULL OR (typeof(errorCode)='text' AND length(CAST(errorCode AS BLOB)) BETWEEN 1 AND 64))
        AND (typeof(createdAt)='integer') AND (typeof(updatedAt)='integer')
        THEN 1 ELSE 0 END FROM Job WHERE jobId=:jobId""")
    abstract override fun renderJobLeaseReadIsBounded(jobId: String): Boolean?

    // Immutable job admission is separate. Read only the exact successful lease-free attempt, never JobRow or its checkpoint.
    @Query("""SELECT attempt FROM Job WHERE jobId=:jobId
        AND (typeof(kind)='text' AND kind='PAGE_RENDER')
        AND (typeof(state)='text' AND state='SUCCEEDED')
        AND (typeof(attempt)='integer' AND attempt>0 AND typeof(leaseGeneration)='integer' AND leaseGeneration=attempt)
        AND (leaseOwner IS NULL AND leaseUntil IS NULL AND errorCode IS NULL)""")
    internal abstract fun terminalRenderAttempt(jobId: String): Long?

    // Keep every matching sibling eligible for the two-ID result, including non-READY rows. The sentinel preserves a malformed row for cardinality checks; UUID grammar is checked by the consumer.
    @Query("""SELECT CASE WHEN (typeof(renderId)='text' AND length(CAST(renderId AS BLOB))=36 AND length(renderId)=36)
        THEN renderId ELSE '' END FROM RenderedPage WHERE jobId=:jobId LIMIT 2""")
    internal abstract fun terminalRenderIds(jobId: String): List<String>

    // A present mismatch returns false, not absence. Check raw storage before Room can narrow dimensions or extract the execution BLOB; use the caller's writer-derived bound.
    @Query("""SELECT CASE WHEN (typeof(renderId)='text' AND length(CAST(renderId AS BLOB))=36 AND length(renderId)=36)
        AND (typeof(jobId)='text' AND length(CAST(jobId AS BLOB))=36 AND length(jobId)=36 AND jobId=:jobId)
        AND (typeof(pageId)='text' AND length(CAST(pageId AS BLOB))=36 AND length(pageId)=36 AND pageId=:pageId)
        AND (typeof(sourceId)='text' AND length(CAST(sourceId AS BLOB))=36 AND length(sourceId)=36)
        AND (typeof(recipeRevision)='integer' AND recipeRevision>0 AND recipeRevision=:recipeRevision)
        AND (typeof(width)='integer' AND width>0 AND width=:width)
        AND (typeof(height)='integer' AND height>0 AND height=:height)
        AND (typeof(cacheKey)='text' AND length(CAST(cacheKey AS BLOB))=80 AND length(cacheKey)=80 AND substr(cacheKey,1,16)='render-cache-v1:')
        AND (typeof(executionSha256)='text' AND length(CAST(executionSha256 AS BLOB))=64 AND length(executionSha256)=64)
        AND (typeof(byteSha256)='text' AND length(CAST(byteSha256 AS BLOB))=64 AND length(byteSha256)=64)
        AND (typeof(rasterPixelSha256)='text' AND length(CAST(rasterPixelSha256 AS BLOB))=64 AND length(rasterPixelSha256)=64)
        AND (typeof(relativePath)='text' AND relativePath=('renders/' || renderId || '/page.bin'))
        AND (typeof(byteCount)='integer' AND byteCount>0)
        AND (typeof(state)='text' AND state='READY')
        AND (typeof(renderStatus)='text' AND renderStatus='OK')
        AND (typeof(execution)='blob' AND length(execution) BETWEEN 1 AND :maxExecutionBytes)
        THEN 1 ELSE 0 END FROM RenderedPage WHERE renderId=:renderId""")
    internal abstract fun terminalRenderReadIsBounded(
        renderId: String, jobId: String, pageId: String, recipeRevision: Long, width: Int, height: Int, maxExecutionBytes: Int,
    ): Boolean?

    // Only after a unique canonical ID and a successful raw guard for that same ID in the same Room transaction; no admission is performed by this projection.
    @Query("SELECT * FROM RenderedPage WHERE renderId=:renderId")
    internal abstract fun terminalRenderRow(renderId: String): RenderedPageRow?

    // kind and ownerId exactly match a bounded ASCII literal/argument in the binary unique key. Guard attempt's actual INTEGER class and every other field before SELECT *.
    @Query("""SELECT CASE WHEN (typeof(transactionId)='text' AND length(CAST(transactionId AS BLOB))=36 AND length(transactionId)=36)
        AND (typeof(state)='text' AND length(CAST(state AS BLOB)) BETWEEN 1 AND 32)
        AND (typeof(stagingPath)='text' AND length(CAST(stagingPath AS BLOB))=57 AND length(stagingPath)=57)
        AND (typeof(finalPath)='text' AND length(CAST(finalPath AS BLOB))=53 AND length(finalPath)=53)
        AND (sealSha256 IS NULL OR (typeof(sealSha256)='text' AND length(CAST(sealSha256 AS BLOB))=64 AND length(sealSha256)=64))
        AND (expectedByteSha256 IS NULL OR (typeof(expectedByteSha256)='text' AND length(CAST(expectedByteSha256 AS BLOB))=64 AND length(expectedByteSha256)=64))
        AND (expectedByteCount IS NULL OR (typeof(expectedByteCount)='integer' AND expectedByteCount>0))
        AND (:expectedPayloadBytes>0 AND typeof(payload)='blob' AND length(payload)=:expectedPayloadBytes)
        AND (typeof(attempt)='integer' AND attempt>0)
        AND (errorCode IS NULL OR (typeof(errorCode)='text' AND length(CAST(errorCode AS BLOB)) BETWEEN 1 AND 64))
        AND (typeof(updatedAt)='integer')
        THEN 1 ELSE 0 END FROM FileTransaction WHERE kind='RENDER' AND ownerId=:jobId AND attempt=:attempt""")
    abstract override fun renderIntentReadIsBounded(jobId: String, attempt: Long, expectedPayloadBytes: Int): Boolean?

    // Bind the exact canonical key and live attempt derived by the caller. No row means absent; a matched false must never authorize insert or typed projection. True admits raw shape only, not payload or path identity.
    @Query("""SELECT CASE WHEN (typeof(attempt)='integer' AND attempt>0)
        AND (typeof(transactionId)='text' AND length(CAST(transactionId AS BLOB))=36 AND length(transactionId)=36)
        AND (typeof(stagingPath)='text' AND length(CAST(stagingPath AS BLOB))=57 AND length(stagingPath)=57)
        AND (typeof(finalPath)='text' AND length(CAST(finalPath AS BLOB))=85 AND length(finalPath)=85)
        AND (:expectedPayloadBytes>0 AND typeof(payload)='blob' AND length(payload)=:expectedPayloadBytes)
        AND (errorCode IS NULL AND typeof(updatedAt)='integer')
        AND ((state IN ('INTENT','COPYING') AND sealSha256 IS NULL AND expectedByteSha256 IS NULL AND expectedByteCount IS NULL)
          OR (state IN ('SEALED','MOVED','COMMITTED')
            AND (typeof(sealSha256)='text' AND length(CAST(sealSha256 AS BLOB))=64 AND length(sealSha256)=64)
            AND (typeof(expectedByteSha256)='text' AND length(CAST(expectedByteSha256 AS BLOB))=64 AND length(expectedByteSha256)=64)
            AND (typeof(expectedByteCount)='integer' AND expectedByteCount>0)))
        THEN 1 ELSE 0 END FROM FileTransaction
        WHERE kind='EXPORT' COLLATE BINARY AND ownerId=:ownerKey COLLATE BINARY AND attempt=:attempt""")
    internal abstract fun exportItemIntentReadIsBounded(ownerKey: String, attempt: Long, expectedPayloadBytes: Int): Boolean?

    // Only after scalar admission of this expected ordinal in the same transaction. No audit time or unexpected ordinal's payload is projected.
    @Query("""SELECT transactionId, kind, ownerId, attempt, state, stagingPath, finalPath, sealSha256,
        expectedByteSha256, expectedByteCount, payload, errorCode FROM FileTransaction
        WHERE kind='EXPORT' COLLATE BINARY AND ownerId=:ownerKey COLLATE BINARY AND attempt=:attempt""")
    internal abstract fun exportItemIntentRow(ownerKey: String, attempt: Long): ExportItemIntentRow?

    // Only after checkFresh and the continuing owned attempt have been authenticated in the same writer transaction. This statement fixes the v1 owner/path grammar; it does not authenticate the plan or interpret its payload. Invalid shape inserts nothing; either existing UNIQUE key aborts, even for a corrupt row.
    @Query("""INSERT OR ABORT INTO FileTransaction (transactionId,kind,ownerId,attempt,state,stagingPath,finalPath,
        sealSha256,expectedByteSha256,expectedByteCount,payload,errorCode,updatedAt)
        SELECT :transactionId,'EXPORT','export-item-v1:' || :assemblyJobId || ':' || :ordinal,:attempt,'INTENT',
            'staging/' || :transactionId || '/derived.part','exports/' || :snapshotId || '/' || :artifactId || '.bin',
            NULL,NULL,NULL,:payload,NULL,:updatedAt
        WHERE typeof(:ordinal)='integer' AND :ordinal BETWEEN 0 AND 99
            AND typeof(:attempt)='integer' AND :attempt>0
            AND typeof(:updatedAt)='integer' AND :updatedAt>=0
            AND typeof(:payload)='blob' AND length(:payload)=685
            AND (typeof(:assemblyJobId)='text' AND length(CAST(:assemblyJobId AS BLOB))=36
                AND :assemblyJobId GLOB '????????-????-????-????-????????????'
                AND length(replace(:assemblyJobId,'-',''))=32 AND :assemblyJobId NOT GLOB '*[^0-9a-f-]*')
            AND (typeof(:snapshotId)='text' AND length(CAST(:snapshotId AS BLOB))=36
                AND :snapshotId GLOB '????????-????-????-????-????????????'
                AND length(replace(:snapshotId,'-',''))=32 AND :snapshotId NOT GLOB '*[^0-9a-f-]*')
            AND (typeof(:artifactId)='text' AND length(CAST(:artifactId AS BLOB))=36
                AND :artifactId GLOB '????????-????-????-????-????????????'
                AND length(replace(:artifactId,'-',''))=32 AND :artifactId NOT GLOB '*[^0-9a-f-]*')
            AND (typeof(:transactionId)='text' AND length(CAST(:transactionId AS BLOB))=36
                AND :transactionId GLOB '????????-????-????-????-????????????'
                AND length(replace(:transactionId,'-',''))=32 AND :transactionId NOT GLOB '*[^0-9a-f-]*')""")
    internal abstract fun insertExportItemIntent(
        transactionId: String, assemblyJobId: String, snapshotId: String, artifactId: String, ordinal: Int, attempt: Long,
        payload: ByteArray, updatedAt: Long,
    ): Long

    // Expected key, paths, payload, phase and audit timestamp come from the owning caller, not from a row projection. The triplet is null for COPYING, newly proved for SEALED, and the exact expected old triplet for MOVED. Wall audit time is not a lease clock.
    // CASE keeps byte equality behind the raw type/extent checks in this same point update; WHERE/AND order is not a guard. No edge commits an artifact or grants file authority.
    @Query("""UPDATE FileTransaction SET state=:nextState, updatedAt=:updatedAt,
        sealSha256=CASE WHEN :nextState='SEALED' THEN :seal ELSE sealSha256 END,
        expectedByteSha256=CASE WHEN :nextState='SEALED' THEN :byteSha256 ELSE expectedByteSha256 END,
        expectedByteCount=CASE WHEN :nextState='SEALED' THEN :byteCount ELSE expectedByteCount END
        WHERE kind='EXPORT' COLLATE BINARY AND ownerId=:ownerKey COLLATE BINARY AND attempt=:attempt
            AND transactionId=:transactionId COLLATE BINARY AND state=:previousState COLLATE BINARY
            AND typeof(attempt)='integer' AND typeof(:attempt)='integer' AND :attempt>0
            AND typeof(updatedAt)='integer' AND typeof(:previousUpdatedAt)='integer' AND updatedAt=:previousUpdatedAt
            AND :previousUpdatedAt>=0 AND typeof(:updatedAt)='integer' AND :updatedAt>=0 AND errorCode IS NULL
            AND typeof(stagingPath)='text' AND stagingPath=:stagingPath COLLATE BINARY
            AND length(CAST(:stagingPath AS BLOB))=57 AND length(:stagingPath)=57
            AND typeof(finalPath)='text' AND finalPath=:finalPath COLLATE BINARY
            AND length(CAST(:finalPath AS BLOB))=85 AND length(:finalPath)=85
            AND CASE WHEN typeof(payload)='blob' AND length(payload)=685 AND typeof(:payload)='blob' AND length(:payload)=685
                THEN payload=:payload ELSE 0 END
            AND CASE
                WHEN state='INTENT' AND :nextState='COPYING'
                    THEN sealSha256 IS NULL AND expectedByteSha256 IS NULL AND expectedByteCount IS NULL
                        AND :seal IS NULL AND :byteSha256 IS NULL AND :byteCount IS NULL
                WHEN (state='COPYING' AND :nextState='SEALED') OR (state='SEALED' AND :nextState='MOVED')
                    THEN CASE WHEN typeof(:seal)='text' AND length(CAST(:seal AS BLOB))=64 AND length(:seal)=64
                        AND typeof(:byteSha256)='text' AND length(CAST(:byteSha256 AS BLOB))=64 AND length(:byteSha256)=64
                        AND typeof(:byteCount)='integer' AND :byteCount>0
                        AND :seal NOT GLOB '*[^0-9a-f]*' AND :byteSha256 NOT GLOB '*[^0-9a-f]*'
                        THEN CASE WHEN state='COPYING'
                            THEN sealSha256 IS NULL AND expectedByteSha256 IS NULL AND expectedByteCount IS NULL
                            ELSE typeof(sealSha256)='text' AND sealSha256=:seal COLLATE BINARY
                                AND typeof(expectedByteSha256)='text' AND expectedByteSha256=:byteSha256 COLLATE BINARY
                                AND typeof(expectedByteCount)='integer' AND expectedByteCount=:byteCount END
                        ELSE 0 END
                ELSE 0 END""")
    internal abstract fun advanceExportItemPhase(
        transactionId: String, ownerKey: String, attempt: Long, previousState: String, nextState: String,
        previousUpdatedAt: Long, updatedAt: Long, stagingPath: String, finalPath: String, payload: ByteArray,
        seal: String?, byteSha256: String?, byteCount: Long?,
    ): Int

    // This is not a file-phase edge. The later same-transaction owner must admit the raw row first, use its own exact expected tuple and require one affected row. Audit time may repeat or regress.
    @Query("""UPDATE FileTransaction SET state='COMMITTED', updatedAt=:updatedAt
        WHERE (transactionId=:transactionId COLLATE BINARY AND kind='EXPORT' COLLATE BINARY
            AND ownerId=:ownerKey COLLATE BINARY AND attempt=:attempt)
        AND (state='MOVED' COLLATE BINARY AND errorCode IS NULL)
        AND (typeof(:transactionId)='text' AND length(CAST(:transactionId AS BLOB))=36 AND length(:transactionId)=36)
        AND (typeof(:ownerKey)='text' AND length(CAST(:ownerKey AS BLOB)) BETWEEN 53 AND 54
            AND length(:ownerKey)=length(CAST(:ownerKey AS BLOB)))
        AND (typeof(attempt)='integer' AND typeof(:attempt)='integer' AND :attempt>0)
        AND (typeof(updatedAt)='integer' AND typeof(:previousUpdatedAt)='integer' AND updatedAt=:previousUpdatedAt
            AND :previousUpdatedAt>=0 AND typeof(:updatedAt)='integer' AND :updatedAt>=0)
        AND (typeof(stagingPath)='text' AND typeof(:stagingPath)='text' AND stagingPath=:stagingPath COLLATE BINARY
            AND length(CAST(:stagingPath AS BLOB))=57 AND length(:stagingPath)=57)
        AND (typeof(finalPath)='text' AND typeof(:finalPath)='text' AND finalPath=:finalPath COLLATE BINARY
            AND length(CAST(:finalPath AS BLOB))=85 AND length(:finalPath)=85)
        AND CASE WHEN typeof(payload)='blob' AND length(payload)=685 AND typeof(:payload)='blob' AND length(:payload)=685
            THEN payload=:payload ELSE 0 END
        AND CASE WHEN (typeof(:seal)='text' AND length(CAST(:seal AS BLOB))=64 AND length(:seal)=64)
            AND (typeof(:byteSha256)='text' AND length(CAST(:byteSha256 AS BLOB))=64 AND length(:byteSha256)=64)
            AND (typeof(:byteCount)='integer' AND :byteCount>0)
            THEN (typeof(sealSha256)='text' AND sealSha256=:seal COLLATE BINARY AND :seal NOT GLOB '*[^0-9a-f]*')
                AND (typeof(expectedByteSha256)='text' AND expectedByteSha256=:byteSha256 COLLATE BINARY AND :byteSha256 NOT GLOB '*[^0-9a-f]*')
                AND (typeof(expectedByteCount)='integer' AND expectedByteCount=:byteCount)
            ELSE 0 END""")
    internal abstract fun commitMovedExportItem(
        transactionId: String, ownerKey: String, attempt: Long, previousUpdatedAt: Long, updatedAt: Long,
        stagingPath: String, finalPath: String, payload: ByteArray, seal: String, byteSha256: String, byteCount: Long,
    ): Int

    // Fixed private PNG metadata, not provider readback. Invalid input inserts nothing and every existing unique key aborts; the owning transaction must postcheck the exact row.
    @Query("""INSERT OR ABORT INTO ExportArtifact (artifactId,snapshotId,ordinal,mime,relativePath,byteSha256,byteCount,pageCount,verification,state,createdAt)
        SELECT :artifactId,:snapshotId,:ordinal,'image/png','exports/' || :snapshotId || '/' || :artifactId || '.bin',
            :byteSha256,:byteCount,1,'ORDINARY_PNG_ENCODED_AND_RGB_V1','READY',:createdAt
        WHERE (typeof(:artifactId)='text' AND length(CAST(:artifactId AS BLOB))=36
            AND :artifactId GLOB '????????-????-????-????-????????????'
            AND length(replace(:artifactId,'-',''))=32 AND :artifactId NOT GLOB '*[^0-9a-f-]*')
        AND (typeof(:snapshotId)='text' AND length(CAST(:snapshotId AS BLOB))=36
            AND :snapshotId GLOB '????????-????-????-????-????????????'
            AND length(replace(:snapshotId,'-',''))=32 AND :snapshotId NOT GLOB '*[^0-9a-f-]*')
        AND (typeof(:byteSha256)='text' AND length(CAST(:byteSha256 AS BLOB))=64 AND length(:byteSha256)=64
            AND :byteSha256 NOT GLOB '*[^0-9a-f]*')
        AND (typeof(:ordinal)='integer' AND :ordinal BETWEEN 0 AND 99)
        AND (typeof(:byteCount)='integer' AND :byteCount>0)
        AND (typeof(:createdAt)='integer' AND :createdAt>=0)""")
    internal abstract fun insertReadyPngExportArtifact(
        artifactId: String, snapshotId: String, ordinal: Int, byteSha256: String, byteCount: Long, createdAt: Long,
    )

    // Bound the writer's expected values before the primary-key seek. The inner CASE bounds persisted values before comparing immutable fields; no artifact row is returned. Ordered n+1 inventory is a separate obligation.
    @Query("""SELECT CASE WHEN (typeof(:artifactId)='text' AND length(CAST(:artifactId AS BLOB))=36
            AND :artifactId GLOB '????????-????-????-????-????????????'
            AND length(replace(:artifactId,'-',''))=32 AND :artifactId NOT GLOB '*[^0-9a-f-]*')
        AND (typeof(:snapshotId)='text' AND length(CAST(:snapshotId AS BLOB))=36
            AND :snapshotId GLOB '????????-????-????-????-????????????'
            AND length(replace(:snapshotId,'-',''))=32 AND :snapshotId NOT GLOB '*[^0-9a-f-]*')
        AND (typeof(:byteSha256)='text' AND length(CAST(:byteSha256 AS BLOB))=64 AND length(:byteSha256)=64
            AND :byteSha256 NOT GLOB '*[^0-9a-f]*')
        AND (typeof(:ordinal)='integer' AND :ordinal BETWEEN 0 AND 99)
        AND (typeof(:byteCount)='integer' AND :byteCount>0)
        AND (typeof(:createdAt)='integer' AND :createdAt>=0)
        THEN EXISTS(SELECT 1 FROM ExportArtifact WHERE artifactId=:artifactId COLLATE BINARY
            AND CASE WHEN (typeof(snapshotId)='text' AND length(CAST(snapshotId AS BLOB))=36)
                AND (typeof(relativePath)='text' AND length(CAST(relativePath AS BLOB))=85)
                AND (typeof(byteSha256)='text' AND length(CAST(byteSha256 AS BLOB))=64)
                AND (typeof(mime)='text' AND length(CAST(mime AS BLOB))=9)
                AND (typeof(verification)='text' AND length(CAST(verification AS BLOB))=31)
                AND (typeof(state)='text' AND length(CAST(state AS BLOB))=5)
                AND (typeof(ordinal)='integer' AND typeof(byteCount)='integer'
                    AND typeof(pageCount)='integer' AND typeof(createdAt)='integer')
                THEN (snapshotId=:snapshotId COLLATE BINARY AND ordinal=:ordinal
                    AND relativePath=('exports/' || :snapshotId || '/' || :artifactId || '.bin') COLLATE BINARY)
                AND (byteSha256=:byteSha256 COLLATE BINARY AND byteCount=:byteCount AND createdAt=:createdAt)
                AND (mime='image/png' COLLATE BINARY AND pageCount=1 AND verification='ORDINARY_PNG_ENCODED_AND_RGB_V1' COLLATE BINARY
                    AND state='READY' COLLATE BINARY)
                ELSE 0 END)
        ELSE 0 END""")
    internal abstract fun readyPngExportArtifactMatches(
        artifactId: String, snapshotId: String, ordinal: Int, byteSha256: String, byteCount: Long, createdAt: Long,
    ): Boolean

    // Header CAS only. The future same-database writer must recheck the full frozen set and retained live lease in its one transaction, and not reuse a RUNNING/FROZEN fence after its own success writes.
    @Query("""UPDATE ExportSnapshot SET state='PRIVATE_READY'
        WHERE (snapshotId=:snapshotId COLLATE BINARY AND state='FROZEN' COLLATE BINARY)
        AND (typeof(:snapshotId)='text' AND length(CAST(:snapshotId AS BLOB))=36
            AND :snapshotId GLOB '????????-????-????-????-????????????'
            AND length(replace(:snapshotId,'-',''))=32 AND :snapshotId NOT GLOB '*[^0-9a-f-]*')
        AND (typeof(:documentId)='text' AND length(CAST(:documentId AS BLOB))=36
            AND :documentId GLOB '????????-????-????-????-????????????'
            AND length(replace(:documentId,'-',''))=32 AND :documentId NOT GLOB '*[^0-9a-f-]*'
            AND typeof(documentId)='text' AND documentId=:documentId COLLATE BINARY)
        AND (typeof(:snapshotSha256)='text' AND length(CAST(:snapshotSha256 AS BLOB))=64 AND length(:snapshotSha256)=64
            AND :snapshotSha256 NOT GLOB '*[^0-9a-f]*'
            AND typeof(snapshotSha256)='text' AND snapshotSha256=:snapshotSha256 COLLATE BINARY)
        AND (typeof(documentRevision)='integer' AND typeof(:documentRevision)='integer' AND :documentRevision>0 AND documentRevision=:documentRevision)
        AND (typeof(createdAt)='integer' AND typeof(:createdAt)='integer' AND :createdAt>=0 AND createdAt=:createdAt)
        AND (typeof(retainedUntil)='integer' AND typeof(:retainedUntil)='integer' AND :retainedUntil>0 AND retainedUntil=:retainedUntil)
        AND (typeof(:now)='integer' AND :now>=0 AND :now<:retainedUntil)
        AND CASE WHEN typeof(settings)='blob' AND length(settings) BETWEEN 1 AND 524288
            AND typeof(:settings)='blob' AND length(:settings) BETWEEN 1 AND 524288
            THEN settings=:settings ELSE 0 END""")
    internal abstract fun markOrdinaryPngSnapshotPrivateReady(
        snapshotId: String, documentId: String, documentRevision: Long, snapshotSha256: String,
        settings: ByteArray, createdAt: Long, retainedUntil: Long, now: Long,
    ): Int

    // Preserve every row for n+1 detection, including malformed members. Validate byte and character extents before inspecting UUID shape; '' is a bounded invalid ID, not absence. Invalid limits become zero, never SQLite's negative unlimited limit.
    @Query("""SELECT CASE WHEN (typeof(artifactId)='text' AND length(CAST(artifactId AS BLOB))=36 AND length(artifactId)=36)
        THEN CASE WHEN (substr(artifactId,9,1)='-' AND substr(artifactId,14,1)='-'
            AND substr(artifactId,19,1)='-' AND substr(artifactId,24,1)='-')
            AND (length(replace(artifactId,'-',''))=32 AND replace(artifactId,'-','') NOT GLOB '*[^0-9a-f]*')
        THEN artifactId ELSE '' END ELSE '' END AS artifactId
        FROM ExportArtifact WHERE snapshotId=:snapshotId ORDER BY ordinal
        LIMIT CASE WHEN :limit BETWEEN 1 AND 101 THEN :limit ELSE 0 END""")
    internal abstract fun exportArtifactIdsForSnapshot(snapshotId: String, limit: Int): List<String>

    // Only on a create-path exact-key miss. Scan the existing (kind, ownerId) index range; never extract a malformed key or its payload.
    @Query("""SELECT EXISTS(SELECT 1 FROM FileTransaction WHERE kind='RENDER' AND ownerId=:jobId
        AND (typeof(attempt)!='integer' OR attempt<=0))""")
    abstract override fun renderIntentHasMalformedAttempts(jobId: String): Boolean

    @Query("SELECT * FROM FileTransaction WHERE kind='RENDER' AND ownerId=:jobId AND attempt=:attempt")
    abstract override fun renderIntentRow(jobId: String, attempt: Long): FileTransactionRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract override fun insertRenderIntent(row: FileTransactionRow)

    @Query("UPDATE FileTransaction SET state='COPYING', updatedAt=:updatedAt WHERE transactionId=:transactionId AND kind='RENDER' AND ownerId=:jobId AND attempt=:attempt AND state='INTENT'")
    abstract override fun markRenderIntentCopying(transactionId: String, jobId: String, attempt: Long, updatedAt: Long): Int

    // Same-transaction raw admission and typed checking precede this exact CAS. Only the first seal can set the triplet; MOVED must retain it verbatim.
    @Query("""UPDATE FileTransaction SET state=:nextState, sealSha256=:seal, expectedByteSha256=:hash, expectedByteCount=:count, updatedAt=:updatedAt
        WHERE transactionId=:transactionId AND kind='RENDER' AND ownerId=:jobId AND attempt=:attempt
        AND (state=:previousState AND updatedAt=:previousUpdatedAt AND errorCode IS NULL)
        AND (stagingPath=:stagingPath AND finalPath=:finalPath AND payload=:payload)
        AND (sealSha256 IS :previousSeal AND expectedByteSha256 IS :previousHash AND expectedByteCount IS :previousCount)
        AND (
            ((state='INTENT' OR state='COPYING') AND (sealSha256 IS NULL AND expectedByteSha256 IS NULL AND expectedByteCount IS NULL)
                AND ((state='INTENT' AND :nextState='COPYING' AND (:seal IS NULL AND :hash IS NULL AND :count IS NULL))
                  OR (state='COPYING' AND :nextState='SEALED' AND (:seal IS NOT NULL AND :hash IS NOT NULL AND :count>0))))
            OR (state='SEALED' AND :nextState='MOVED' AND (sealSha256=:seal AND expectedByteSha256=:hash AND expectedByteCount=:count AND :count>0))
        )""")
    internal abstract fun advanceRenderFilePhase(
        transactionId: String, jobId: String, attempt: Long, previousState: String,
        stagingPath: String?, finalPath: String?, payload: ByteArray, previousUpdatedAt: Long, updatedAt: Long, nextState: String,
        previousSeal: String?, previousHash: String?, previousCount: Long?, seal: String?, hash: String?, count: Long?,
    ): Int

    override fun advanceRenderFilePhase(previous: FileTransactionRow, next: FileTransactionRow): Int =
        advanceRenderFilePhase(previous.transactionId, previous.ownerId, previous.attempt, previous.state,
            previous.stagingPath, previous.finalPath, previous.payload, previous.updatedAt, next.updatedAt, next.state,
            previous.sealSha256, previous.expectedByteSha256, previous.expectedByteCount,
            next.sealSha256, next.expectedByteSha256, next.expectedByteCount)

    // Completion is deliberately not an additional file-executor phase edge. The separate join controls when this CAS can run.
    @Query("""UPDATE FileTransaction SET state='COMMITTED', updatedAt=:updatedAt
        WHERE transactionId=:transactionId AND kind='RENDER' AND ownerId=:jobId AND attempt=:attempt
        AND (state='MOVED' AND updatedAt=:previousUpdatedAt AND errorCode IS NULL)
        AND (stagingPath=:stagingPath AND finalPath=:finalPath AND payload=:payload)
        AND (sealSha256=:seal AND expectedByteSha256=:hash AND expectedByteCount=:count AND :count>0)""")
    internal abstract fun commitMovedRender(
        transactionId: String, jobId: String, attempt: Long, stagingPath: String?, finalPath: String?,
        payload: ByteArray, previousUpdatedAt: Long, updatedAt: Long, seal: String?, hash: String?, count: Long?,
    ): Int

    // Each EXISTS uses an existing key/index and returns only a scalar, even if the colliding row is malformed or huge.
    @Query("""SELECT EXISTS(SELECT 1 FROM RenderedPage WHERE renderId=:renderId)
        OR EXISTS(SELECT 1 FROM RenderedPage WHERE jobId=:jobId)
        OR EXISTS(SELECT 1 FROM RenderedPage WHERE cacheKey=:cacheKey)
        OR EXISTS(SELECT 1 FROM RenderedPage WHERE relativePath=:relativePath)""")
    internal abstract fun renderCompletionOccupied(renderId: String, jobId: String, cacheKey: String, relativePath: String): Boolean

    @Query("""INSERT INTO RenderedPage (renderId,jobId,sourceId,pageId,recipeRevision,cacheKey,execution,executionSha256,
        relativePath,byteSha256,rasterPixelSha256,byteCount,width,height,renderStatus,state)
        VALUES (:renderId,:jobId,:sourceId,:pageId,:recipeRevision,:cacheKey,:execution,:executionSha256,
        :relativePath,:byteSha256,:rasterPixelSha256,:byteCount,:width,:height,'OK','READY')""")
    internal abstract fun insertReadyRender(
        renderId: String, jobId: String, sourceId: String, pageId: String, recipeRevision: Long, cacheKey: String,
        execution: ByteArray, executionSha256: String, relativePath: String, byteSha256: String, rasterPixelSha256: String,
        byteCount: Long, width: Int, height: Int,
    )

    @Query("""SELECT EXISTS(SELECT 1 FROM RenderedPage WHERE renderId=:renderId
        AND (jobId=:jobId AND sourceId=:sourceId AND pageId=:pageId AND cacheKey=:cacheKey)
        AND (typeof(recipeRevision)='integer' AND recipeRevision=:recipeRevision)
        AND (typeof(execution)='blob' AND execution=:execution AND executionSha256=:executionSha256)
        AND (relativePath=:relativePath AND byteSha256=:byteSha256 AND rasterPixelSha256=:rasterPixelSha256)
        AND (typeof(byteCount)='integer' AND byteCount=:byteCount)
        AND (typeof(width)='integer' AND width=:width AND typeof(height)='integer' AND height=:height)
        AND (renderStatus='OK' AND state='READY'))
        AND NOT EXISTS(SELECT 1 FROM RenderedPage WHERE jobId=:jobId AND renderId<>:renderId)""")
    internal abstract fun readyRenderMatches(
        renderId: String, jobId: String, sourceId: String, pageId: String, recipeRevision: Long, cacheKey: String,
        execution: ByteArray, executionSha256: String, relativePath: String, byteSha256: String, rasterPixelSha256: String,
        byteCount: Long, width: Int, height: Int,
    ): Boolean

    internal fun renderCompletionQueries(): RenderCompletionQueries = object : RenderCompletionQueries {
        override fun renderCompletionOccupied(renderId: String, jobId: String, cacheKey: String, relativePath: String): Boolean =
            this@ExportSnapshotDao.renderCompletionOccupied(renderId, jobId, cacheKey, relativePath)

        override fun commitMovedRender(previous: FileTransactionRow, updatedAt: Long): Int =
            commitMovedRender(previous.transactionId, previous.ownerId, previous.attempt, previous.stagingPath, previous.finalPath,
                previous.payload, previous.updatedAt, updatedAt, previous.sealSha256, previous.expectedByteSha256, previous.expectedByteCount)

        override fun insertReadyRender(row: RenderedPageRow) =
            insertReadyRender(row.renderId, row.jobId, row.sourceId, row.pageId, row.recipeRevision, row.cacheKey, row.execution,
                row.executionSha256, row.relativePath, row.byteSha256, row.rasterPixelSha256, row.byteCount, row.width, row.height)

        override fun readyRenderMatches(row: RenderedPageRow): Boolean =
            readyRenderMatches(row.renderId, row.jobId, row.sourceId, row.pageId, row.recipeRevision, row.cacheKey, row.execution,
                row.executionSha256, row.relativePath, row.byteSha256, row.rasterPixelSha256, row.byteCount, row.width, row.height)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertExportJob(row: JobRow)
}
