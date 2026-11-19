package app.pristinepage.image.export

import android.os.Build
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.model.ExportFormat
import app.pristinepage.data.model.ExportPageSnapshot
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryExportProfile
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceInspection
import app.pristinepage.data.repository.ExportSourceBinding
import app.pristinepage.image.decode.DecodeAdmission
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecodePolicy
import app.pristinepage.image.decode.sourceParserBytes
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.GeometryEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Observes a fit in the execution ledger; it neither reserves capacity nor certifies source bytes, actual map or codec behavior. The caller must preserve the authenticated JOB pin, frozen ordinal and sink cap through admission and execution. */
internal class OrdinaryPngPreflight(
    profile: OrdinaryExportProfile,
    private val budget: ImageAllocationBudget,
    private val sdkLevel: () -> Int = { Build.VERSION.SDK_INT },
    private val buildFingerprint: () -> String = { Build.FINGERPRINT },
) {
    private val requests = OrdinaryFrozenPageRequest(profile)

    suspend fun check(format: ExportFormat, page: ExportPageSnapshot, jobSource: ExportSourceBinding, maxBytes: Long): OperationError? {
        val context = currentCoroutineContext()
        context.ensureActive()
        val pageId = page.pageId.value
        val error = try {
            if (format != ExportFormat.PNG) pngRefuse(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_format")
            val sdk = sdkLevel()
            requireOrdinaryPngApi(sdk)
            if (maxBytes <= 0) pngRefuse(OperationErrorCode.INVALID_INPUT, "export_png_invalid_cap")
            val request = requests.build(page)
            val (source, blob) = jobSource
            val decode = DecodeAdmission.estimate(blob.byteCount, blob.encodedWidth.toLong(), blob.encodedHeight.toLong(), source.exifOrientation)
            val inspection = requireMatchingSource(page, jobSource, decode.size)
            context.ensureActive()
            SourceDecodePolicy.requireReproducible(inspection, sdk, buildFingerprint())

            val size = request.output.size
            val geometry = GeometryEngine.ordinaryCharge(request.recipe.geometry, size)
            val appearance = AppearanceEngine.ordinaryCharge(request.recipe, size)
            // Parser closes before decode. Decode and geometry remain owned until the appearance result has been handed out.
            val render = Math.addExact(Math.addExact(decode.bytes, geometry), appearance)
            // The complete appearance permit survives, including its scratch headroom. Encode, both container scans and readback are sequential; the cap bounds readback exposure, not actual compressed length.
            val codec = maxOf(pngNativeBytes(size.width, size.height), OrdinaryPngContainer.scanBytes,
                pngNativeBytes(size.width, size.height, maxBytes))
            val peak = maxOf(sourceParserBytes(blob.byteCount), render, Math.addExact(appearance, codec))
            // Capacity is immutable and usedBytes is synchronized; nothing is held on behalf of this caller after the observation.
            if (peak > budget.capacityBytes - budget.usedBytes) pngRefuse(OperationErrorCode.RESOURCE_LIMIT, "image_allocation_limit")
            null
        } catch (failure: RepositoryFailure) {
            failure.error.copy(affectedId = pageId)
        } catch (_: ArithmeticException) {
            OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "image_allocation_limit", pageId)
        } catch (_: IllegalArgumentException) {
            OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "export_frozen_recipe_mismatch", pageId)
        }
        context.ensureActive()
        return error
    }

    private fun requireMatchingSource(page: ExportPageSnapshot, jobSource: ExportSourceBinding, decodedSize: PixelSize): SourceInspection {
        val (source, blob) = jobSource
        try {
            requireSha256(blob.byteSha256)
            requireSha256(page.byteHash)
            val inspection = SourceInspection(blob.mime, PixelSize(blob.encodedWidth, blob.encodedHeight),
                PixelSize(source.width, source.height), source.exifOrientation, source.profileSha256, source.conversionFingerprint, source.pixelSha256)
            // A distinct original may normalize to identical pixels. A reused Source or Blob ID cannot describe a different immutable object.
            require(source.sourceBlobId == blob.sourceBlobId)
            require(source.sourceId != page.sourceId.value || blob.sourceBlobId == page.sourceBlobId.value)
            require(blob.sourceBlobId != page.sourceBlobId.value || blob.byteSha256 == page.byteHash)
            require(decodedSize == inspection.normalizedSize && decodedSize == page.sourceSize &&
                inspection.sourcePixelHash == page.pixelHash && inspection.conversionFingerprint == page.conversionFingerprint)
            return inspection
        } catch (_: IllegalArgumentException) {
            pngRefuse(OperationErrorCode.INTEGRITY_FAILED, "export_png_source_binding_mismatch")
        }
    }
}
