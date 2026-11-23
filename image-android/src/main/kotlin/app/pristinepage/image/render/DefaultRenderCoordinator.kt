package app.pristinepage.image.render

import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.ExportSourceBinding
import app.pristinepage.image.decode.DecodeAdmission
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.image.decode.sourceParserBytes
import app.pristinepage.image.preview.PreviewProcessing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Classical source-to-raster composition with explicit FAIL/KEEP_STAGE_INPUT semantics. One application-owned instance serializes processing on the existing image dispatcher. Caller retains a durable source owner; this queue is not a GC lease, generation publisher or durable accepted-degraded export record. */
class DefaultRenderCoordinator @JvmOverloads constructor(private val decoder: SourceDecoder, private val geometry: GeometryEngine, private val appearance: AppearanceEngine, private val dispatcher: CoroutineDispatcher = PreviewProcessing.dispatcher, private val queue: Mutex = Mutex()) : RenderCoordinator {

    override suspend fun render(request: RenderRequest): RenderResult = render(request, null)

    /** Expose the typed preflight refusal only to the OCR preparer using this same image ledger. */
    internal suspend fun renderForOcrPreparation(request: RenderRequest, ledger: ImageAllocationBudget): RenderResult {
        require(request.output.purpose == RenderPurpose.OCR)
        return render(request, null, reportIdleRefusal = ledger === geometry.budget)
    }

    /** Ordinary export only. The binding selects the admitted JOB original, not the frozen consumer's file; the caller retains the job authority. */
    internal suspend fun renderForProducer(request: RenderRequest, expected: ExportSourceBinding): RenderResult =
        render(request, expected)

