package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.model.ExportSnapshot
import app.pristinepage.data.model.ExportSnapshotId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure

/** A foreground render-and-encode job. The snapshot hash authenticates its ordered inputs; there are no promised child render jobs. */
internal object NamedAlbumJobInput {
    const val KIND = "ALBUM_ARTIFACT"
    fun key(id: ExportSnapshotId) = "named-album-out-v1/${id.value}"

    fun encode(snapshot: ExportSnapshot): ByteArray {
        require(snapshot.settings.albumScope != null)
        requireSha256(snapshot.snapshotHash)
        return "pristinepage-named-album-out-v1\u0000${snapshot.snapshotId.value}\u0000${snapshot.snapshotHash}".encodeToByteArray()
    }

    fun verify(id: JobId, snapshot: ExportSnapshot, row: AssemblyJobIdentityRow) {
        val bytes = encode(snapshot)
        if (row.jobId != id.value || row.kind != KIND || row.idempotenceKey != key(snapshot.snapshotId) ||
            row.snapshotId != snapshot.snapshotId.value || row.canonicalInputSha256 != ExportAssemblyInput.digest(bytes) ||
            !row.input.contentEquals(bytes)
        ) throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "named_export_job_input_invalid", id.value))
    }
}
