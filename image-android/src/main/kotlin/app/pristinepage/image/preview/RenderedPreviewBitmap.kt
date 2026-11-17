package app.pristinepage.image.preview

import android.graphics.Bitmap
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Display ownership for an already rendered preview, not a new render or durable output. Bitmap is borrowed while open; close drops application ownership/accounting without recycling an issued draw reference. */
interface RenderedPreviewBitmap : AutoCloseable {
    val request: RenderRequest
    val bitmap: Bitmap
}

fun interface RenderedPreviewBitmaps {
    /** Borrows the entire result: the caller retains it until conversion completes. Neither that result nor its image/accounting owner is closed or transferred. */
    suspend fun convert(result: RenderResult): RenderedPreviewBitmap
}

/** Converts only an exact successful PREVIEW raster. Current generation/request publication is the editor scheduler's responsibility, not this image bridge's. */
class RenderedPreviewBitmapLoader @JvmOverloads constructor(private val budget: ImageAllocationBudget, private val dispatcher: CoroutineDispatcher = PreviewProcessing.dispatcher) : RenderedPreviewBitmaps {
    override suspend fun convert(result: RenderResult): RenderedPreviewBitmap {
        var completed: SrgbPreviewBitmap? = null
        try {
            val display = withContext(dispatcher) {
                val context = currentCoroutineContext()
                context.ensureActive()
                val request = result.request
                val size = request.output.size
                val image = result.image
                if (result.status != RenderStatus.OK || request.output.purpose != RenderPurpose.PREVIEW ||
                    maxOf(size.width, size.height) > 1024 || image == null || image.width != size.width || image.height != size.height) {
                    throw RepositoryFailure(OperationError(OperationErrorCode.INVALID_INPUT, false, "invalid_rendered_preview", null))
                }
                val packed = SrgbPreviewBitmap.convert(image, budget) { context.ensureActive() }.also { completed = it }
                OwnedRenderedPreviewBitmap(request, packed)
            }
            currentCoroutineContext().ensureActive()
            return display
        } catch (canceled: CancellationException) { completed?.discardUndelivered(); throw canceled }
        catch (failure: RepositoryFailure) { completed?.discardUndelivered(); throw failure }
        catch (_: Exception) { completed?.discardUndelivered(); throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, false, "rendered_display_failed", null)) }
    }
}

private class OwnedRenderedPreviewBitmap(override val request: RenderRequest, private val display: SrgbPreviewBitmap) : RenderedPreviewBitmap {
    override val bitmap: Bitmap get() = display.bitmap
    override fun close() = display.close()
}
