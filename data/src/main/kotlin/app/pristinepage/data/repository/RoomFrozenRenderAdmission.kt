package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.ExportPageSnapshot
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryExportProfile
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** First ordinary PNG admission only. The caller supplies a verified assembly reference and page at the same ordinal and retains their owners. */
internal class RoomFrozenRenderAdmission(
    private val metadata: MetadataAccess,
    private val runtime: OrdinaryExportProfile,
    private val preflight: suspend (ExportPageSnapshot, ExportSourceBinding) -> OperationError?,
    jobBounds: (String, Int) -> Boolean?,
    jobIdentity: (String) -> FrozenRenderJobIdentityRow?,
    pinSourceIds: (String) -> List<String>,
    sourceBounds: (String) -> Boolean?,
    private val admitPending: (String, String, String, ByteArray, Long) -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        database: PristineDatabase,
        runtime: OrdinaryExportProfile,
        preflight: suspend (ExportPageSnapshot, ExportSourceBinding) -> OperationError?,
    ) : this(
        RoomMetadataAccess(database), runtime, preflight,
        database.exportSnapshots()::renderJobReadIsBounded,
        database.exportSnapshots()::renderJobIdentity,
        database.exportSnapshots()::boundedRenderJobPinSourceIds,
        database.catalog()::sourceReadIsBounded,
        database.exportSnapshots()::admitPendingRender,
    )

    private val projection = RoomFrozenRenderReads.Projection(metadata.queries, jobBounds, jobIdentity, pinSourceIds, sourceBounds)

    suspend fun admit(jobId: JobId, page: ExportPageSnapshot): Boolean = withContext(dispatcher) {
        val active: () -> Unit = { coroutineContext.ensureActive() }
        active()
        val prepared = RoomFrozenRenderReads.Verified.Prepared(jobId, page, runtime)
        val initial = transaction(jobId) {
            active()
            projection.job(jobId, prepared.inputBytes, active).detached()
        }
        active()
        prepared.authenticate(initial, active)

        // No transaction or mutable job bytes cross into the external capability/resource check; success is not a reservation.
        val refusal = preflight(page, initial.binding)
        active()
        refusal?.let { throw RepositoryFailure(it) }
        val updatedAt = clock()
        active()

        transaction(jobId) {
            active()
            val live = projection.job(jobId, prepared.inputBytes, active)
            // Cancellation is checked immediately before the CAS, not guaranteed against a race with a started commit.
            when (prepared.admitUnchanged(initial, live, updatedAt, active, admitPending)) {
                0 -> false
                1 -> true
                else -> throw RepositoryFailure(OperationError(
                    OperationErrorCode.INTEGRITY_FAILED, false, "frozen_render_admission_count_invalid", jobId.value,
                ))
            }
        }
    }

    private fun <T> transaction(jobId: JobId, action: () -> T): T = try {
        metadata.transaction(action)
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (failure: RepositoryFailure) {
        throw failure
    } catch (_: Exception) {
        throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "frozen_render_admission_storage_failed", jobId.value))
    }
}
