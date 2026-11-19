package app.pristinepage.data.repository

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.data.model.ExportPageSnapshot
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryRenderExecution
import app.pristinepage.data.model.RenderId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.RoomJobLeases.FrozenRenderAcquisition
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Fresh production only. The caller retains the authenticated PNG snapshot and matching ordered job reference; all participants belong to one stable store. */
internal class RoomOrdinaryPngPageService(
    private val owners: FileOperationOwners,
    private val newAdmission: (suspend (ExportPageSnapshot, ExportSourceBinding) -> OperationError?) -> RoomFrozenRenderAdmission,
    private val acquisition: FrozenRenderAcquisition,
    private val intents: RoomRenderIntents,
    private val newCompletion: () -> RoomRenderCompletion,
    private val leaseDurationMillis: Long,
) {
    init {
        require(leaseDurationMillis in 1..86_400_000L)
    }

    /** Load this exact JOB original in the shared renderer, never by unbound source ID. Transfer the whole result on delivery; close it if delivery is canceled inside the adapter. */
    fun interface Renderer {
        suspend fun render(request: RenderRequest, original: ExportSourceBinding): RenderResult
    }

    /** Codec ports borrow only their raster, sink or closed file and checkpoint, synchronously, without retaining them. This returns a private render ID, not an artifact or terminal reuse proof. */
    suspend fun produce(
        jobId: JobId,
        page: ExportPageSnapshot,
        maxBytes: Long,
        preflight: suspend (ExportPageSnapshot, ExportSourceBinding, Long) -> OperationError?,
        renderer: Renderer,
        writer: (RgbImage, OutputStream, () -> Unit) -> Unit,
        readback: (File, RgbImage, Long, () -> Unit) -> String?,
    ): RenderId {
        currentCoroutineContext().ensureActive()
        if (maxBytes <= 0) refuse(jobId, OperationErrorCode.INVALID_INPUT, "ordinary_png_invalid_cap")
        val admitted = newAdmission { frozen, original -> preflight(frozen, original, maxBytes) }.admit(jobId, page)
            ?: refuse(jobId, OperationErrorCode.REVISION_CONFLICT, "ordinary_png_admission_missed")

        return owners.run { owner ->
            val acquired = when (val outcome = acquisition.acquireForFreshProducer(admitted, owner, leaseDurationMillis)) {
                is FrozenRenderAcquisition.Acquired -> outcome
                is FrozenRenderAcquisition.ProducerResult.Busy ->
                    refuse(jobId, OperationErrorCode.REVISION_CONFLICT, "ordinary_png_lease_busy", retryable = true)
                is FrozenRenderAcquisition.ProducerResult.Rejected -> throw RepositoryFailure(outcome.error)
            }
            val original = acquired.proof.pinnedBinding
            val request = try {
                OrdinaryRenderExecution.requestFor(acquired.proof.page, SourceId(original.source.sourceId))
            } catch (_: IllegalArgumentException) {
                refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "render_execution_invalid")
            }

            try {
                currentCoroutineContext().ensureActive()
                // No dispatcher crossing between delivery and taking ownership. Even a refused or canceled result closes before the live owner ends.
                renderer.render(request, original).use { result ->
                    currentCoroutineContext().ensureActive()
                    if (result.request != request) refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "render_execution_invalid")
                    if (result.status != RenderStatus.OK) refuse(jobId, result.status.refusalCode(), "ordinary_png_render_refused")
                    val image = result.image
                    if (image == null || image.width != request.output.size.width || image.height != request.output.size.height) {
                        refuse(jobId, OperationErrorCode.INTEGRITY_FAILED, "render_execution_invalid")
                    }
                    val intent = intents.prepareForProducer(acquired)
                    // The existing completion validates the complete execution before granting COPY. Failure or uncertain delivery never authorizes another encode.
                    newCompletion().completeForProducerWithCheckpoint(acquired, intent, result, maxBytes,
                        writer = { sink, checkpoint -> writer(image, sink, checkpoint) },
                        readback = { file, checkpoint -> readback(file, image, maxBytes, checkpoint) })
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: RepositoryFailure) {
                throw failure
            } catch (_: OutOfMemoryError) {
                refuse(jobId, OperationErrorCode.RESOURCE_LIMIT, "ordinary_png_allocation_failed")
            } catch (_: Exception) {
                refuse(jobId, OperationErrorCode.PROCESSING_FAILED, "ordinary_png_result_failed")
            }
        }
    }

    private fun RenderStatus.refusalCode(): OperationErrorCode = when (this) {
        RenderStatus.OK -> error("Successful result is not a refusal")
        RenderStatus.DEGRADED, RenderStatus.PROCESSING_FAILED -> OperationErrorCode.PROCESSING_FAILED
        RenderStatus.INVALID_INPUT -> OperationErrorCode.INVALID_INPUT
        RenderStatus.UNAVAILABLE -> OperationErrorCode.PROVIDER_UNAVAILABLE
        RenderStatus.INVALID_MAP -> OperationErrorCode.INVALID_MAP
        RenderStatus.CANCELED -> OperationErrorCode.CANCELED
        RenderStatus.RESOURCE_LIMIT -> OperationErrorCode.RESOURCE_LIMIT
        RenderStatus.STORAGE_FAILED -> OperationErrorCode.STORAGE_FAILED
    }

    private fun refuse(jobId: JobId, code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
        throw RepositoryFailure(OperationError(code, retryable, key, jobId.value))
}
