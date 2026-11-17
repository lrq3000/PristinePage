package app.pristinepage.image.preview

import android.graphics.Bitmap
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.data.model.*
import app.pristinepage.image.decode.ImageAllocationBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Converts only the verified, sized RGB preview, never reopens a provider or decodes another bitmap from source bytes. The result's display owner must end before close; published draw references are not manually recycled. */
class OriginalPreviewBitmapLoader @JvmOverloads constructor(private val rasters: OriginalPreviewLoader, private val budget: ImageAllocationBudget, private val dispatcher: CoroutineDispatcher = PreviewProcessing.dispatcher) {
    suspend fun load(sourceId: String, longEdge: Int = 1024): OriginalPreviewBitmap {
        var completed: SrgbPreviewBitmap? = null
        try {
            return withContext(dispatcher) {
                val context = currentCoroutineContext()
                context.ensureActive()
                rasters.load(sourceId, longEdge).use { raster ->
                    val display = SrgbPreviewBitmap.convert(raster.image, budget) { context.ensureActive() }.also { completed = it }
                    OwnedPreviewBitmap(raster.sourceId, raster.sourceByteHash, raster.inspection, raster.output, display)
                }
            }
        } catch (canceled: CancellationException) { completed?.discardUndelivered(); throw canceled }
        catch (failure: RepositoryFailure) { completed?.discardUndelivered(); throw failure }
        catch (_: Exception) { completed?.discardUndelivered(); throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, false, "original_display_failed", null)) }
    }
}

private class OwnedPreviewBitmap(override val sourceId: String, override val sourceByteHash: String, override val inspection: SourceInspection, override val output: OutputSpec, private val display: SrgbPreviewBitmap) : OriginalPreviewBitmap {
    override val bitmap: Bitmap get() = display.bitmap
    override fun close() = display.close()
}
