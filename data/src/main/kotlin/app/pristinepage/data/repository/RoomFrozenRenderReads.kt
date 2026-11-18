package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.ExportPageSnapshot
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryExportProfile
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.requireOpaqueId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Verifies one ordinary authenticated frozen page and its JobId at the matching verified assembly ordinal. The caller retains the snapshot/job ownership and separately proves lease, runtime and file availability; no rows are returned. */
internal class RoomFrozenRenderReads(
    private val metadata: MetadataAccess,
    private val jobBounds: (String, Int) -> Boolean?,
    private val jobIdentity: (String) -> FrozenRenderJobIdentityRow?,
    private val pinSourceIds: (String) -> List<String>,
    private val sourceBounds: (String) -> Boolean?,
    private val runtime: OrdinaryExportProfile,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(database: PristineDatabase, runtime: OrdinaryExportProfile) : this(
        RoomMetadataAccess(database),
        database.exportSnapshots()::renderJobReadIsBounded,
        database.exportSnapshots()::renderJobIdentity,
        database.exportSnapshots()::boundedRenderJobPinSourceIds,
        database.catalog()::sourceReadIsBounded,
        runtime,
    )

    suspend fun verify(jobId: JobId, page: ExportPageSnapshot): Unit = withContext(dispatcher) {
        coroutineContext.ensureActive()
        val expected = FrozenRenderJobProof.prepare(jobId, page, runtime)
        coroutineContext.ensureActive()
        val (job, pinId, binding) = try {
            metadata.transaction {
                when (jobBounds(jobId.value, expected.inputBytes)) {
                    null -> refuse(jobId, OperationErrorCode.SOURCE_UNAVAILABLE, "frozen_render_job_unavailable")
                    false -> invalidMetadata(jobId)
                    true -> Unit
                }
                coroutineContext.ensureActive()
                val row = jobIdentity(jobId.value) ?: invalidMetadata(jobId)
                val pin = pinSourceIds(jobId.value).singleOrNull() ?: invalidMetadata(jobId)
                validateId(pin, jobId)
                // A missing Source behind an existing JOB pin is corruption, not an absent job. The reused scalar also guards the corresponding SourceBlob before either projection.
                if (sourceBounds(pin) != true) invalidMetadata(jobId)
                coroutineContext.ensureActive()
                val source = metadata.queries.source(pin) ?: invalidMetadata(jobId)
                if (source.sourceId != pin) invalidMetadata(jobId)
                validateId(source.sourceBlobId, jobId)
                val blob = metadata.queries.sourceBlob(source.sourceBlobId) ?: invalidMetadata(jobId)
                Triple(row, pin, ExportSourceBinding(source, blob))
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: RepositoryFailure) {
            throw failure
        } catch (_: Exception) {
            refuse(jobId, OperationErrorCode.STORAGE_FAILED, "frozen_render_job_read_failed", retryable = true)
        }
        coroutineContext.ensureActive()
        expected.verify(job, pinId, binding)
        coroutineContext.ensureActive()
        // Mismatches in the retained job/pin chain must not be hidden by a runtime difference.
        if (expected.historical) refuse(jobId, OperationErrorCode.PROVIDER_UNAVAILABLE, "frozen_render_profile_unavailable")
    }

    private fun validateId(id: String, jobId: JobId) {
        try {
            requireOpaqueId(id)
        } catch (_: IllegalArgumentException) {
            invalidMetadata(jobId)
        }
    }

    private fun invalidMetadata(jobId: JobId): Nothing =
        refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "frozen_render_job_metadata_invalid")

    private fun refuse(jobId: JobId, code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
        throw RepositoryFailure(OperationError(code, retryable, key, jobId.value))
}
