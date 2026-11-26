package app.pristinepage.image.preview

import app.pristinepage.core.geometry.Coordinates
import app.pristinepage.core.image.Numeric
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.CommittedSources
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** A bounded display-only consumer of the existing verified original. Storage access stays inside SourceDecoder's loan; no recipe, durable copy or new source identity is created. The caller retains the page/history/source owner. */
class OriginalPreviewLoader private constructor(
    private val decoder: SourceDecoder, private val budget: ImageAllocationBudget, private val dispatcher: CoroutineDispatcher,
    private val privateSources: CommittedSources?,
) {
    @JvmOverloads constructor(decoder: SourceDecoder, budget: ImageAllocationBudget, dispatcher: CoroutineDispatcher = PreviewProcessing.dispatcher) :
        this(decoder, budget, dispatcher, null)

    // Preserve the independent legacy private lookup and binding check for existing callers.
    @JvmOverloads constructor(sources: CommittedSources, decoder: SourceDecoder, budget: ImageAllocationBudget,
        dispatcher: CoroutineDispatcher = PreviewProcessing.dispatcher,
    ) : this(decoder, budget, dispatcher, sources)

    suspend fun load(sourceId: String, longEdge: Int = 1024): OriginalPreviewRaster {
        var completed: OriginalPreviewRaster? = null
        try {
            return withContext(dispatcher) {
                val context = currentCoroutineContext()
                context.ensureActive()
                val id = try { SourceId(sourceId) } catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INVALID_INPUT, "invalid_source_id", null) }
                if (longEdge !in 1..1024) refuse(OperationErrorCode.INVALID_INPUT, "invalid_preview_limit", id)
                val retained = privateSources?.load(id)
                if (retained != null && retained.sourceId != id) refuse(OperationErrorCode.INTEGRITY_FAILED, "committed_source_binding_mismatch", id)
                decoder.decodeForOriginalPreview(sourceId).use { full ->
                    context.ensureActive()
                    if (retained != null && (full.inspection != retained.inspection || full.sourceByteHash != retained.sourceByteHash)) {
                        refuse(OperationErrorCode.INTEGRITY_FAILED, "preview_source_binding_changed", id)
                    }
                    val size = Coordinates.limitLongEdge(full.inspection.normalizedSize, longEdge, geometric = false)
                    val output = OutputSpec(size, RenderPurpose.PREVIEW)
                    // At most1024² pixels. Charge both the packed scratch and immutable RgbImage copy before allocating either, in addition to the still-live full-source permit. Retaining the conservative charge until close is intentional; a later bitmap/display bridge needs its own reservation.
                    val permit = budget.reserve(6L * size.pixelCount)
                    var image: RgbImage? = null
                    var transferred = false
                    try {
                        val bytes = ByteArray((3L * size.pixelCount).toInt())
                        sample(full.image, size, bytes) { context.ensureActive() }
                        image = RgbImage(size.width, size.height, size.width * 3, bytes)
                        context.ensureActive()
                        OwnedOriginalPreview(sourceId, full.sourceByteHash, full.inspection, output, image, permit).also { completed = it; transferred = true }
                    } finally { if (!transferred) { try { image?.close() } finally { permit.close() } } }
                }
            }
        } catch (canceled: CancellationException) { completed?.close(); throw canceled }
        catch (failure: RepositoryFailure) { completed?.close(); throw failure }
        catch (_: Exception) { completed?.close(); refuse(OperationErrorCode.PROCESSING_FAILED, "original_preview_failed", null) }
    }

    private fun sample(source: RgbImage, size: PixelSize, output: ByteArray, checkpoint: () -> Unit) {
        val identity = source.width == size.width && source.height == size.height
        for (y in 0 until size.height) {
            checkpoint()
            val sy = Coordinates.halfPixelSource(y.toDouble(), source.height, size.height)
            val y0 = floor(sy).toInt(); val fy = sy - y0
            for (x in 0 until size.width) {
                val sx = Coordinates.halfPixelSource(x.toDouble(), source.width, size.width)
                val x0 = floor(sx).toInt(); val fx = sx - x0
                for (channel in 0..2) {
                    val sample = if (identity) source.getUnsigned(x, y, channel) else {
                        val left = x0.coerceIn(0, source.width - 1); val right = (x0 + 1).coerceIn(0, source.width - 1)
                        val top = y0.coerceIn(0, source.height - 1); val bottom = (y0 + 1).coerceIn(0, source.height - 1)
                        val upper = (1.0 - fx) * source.getUnsigned(left, top, channel) + fx * source.getUnsigned(right, top, channel)
                        val lower = (1.0 - fx) * source.getUnsigned(left, bottom, channel) + fx * source.getUnsigned(right, bottom, channel)
                        Numeric.roundByte((1.0 - fy) * upper + fy * lower)
                    }
                    output[(y * size.width + x) * 3 + channel] = sample.toByte()
                }
            }
        }
    }

    private companion object {
        fun refuse(code: OperationErrorCode, key: String, id: SourceId?): Nothing = throw RepositoryFailure(OperationError(code, false, key, id?.value))
    }
}

private class OwnedOriginalPreview(override val sourceId: String, override val sourceByteHash: String, override val inspection: SourceInspection, override val output: OutputSpec, override val image: RgbImage, private val permit: AutoCloseable) : OriginalPreviewRaster {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) { try { image.close() } finally { permit.close() } } }
}
