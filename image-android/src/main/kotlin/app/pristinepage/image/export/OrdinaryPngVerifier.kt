package app.pristinepage.image.export

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryRasterDigest
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

/** Encoded closed-file byte identity and independently computed canonical decoded RGB identity. */
internal data class VerifiedPngIdentity(val encoded: EncodedPngIdentity, val rasterSha256: String)

/** Read-only, synchronous borrow under the caller's stable closed-file and complete raster ownership. The receipt is neither a storage seal nor proof against concurrent path replacement. */
internal class OrdinaryPngVerifier(private val budget: ImageAllocationBudget) {
    fun verifyClosed(file: File, expected: RgbImage, maxBytes: Long, checkpoint: () -> Unit): VerifiedPngIdentity {
        checkpoint()
        requireOrdinaryPngApi()
        val size = PixelSize(expected.width, expected.height)
        val before = scan(file, size, maxBytes, checkpoint)
        val decoded = compare(file, expected, before.byteLength, checkpoint)
        // Reopen after native resources have been released. Do not finalize the decoded digest until the compressed-byte identity also matches.
        val after = scan(file, size, maxBytes, checkpoint)
        if (before != after) pngRefuse(OperationErrorCode.INTEGRITY_FAILED, "export_png_byte_identity_mismatch")
        checkpoint()
        return try {
            VerifiedPngIdentity(after, decoded.finish())
        } catch (_: OutOfMemoryError) {
            allocationFailed()
        }
    }

    private fun scan(file: File, size: PixelSize, maxBytes: Long, checkpoint: () -> Unit): EncodedPngIdentity {
        checkpoint()
        if (maxBytes <= 0) pngRefuse(OperationErrorCode.INVALID_INPUT, "export_png_invalid_cap")
        try {
            val path = file.toPath()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) readFailed()
            return Files.newInputStream(path, NOFOLLOW_LINKS).use {
                OrdinaryPngContainer(budget).read(it, attributes.size(), maxBytes, size, checkpoint)
            }
        } catch (_: IOException) {
            readFailed()
        }
    }

    @TargetApi(28)
    private fun compare(file: File, expected: RgbImage, encodedBytes: Long, checkpoint: () -> Unit): OrdinaryRasterDigest {
        checkpoint()
        return try {
            budget.reserve(pngNativeBytes(expected, encodedBytes)).use {
                checkpoint()
                val bitmap = decode(file, expected, checkpoint)
                try {
                    checkpoint()
                    if (bitmap.width != expected.width || bitmap.height != expected.height) dimensions()
                    if (bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.colorSpace?.isSrgb != true ||
                        (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap())) {
                        pngRefuse(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_decode_color")
                    }
                    val row = IntArray(expected.width)
                    // The 4W ARGB row charge is unchanged. The digest's at-most-12 KiB RGB block fits in the already charged 1 MiB workspace and is dropped after the final row.
                    val decoded = OrdinaryRasterDigest(bitmap.width, bitmap.height)
                    for (y in 0 until expected.height) {
                        checkpoint()
                        bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                        decoded.appendRow(row.size, checkpoint) { x ->
                            val actual = row[x]
                            if ((actual ushr 24) != 255) pngRefuse(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_alpha")
                            if (actual != opaqueArgb(expected, x, y)) pngRefuse(OperationErrorCode.INTEGRITY_FAILED, "export_png_pixels_mismatch")
                            actual
                        }
                    }
                    checkpoint()
                    decoded
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (_: OutOfMemoryError) {
            allocationFailed()
        }
    }

    @TargetApi(28)
    private fun decode(file: File, expected: RgbImage, checkpoint: () -> Unit): Bitmap {
        var strictHeaderApplied = false
        try {
            // Same strict platform codec as import, but its container/normalization/identity policy does not prove this export.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                checkpoint()
                if (info.isAnimated || info.mimeType != "image/png") pngRefuse(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_format")
                if (info.size.width != expected.width || info.size.height != expected.height) dimensions()
                // File source, no target size or sample size; verify dimensions again on the resulting bitmap. Header color is not encoded metadata.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isUnpremultipliedRequired = true
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
                strictHeaderApplied = true
            }
        } catch (failure: ImageDecoder.DecodeException) {
            when (failure.error) {
                ImageDecoder.DecodeException.SOURCE_EXCEPTION -> readFailed()
                ImageDecoder.DecodeException.SOURCE_INCOMPLETE, ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA ->
                    pngRefuse(OperationErrorCode.INTEGRITY_FAILED, if (strictHeaderApplied) "export_png_decode_data" else "export_png_decode_header_data")
                else -> pngRefuse(OperationErrorCode.PROCESSING_FAILED, "export_png_decode_failed")
            }
        } catch (_: IOException) {
            readFailed()
        } catch (_: IllegalArgumentException) {
            pngRefuse(OperationErrorCode.PROCESSING_FAILED, "export_png_decode_failed")
        }
    }

    private fun dimensions(): Nothing = pngRefuse(OperationErrorCode.INTEGRITY_FAILED, "export_png_dimensions")
    private fun readFailed(): Nothing = pngRefuse(OperationErrorCode.STORAGE_FAILED, "export_png_read_failed", retryable = true)
    private fun allocationFailed(): Nothing = pngRefuse(OperationErrorCode.RESOURCE_LIMIT, "export_png_allocation_failed", retryable = true)
}
