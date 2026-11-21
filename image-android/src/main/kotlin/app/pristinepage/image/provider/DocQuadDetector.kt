package app.pristinepage.image.provider

import app.pristinepage.core.geometry.DocQuadGeometry
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.DetectionProposal
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** The selected runtime validates named shapes and snapshots FLOAT32 planes before closing or reusing its native outputs. */
internal fun interface DocQuadInference {
    fun run(asset: Path, inputChw: FloatArray, checkpoint: () -> Unit): DocQuadHeads
}

internal class DocQuadHeads(maskLogits: FloatArray, cornerHeatmaps: FloatArray) {
    private val mask: FloatArray
    private val corners: FloatArray

    init {
        if (maskLogits.size != 64 * 64 || cornerHeatmaps.size != 4 * 64 * 64) {
            throw DocQuadInferenceFailure(DocQuadInferenceFailure.Reason.INVALID_TENSOR)
        }
        // Own a bounded snapshot while the runtime's inputs are stable, rather than re-reading its scratch after validation.
        mask = maskLogits.copyOf()
        corners = cornerHeatmaps.copyOf()
    }

    fun propose(source: PixelSize, checkpoint: () -> Unit): DetectionProposal =
        DocQuadGeometry.propose(source, mask, corners, checkpoint)
}

/** Runtime adapters classify a refused session or output without leaking runtime paths, tensors, or raw exceptions to the UI. */
internal class DocQuadInferenceFailure(val reason: Reason) : RuntimeException() {
    enum class Reason { DETECTOR_UNAVAILABLE, INVALID_TENSOR, INFERENCE_FAILED }
}

/**
 * One application-owned detector, called on a worker thread with an oriented RGB lease held by the caller.
 * It only proposes corners. A non-null peak estimate must come from qualification of the exact model/backend and include tensor and snapshot copies.
 */
internal class DocQuadDetector(
    private val assets: ModelAssetStore,
    private val spec: ModelAssetSpec,
    private val budget: ImageAllocationBudget,
    private val inference: DocQuadInference?,
) {
    private val running = AtomicBoolean(false)

    fun detect(rgb: RgbImage, checkpoint: () -> Unit = {}): DetectionProposal {
        checkpoint()
        if (rgb.width < 2 || rgb.height < 2) return refuse("INVALID_GEOMETRY")
        val native = inference ?: return refuse("DETECTOR_UNAVAILABLE")
        val peak = spec.peakInferenceBytes ?: return refuse("DETECTOR_UNAVAILABLE")
        if (!running.compareAndSet(false, true)) return refuse("RESOURCE_LIMIT")
        try {
            assets.acquire(spec).use { asset ->
                budget.reserve(peak).use {
                    checkpoint()
                    val input = DocQuadGeometry.preprocess(rgb, checkpoint)
                    val output = native.run(asset.path, input, checkpoint)
                    checkpoint() // A blocking native call may finish after cancellation; its result stays private.
                    return output.propose(PixelSize(rgb.width, rgb.height), checkpoint)
                }
            }
        } catch (failure: RepositoryFailure) {
            if (failure.error.code != OperationErrorCode.RESOURCE_LIMIT) throw failure
            return checkedRefusal("RESOURCE_LIMIT", checkpoint)
        } catch (failure: DocQuadInferenceFailure) {
            return checkedRefusal(failure.reason.name, checkpoint)
        } catch (_: ModelAssetException) {
            return checkedRefusal("DETECTOR_UNAVAILABLE", checkpoint)
        } catch (_: IOException) {
            return checkedRefusal("STORAGE_FAILED", checkpoint)
        } catch (_: OutOfMemoryError) {
            return checkedRefusal("RESOURCE_LIMIT", checkpoint)
        } finally {
            running.set(false)
        }
    }

    private fun refuse(reason: String) = DetectionProposal(null, null, false, reason)

    private fun checkedRefusal(reason: String, checkpoint: () -> Unit): DetectionProposal {
        checkpoint()
        return refuse(reason)
    }
}