    private suspend fun render(request: RenderRequest, expected: ExportSourceBinding?, reportIdleRefusal: Boolean = false): RenderResult {
        var completed: RenderResult? = null
        try {
            return withContext(dispatcher) {
                queue.withLock {
                    val context = currentCoroutineContext()
                    context.ensureActive()
                    val execution = Execution(request, expected)
                    val result = try { execution.run { context.ensureActive() } }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (refused: ProvenIdleLimit) {
                        if (reportIdleRefusal) throw refused
                        execution.failure(refused)
                    }
                    catch (failure: RepositoryFailure) { execution.failure(failure) }
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

    private inner class Execution(private val request: RenderRequest, private val expected: ExportSourceBinding?) {
        private val records = ArrayList<StageRecord>()
        private var active: String? = null
        private var inputSize = request.recipe.geometry.source.sourceSize
        private var outputSize = inputSize
        private var ocrIdleFits = false
        private var ocrGeometryCharge = 0L
        private var ocrHeldBytes = 0L

        suspend fun run(checkpoint: () -> Unit): RenderResult {
            checkpoint()
            if (expected != null && (request.fallback != FallbackPolicy.FAIL || request.output.purpose != RenderPurpose.EXPORT)) {
                refuse(OperationErrorCode.INVALID_INPUT, "invalid_producer_render_policy")
            }
            preflight(request)
            var final: AppearanceRaster? = null
            var transferred = false
            try {
                active = "source_decode_v1"
                val decoded = when {
                    expected != null -> decoder.decodeForProducer(request.sourceId, request.output, expected)
                    request.output.purpose == RenderPurpose.OCR -> decoder.decodeForOcr(request.sourceId, request.output, ::checkOcrCapacity)
                    else -> decoder.decode(request.sourceId, request.output)
                }
                var sourceTransferred = false
                try {
                    checkpoint()
                    val info = decoded.inspection
                    val actual = SourceBinding(info.sourcePixelHash, info.normalizedSize, info.conversionFingerprint)
                    if (actual != request.recipe.geometry.source || actual.sourcePixelHash != request.sourcePixelHash) refuse(OperationErrorCode.INVALID_INPUT, "render_source_binding_mismatch")
                    records.add(StageRecord("source_decode_v1", 1, null, "OK", info.encodedSize, info.normalizedSize, listOf("SOURCE_BYTES_VERIFIED", "SOURCE_PIXELS_VERIFIED")))
                    active = when (request.recipe.geometry) {
                        is GeometryRecipe.None -> "geometry_none_v1"
                        is GeometryRecipe.Perspective -> "perspective_v1"
                        is GeometryRecipe.ManualCurve -> "manual_ruled_v1"
                        is GeometryRecipe.ManualCurveFourSides -> "manual_four_sides_v1"
                        is GeometryRecipe.Bezier -> "atelier_bezier_v1"
                        is GeometryRecipe.RoughCropDewarp -> "rough_crop_dewarp_v1"
                    }
                    inputSize = info.normalizedSize; outputSize = request.output.size
                    if (ocrIdleFits) ocrHeldBytes = decoded.chargedOn(geometry.budget)
                    val shaped = try { geometry.render(decoded.image, actual, request.recipe.geometry, request.output, null, null, checkpoint) }
                    catch (error: Exception) {
                        val failure = StageFallback.failure(error, request.fallback, "geometry_processing_failed")
                        val omissions = listOf("KEEP_STAGE_INPUT", "GEOMETRY_OUTPUT_OMITTED", "OUTPUT_ROTATION_OMITTED_${request.recipe.geometry.outputQuarterTurns}", failure.safeMessageKey)
                        records.add(StageRecord(checkNotNull(active), 1, null, "FAILED_${failure.code.name}", inputSize, inputSize, omissions))
                        records.addAll(appearance.skippedStages(request.recipe, inputSize))
                        checkpoint()
                        // Geometry never completed: preserve the ACTUAL unrotated full stage input, not its requested resized/rotated destination. Transfer the WHOLE decoder permit so these pixels remain charged until the result closes.
                        return RenderResult(request, RenderStatus.DEGRADED, decoded.image, records, omissions, decoded).also { sourceTransferred = true }
                    }
                    shaped.use {
                        // OCR has no geometry fallback. The completed geometry raster independently owns every pixel consumed by appearance; release the now-unused full-source decode charge before reserving appearance.
                        if (request.output.purpose == RenderPurpose.OCR) {
                            decoded.close()
                            ocrHeldBytes = ocrGeometryCharge
                        }
                        records.add(shaped.stage)
                        active = "appearance_graph_v1"
                        inputSize = PixelSize(shaped.image.width, shaped.image.height); outputSize = inputSize
                        final = appearance.render(shaped.image, request.recipe, shaped.protection, request.fallback, checkpoint)
                        records.addAll(final.stages)
                        checkpoint()
                    }
                } finally { if (!sourceTransferred) decoded.close() }
                // Only the final packed output survives. Keep the COMPLETE AppearanceRaster as owner, rather than retaining RGB after its permit or decode owner's scope ends.
                checkpoint()
                val raster = checkNotNull(final)
                val failure = raster.failure
                return RenderResult(request, if (failure == null) RenderStatus.OK else RenderStatus.DEGRADED, raster.image, records, if (failure == null) emptyList() else listOf("KEEP_STAGE_INPUT", failure.safeMessageKey), raster).also { transferred = true }
            } finally { if (!transferred) final?.close() }
        }

        private fun checkOcrCapacity(source: SourceInspection, encodedBytes: Long) {
            val expectedSource = request.recipe.geometry.source
            if (source.sourcePixelHash != expectedSource.sourcePixelHash || source.normalizedSize != expectedSource.sourceSize ||
                source.conversionFingerprint != expectedSource.orientationColorConversionVersion) {
                refuse(OperationErrorCode.INVALID_INPUT, "render_source_binding_mismatch")
            }
            val extent = source.encodedSize
            val decode = DecodeAdmission.estimate(encodedBytes, extent.width.toLong(), extent.height.toLong(), source.exifOrientation)
            val size = request.output.size
            val shaped = GeometryEngine.ordinaryCharge(request.recipe.geometry, size)
            val appearance = AppearanceEngine.ordinaryCharge(request.recipe, size)
            // Parser, decode+geometry, and geometry+appearance are sequential. The app's renderer uses one shared ledger for all three.
            val peak = maxOf(sourceParserBytes(encodedBytes), Math.addExact(decode.bytes, shaped), Math.addExact(shaped, appearance))
            val budget = geometry.budget
            if (peak > budget.capacityBytes) throw ProvenIdleLimit(request)
            ocrGeometryCharge = shaped
            ocrIdleFits = true
            // This is an observation, not a reservation: a later consumer may still take headroom.
            if (peak > budget.capacityBytes - budget.usedBytes) refuse(OperationErrorCode.RESOURCE_LIMIT, OCR_RENDER_BUDGET_BUSY)
        }

        fun failure(failure: RepositoryFailure): RenderResult {
            val error = failure.error
            val budget = geometry.budget
            // Use the bytes and ledger that actually refused, not just the earlier fit estimate or a string key. Even one reservation can exceed idle capacity if the codec's parsed header differs from stored metadata.
            val key = if (ocrIdleFits && failure is ImageAllocationBudget.Denied && failure.ledger === budget &&
                failure.requestedBytes <= budget.capacityBytes - ocrHeldBytes) OCR_RENDER_BUDGET_BUSY else error.safeMessageKey
            return failure(status(error.code), key)
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
        val longEdgeLimit = when (request.output.purpose) {
            RenderPurpose.CARD -> 224
            RenderPurpose.PREVIEW -> 1024
            RenderPurpose.OCR -> GeometrySizing.OCR_LONG_EDGE
            else -> null
        }
        if (longEdgeLimit != null && maxOf(size.width, size.height) > longEdgeLimit) refuse(OperationErrorCode.INVALID_INPUT, "invalid_render_output_purpose")
        if (request.output.purpose == RenderPurpose.OCR && request.fallback != FallbackPolicy.FAIL) {
            refuse(OperationErrorCode.INVALID_INPUT, "invalid_ocr_render_fallback")
        }
        GeometryEngine.validateOutput(target, size, request.recipe.geometry !is GeometryRecipe.None)
        AppearanceEngine.preflight(request.recipe, request.protectionMaskId != null, request.fallback)
        if (request.protectionMaskId != null) refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "durable_render_mask_not_implemented")
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

    private class ProvenIdleLimit(val request: RenderRequest) :
        RepositoryFailure(OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "ocr_render_exceeds_idle_budget", null))

    internal fun provedImpossibleIdleLimit(failure: RepositoryFailure, request: RenderRequest): Boolean =
        failure is ProvenIdleLimit && failure.request === request
}

/** Only a proven idle-fit shared-renderer ledger refusal is eligible for automatic redelivery. */
internal const val OCR_RENDER_BUDGET_BUSY = "ocr_render_budget_busy"
