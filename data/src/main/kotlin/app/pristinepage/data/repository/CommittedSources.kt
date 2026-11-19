package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.model.SourceBlobId
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.model.SourceInspection
import java.io.File

/** Private immutable original plus its recorded normalization, not a page/current-recipe view. This value is process-private, never serialized into UI messages, WorkManager Data or external URIs. Caller retains an existing page/history/job/snapshot owner through consumption; loading does not acquire a GC lease or authorize reclamation. */
class CommittedSource(val sourceId: SourceId, val sourceBlobId: SourceBlobId, val sourceByteHash: String, val byteCount: Long, val inspection: SourceInspection, val file: File) {
    init { requireSha256(sourceByteHash); require(byteCount in 1..104857600L) }
}

fun interface CommittedSources {
    suspend fun load(sourceId: SourceId): CommittedSource
}

/** Read the exact admitted JOB original through this same store. The caller retains its admission/acquisition and source ownership; the expected rows alone do not authorize a file read. */
fun interface ProducerCommittedSources {
    suspend fun loadForProducer(sourceId: SourceId, expected: ExportSourceBinding): CommittedSource
}

/** Read in the same metadata transaction BEFORE materializing Source/SourceBlob rows. Null is an absent SourceId; false covers malformed raw types/ranges/lengths or a missing/not-READY backing blob. The scalar must not hide SQLite64-bit values behind generated Int projections. */
fun interface CommittedSourceQueries {
    fun sourceReadIsBounded(id: String): Boolean?
}
