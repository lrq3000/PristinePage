package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.ExportSnapshot
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class AssemblyJobIdentityRow(
    val jobId: String,
    val kind: String,
    val idempotenceKey: String,
    val canonicalInputSha256: String,
    val snapshotId: String?,
    val input: ByteArray,
)

/** Read immutable intent only. The caller supplies a separately authenticated snapshot; neither the lookup nor the decoded references prove current state, lease authority, or referenced job validity. */
internal class RoomExportAssemblyReads(
    private val metadata: MetadataAccess,
    private val bounds: (String) -> Boolean?,
    private val identity: (String) -> AssemblyJobIdentityRow?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val jobIdByKey: (String) -> String? = { error("Assembly job key lookup is not configured") },
) {
    constructor(database: PristineDatabase) : this(
        RoomMetadataAccess(database),
        database.exportSnapshots()::assemblyJobReadIsBounded,
        database.exportSnapshots()::assemblyJobIdentity,
        jobIdByKey = database.exportSnapshots()::renderJobIdByKey,
    )

    suspend fun load(jobId: JobId, snapshot: ExportSnapshot): List<JobId> =
        read(snapshot, jobId).second

    suspend fun loadForSnapshot(snapshot: ExportSnapshot, namedAlbum: Boolean = false): Pair<JobId, List<JobId>> =
        read(snapshot, requestedJobId = null, namedAlbum)

    private suspend fun read(snapshot: ExportSnapshot, requestedJobId: JobId?, namedAlbum: Boolean = false): Pair<JobId, List<JobId>> = withContext(dispatcher) {
        coroutineContext.ensureActive()
        var affectedId = requestedJobId?.value ?: snapshot.snapshotId.value
        val (jobId, projection) = try {
            metadata.transaction {
                val jobId = requestedJobId ?: run {
                    val key = if (namedAlbum) NamedAlbumJobInput.key(snapshot.snapshotId) else ExportAssemblyInput.key(snapshot.snapshotId)
                    val matchedId = jobIdByKey(key)
                    coroutineContext.ensureActive()
                    if (matchedId == null) unavailable(affectedId)
                    try {
                        JobId(matchedId)
                    } catch (_: IllegalArgumentException) {
                        invalidMetadata(affectedId)
                    }
                }
                affectedId = jobId.value
                when (bounds(jobId.value)) {
                    null -> {
                        // Only the initial lookup can establish absence; a key hit followed by a guard miss is inconsistent.
                        if (requestedJobId != null) unavailable(affectedId)
                        invalidMetadata(affectedId)
                    }
                    false -> invalidMetadata(affectedId)
                    true -> Unit
                }
                coroutineContext.ensureActive()
                jobId to (identity(jobId.value) ?: invalidMetadata(affectedId))
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            throw failure
        } catch (_: Exception) {
            refuse(affectedId, OperationErrorCode.STORAGE_FAILED, "export_assembly_job_read_failed", retryable = true)
        }
        // Hash/parse only after releasing Room's transaction on the bounded, materialized projection.
        coroutineContext.ensureActive()
        val references = if (namedAlbum) {
            NamedAlbumJobInput.verify(jobId, snapshot, projection)
            emptyList()
        } else ExportAssemblyInput.verifyProjection(jobId, snapshot, projection)
        coroutineContext.ensureActive()
        jobId to references
    }

    private fun unavailable(affectedId: String): Nothing =
        refuse(affectedId, OperationErrorCode.SOURCE_UNAVAILABLE, "export_assembly_job_unavailable")

    private fun invalidMetadata(affectedId: String): Nothing =
        refuse(affectedId, OperationErrorCode.INTEGRITY_FAILED, "export_assembly_job_metadata_invalid")

    private fun refuse(affectedId: String, code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
        throw RepositoryFailure(OperationError(code, retryable, key, affectedId))
}
