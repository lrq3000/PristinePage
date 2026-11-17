package app.pristinepage.image.preview

import android.graphics.Bitmap
import android.graphics.ColorSpace
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.data.model.*
import app.pristinepage.image.decode.ImageAllocationBudget
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Converts only the verified, sized RGB preview, never reopens a provider or decodes another bitmap from source bytes. The result's display owner must end before close; published draw references are not manually recycled. */
class OriginalPreviewBitmapLoader @JvmOverloads constructor(private val rasters: OriginalPreviewLoader, private val budget: ImageAllocationBudget, private val dispatcher: CoroutineDispatcher = PreviewProcessing.dispatcher) {
    suspend fun load(sourceId: String, longEdge: Int = 1024): OriginalPreviewBitmap {
        var completed: OwnedPreviewBitmap? = null
        try {
            return withContext(dispatcher) {
                val context = currentCoroutineContext()
                context.ensureActive()
                rasters.load(sourceId, longEdge).use { raster ->
                    val size = raster.output.size
                    // Bitmap4N + provisional draw/upload copy4N + one ARGB row4W, charged while the separate RGB raster reservation is still held. This is conservative processing accounting, not a measured upper bound on graphics/PSS or GC latency.
                    val permit = budget.reserve(8L * size.pixelCount + 4L * size.width)
                    var bitmap: Bitmap? = null
                    var transferred = false
                    try {
                        context.ensureActive()
                        bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
                        bitmap.density = Bitmap.DENSITY_NONE
                        val row = IntArray(size.width)
                        for (y in 0 until size.height) {
                            context.ensureActive()
                            for (x in row.indices) row[x] = (255 shl 24) or (raster.image.getUnsigned(x,y,0) shl 16) or (raster.image.getUnsigned(x,y,1) shl 8) or raster.image.getUnsigned(x,y,2)
                            bitmap.setPixels(row, 0, size.width, 0, y, size.width, 1)
                        }
                        context.ensureActive()
                        OwnedPreviewBitmap(raster.sourceId, raster.sourceByteHash, raster.inspection, raster.output, bitmap, permit).also { completed = it; transferred = true }
                    } finally { if (!transferred) { try { bitmap?.recycle() } finally { permit.close() } } }
                }
            }
        } catch (canceled: CancellationException) { completed?.discardUndelivered(); throw canceled }
        catch (failure: RepositoryFailure) { completed?.discardUndelivered(); throw failure }
        catch (_: Exception) { completed?.discardUndelivered(); throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, false, "original_display_failed", null)) }
    }
}

private class OwnedPreviewBitmap(override val sourceId: String, override val sourceByteHash: String, override val inspection: SourceInspection, override val output: OutputSpec, initial: Bitmap, private val permit: AutoCloseable) : OriginalPreviewBitmap {
    @Volatile private var pixels: Bitmap? = initial
    private val closed = AtomicBoolean(false)
    override val bitmap: Bitmap get() = checkNotNull(pixels) { "Original display lease is closed" }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Relinquish the application's strong reference. An issued Compose/draw reference may outlive disposal by a frame; recycling it here would invalidate that reference. Android GC owns residual Java/graphics retention.
            pixels = null
            permit.close()
        }
    }
    fun discardUndelivered() { val owned = pixels; close(); owned?.recycle() }
}
