package app.pristinepage.image.export

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import app.pristinepage.core.image.RgbImage
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.OutputStream

/** Synchronous pixels-only encoder. The caller retains the complete raster and the byte-limited staged sink; neither is closed or retained. */
internal class OrdinaryPngWriter(private val budget: ImageAllocationBudget) {
    fun write(image: RgbImage, sink: OutputStream, checkpoint: () -> Unit) {
        checkpoint()
        requireOrdinaryPngApi()
        // Same opaque row-packing pattern as SrgbPreviewBitmap, without involving a display lease. Charge bitmap + copies/workspace before packing.
        try {
            budget.reserve(pngNativeBytes(image)).use {
                checkpoint()
                val bitmap = try {
                    Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
                } catch (_: IllegalArgumentException) {
                    pngRefuse(OperationErrorCode.PROCESSING_FAILED, "export_png_bitmap_failed")
                }
                try {
                    bitmap.density = Bitmap.DENSITY_NONE
                    if (bitmap.width != image.width || bitmap.height != image.height ||
                        bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.colorSpace?.isSrgb != true || bitmap.hasAlpha()) {
                        pngRefuse(OperationErrorCode.PROCESSING_FAILED, "export_png_bitmap_failed")
                    }
                    val row = IntArray(image.width)
                    for (y in 0 until image.height) {
                        checkpoint()
                        for (x in row.indices) row[x] = opaqueArgb(image, x, y)
                        bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
                    }
                    checkpoint()
                    val output = BorrowedOutput(sink)
                    // A native stream bridge may translate a thrown write into false. Preserve the original sentinel for the stage owner in either case.
                    val complete = try {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) // PNG ignores quality.
                    } finally {
                        output.failure?.let { throw it }
                    }
                    checkpoint()
                    if (!complete) pngRefuse(OperationErrorCode.PROCESSING_FAILED, "export_png_encode_failed")
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (_: OutOfMemoryError) {
            pngRefuse(OperationErrorCode.RESOURCE_LIMIT, "export_png_allocation_failed", retryable = true)
        }
    }

    private class BorrowedOutput(private val sink: OutputStream) : OutputStream() {
        var failure: Exception? = null
            private set

        override fun write(b: Int) = forward { sink.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = forward { sink.write(b, off, len) }

        private inline fun forward(write: () -> Unit) {
            failure?.let { throw it }
            try {
                write()
            } catch (error: Exception) {
                failure = error
                throw error
            }
        }
    }
}

internal fun requireOrdinaryPngApi() {
    if (Build.VERSION.SDK_INT < 28) pngRefuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "export_png_api_unavailable")
}

/** Admission, not a measured native limit: one 4N bitmap, one 4N potential copy, a 4W ARGB row, 1 MiB fixed codec workspace and encoded exposure if decoding. The caller's live RGB remains charged by its own owner. */
internal fun pngNativeBytes(image: RgbImage, encodedReadback: Long = 0): Long {
    try {
        val pixels = Math.multiplyExact(image.width.toLong(), image.height.toLong())
        val bitmap = Math.multiplyExact(4L, pixels)
        if (bitmap > Int.MAX_VALUE) pngRefuse(OperationErrorCode.RESOURCE_LIMIT, "export_png_bitmap_limit")
        val row = Math.multiplyExact(4L, image.width.toLong())
        val buffers = Math.addExact(Math.multiplyExact(2L, bitmap), row)
        return Math.addExact(Math.addExact(buffers, 1048576L), encodedReadback)
    } catch (_: ArithmeticException) {
        pngRefuse(OperationErrorCode.RESOURCE_LIMIT, "export_png_bitmap_limit")
    }
}

/** Only active samples; no stride bytes or newly allocated packed RGB. */
internal fun opaqueArgb(image: RgbImage, x: Int, y: Int): Int =
    (255 shl 24) or (image.getUnsigned(x, y, 0) shl 16) or (image.getUnsigned(x, y, 1) shl 8) or image.getUnsigned(x, y, 2)
