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
) {
    constructor(database: PristineDatabase) : this(
        RoomMetadataAccess(database),
        database.exportSnapshots()::assemblyJobReadIsBounded,
        database.exportSnapshots()::assemblyJobIdentity,
    )

    suspend fun load(jobId: JobId, snapshot: ExportSnapshot): List<JobId> = withContext(dispatcher) {
        coroutineContext.ensureActive()
        val projection = try {
            metadata.transaction {
                when (bounds(jobId.value)) {
                    null -> refuse(jobId, OperationErrorCode.SOURCE_UNAVAILABLE, "export_assembly_job_unavailable")
                    false -> invalidMetadata(jobId)
                    true -> Unit
                }
                coroutineContext.ensureActive()
                identity(jobId.value) ?: invalidMetadata(jobId)
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            throw failure
        } catch (_: Exception) {
            refuse(jobId, OperationErrorCode.STORAGE_FAILED, "export_assembly_job_read_failed", retryable = true)
        }
        // Hash/parse only after releasing Room's transaction on the bounded, materialized projection.
        coroutineContext.ensureActive()
        val references = ExportAssemblyInput.verifyProjection(jobId, snapshot, projection)
        coroutineContext.ensureActive()
        references
    }

    private fun invalidMetadata(jobId: JobId): Nothing =
        refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "export_assembly_job_metadata_invalid")

    private fun refuse(jobId: JobId, code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
        throw RepositoryFailure(OperationError(code, retryable, key, jobId.value))
}
