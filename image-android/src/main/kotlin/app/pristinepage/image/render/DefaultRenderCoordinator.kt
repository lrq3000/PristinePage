package app.pristinepage.image.render

import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.*
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.image.preview.PreviewProcessing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Ordinary FAIL-policy source-to-raster prerequisite. One application-owned instance serializes expensive processing and reuses the existing two-lane image dispatcher. The caller retains a durable page/history/snapshot source owner; this queue is neither a GC lease nor a current-generation UI publisher. */
class DefaultRenderCoordinator @JvmOverloads constructor(private val decoder: SourceDecoder, private val geometry: GeometryEngine, private val appearance: AppearanceEngine, private val dispatcher: CoroutineDispatcher = PreviewProcessing.dispatcher) : RenderCoordinator {
    private val queue = Mutex()

    override suspend fun render(request: RenderRequest): RenderResult {
        var completed: RenderResult? = null
        try {
            return withContext(dispatcher) {
                queue.withLock {
                    val context = currentCoroutineContext()
                    context.ensureActive()
                    val execution = Execution(request)
                    val result = try { execution.run { context.ensureActive() } }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (failure: RepositoryFailure) { execution.failure(status(failure.error.code), failure.error.safeMessageKey) }
                    catch (_: OutOfMemoryError) { execution.failure(RenderStatus.RESOURCE_LIMIT, "render_allocation_failed") }
                    catch (_: Exception) { execution.failure(RenderStatus.PROCESSING_FAILED, "render_processing_failed") }
                    result.also { completed = it }
                }
            }
        } catch (canceled: CancellationException) {
            // A dispatcher can cancel delivery AFTER the complete final owner was constructed. It must not leak merely because its continuation never receives it.
            completed?.close()
            throw canceled
        }
    }

    private inner class Execution(private val request: RenderRequest) {
        private val records = ArrayList<StageRecord>()
        private var active: String? = null
        private var inputSize = request.recipe.geometry.source.sourceSize
        private var outputSize = inputSize

        suspend fun run(checkpoint: () -> Unit): RenderResult {
            checkpoint()
            preflight(request)
            var final: AppearanceRaster? = null
            var transferred = false
            try {
                active = "source_decode_v1"
                decoder.decode(request.sourceId, request.output).use { decoded ->
                    checkpoint()
                    val info = decoded.inspection
                    val actual = SourceBinding(info.sourcePixelHash, info.normalizedSize, info.conversionFingerprint)
                    if (actual != request.recipe.geometry.source || actual.sourcePixelHash != request.sourcePixelHash) refuse(OperationErrorCode.INVALID_INPUT, "render_source_binding_mismatch")
                    records.add(StageRecord("source_decode_v1", 1, null, "OK", info.encodedSize, info.normalizedSize, listOf("SOURCE_BYTES_VERIFIED", "SOURCE_PIXELS_VERIFIED")))
                    active = "geometry_${request.recipe.geometry.mode.name.lowercase()}_v1"
                    inputSize = info.normalizedSize; outputSize = request.output.size
                    geometry.render(decoded.image, actual, request.recipe.geometry, request.output, null, null, checkpoint).use { shaped ->
                        records.add(shaped.stage)
                        active = "appearance_graph_v1"
                        inputSize = PixelSize(shaped.image.width, shaped.image.height); outputSize = inputSize
                        final = appearance.render(shaped.image, request.recipe, shaped.protection, checkpoint)
                        records.addAll(final.stages)
                        checkpoint()
                    }
                }
                // Only the final packed output survives. Keep the COMPLETE AppearanceRaster as owner, rather than retaining RGB after its permit or decode owner's scope ends.
                checkpoint()
                val raster = checkNotNull(final)
                return RenderResult(request, RenderStatus.OK, raster.image, records, emptyList(), raster).also { transferred = true }
            } finally { if (!transferred) final?.close() }
        }

        fun failure(status: RenderStatus, key: String): RenderResult {
            active?.let { records.add(StageRecord(it, 1, null, "FAILED_${status.name}", inputSize, outputSize, listOf(key))) }
            return RenderResult(request, status, null, records, listOf(key))
        }
    }

    private fun preflight(request: RenderRequest) {
        val target = try {
            SourceId(request.sourceId); PageId(request.pageId)
            requireSha256(request.sourcePixelHash)
            require(request.recipeRevision >= 1 && request.generation >= 0 && request.sourcePixelHash == request.recipe.geometry.source.sourcePixelHash)
            request.protectionMaskId?.let { MaskId(it) }
            GeometrySizing.target(request.recipe.geometry)
        } catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INVALID_INPUT, "invalid_render_request") }
        val size = request.output.size
        if (size.width > 12000 || size.height > 12000 || size.pixelCount > 48_000_000) refuse(OperationErrorCode.RESOURCE_LIMIT, "render_destination_limit")
        // OutputSpec is already resolved: reject a mislabeled oversized card/preview, never silently shrink it. Full-target detail and explicit source-resolution export have different policies.
        val longEdgeLimit = when (request.output.purpose) { RenderPurpose.CARD -> 224; RenderPurpose.PREVIEW -> 1024; else -> null }
        if (longEdgeLimit != null && maxOf(size.width, size.height) > longEdgeLimit) refuse(OperationErrorCode.INVALID_INPUT, "invalid_render_output_purpose")
        GeometryEngine.validateOutput(target, size, request.recipe.geometry !is GeometryRecipe.None)
        AppearanceEngine.preflight(request.recipe, request.protectionMaskId != null)
        if (request.protectionMaskId != null) refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "durable_render_mask_not_implemented")
        if (request.fallback != FallbackPolicy.FAIL) refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "render_fallback_not_implemented")
        if (request.recipe.geometry is GeometryRecipe.RoughCropDewarp) refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "geometry_map_not_implemented")
        if (request.output.purpose == RenderPurpose.DETAIL_CROP && size != target) refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "detail_context_not_implemented")
    }

    private fun status(code: OperationErrorCode): RenderStatus = when (code) {
        OperationErrorCode.INVALID_INPUT, OperationErrorCode.UNSUPPORTED_FORMAT, OperationErrorCode.REVISION_CONFLICT -> RenderStatus.INVALID_INPUT
        OperationErrorCode.PROVIDER_UNAVAILABLE, OperationErrorCode.UNSUPPORTED_VERSION -> RenderStatus.UNAVAILABLE
        OperationErrorCode.INVALID_MAP -> RenderStatus.INVALID_MAP
        OperationErrorCode.PROCESSING_FAILED -> RenderStatus.PROCESSING_FAILED
        OperationErrorCode.RESOURCE_LIMIT -> RenderStatus.RESOURCE_LIMIT
        OperationErrorCode.CANCELED -> RenderStatus.CANCELED
        OperationErrorCode.STORAGE_FAILED, OperationErrorCode.INTEGRITY_FAILED, OperationErrorCode.PERMISSION_LOST, OperationErrorCode.SOURCE_UNAVAILABLE, OperationErrorCode.SESSION_EXPIRED -> RenderStatus.STORAGE_FAILED
    }
    private fun refuse(code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, null))
}
