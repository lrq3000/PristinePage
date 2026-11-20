package app.pristinepage.image.export

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.*
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceContainer
import java.io.File
import java.io.OutputStream
import kotlin.math.abs

/** No source metadata enters the pixels-only writer. Verify encoded sampling, complete decode and bounded lossy fidelity separately. */
internal class AlbumJpeg(private val budget: ImageAllocationBudget) {
    fun write(image: RgbImage, output: OutputStream, settings: JpegSettings, checkpoint: () -> Unit) {
        require(settings == DEFAULT)
        budget.reserve(pngNativeBytes(image)).use {
            val bitmap = albumBitmap(image, checkpoint)
            try {
                var failure: Exception? = null
                val sink = object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        failure?.let { throw it }
                        try { checkpoint(); output.write(b, off, len) }
                        catch (error: Exception) { failure = error; throw error }
                    }
                }
                val encoded = try { bitmap.compress(Bitmap.CompressFormat.JPEG, settings.quality, sink) }
                    finally { failure?.let { throw it } }
                checkpoint()
                if (!encoded) refuse(OperationErrorCode.PROCESSING_FAILED, "export_jpeg_encode")
            } finally { bitmap.recycle() }
        }
    }

    fun verify(file: File, image: RgbImage, settings: JpegSettings, checkpoint: () -> Unit) {
        require(settings == DEFAULT)
        if (Build.VERSION.SDK_INT < 28) refuse(OperationErrorCode.UNSUPPORTED_VERSION, "export_jpeg_readback_api")
        budget.reserve(pngNativeBytes(image, file.length())).use {
            val size = PixelSize(image.width, image.height)
            SourceContainer.verifyExportJpeg(file, size, settings.expectedSampling, checkpoint)
            val decoded = decode(file, size, checkpoint)
            try {
                require(decoded.width == size.width && decoded.height == size.height && decoded.colorSpace?.isSrgb == true)
                val row = IntArray(size.width)
                var delta = 0L
                for (y in 0 until size.height) {
                    checkpoint()
                    decoded.getPixels(row, 0, size.width, 0, y, size.width, 1)
                    for (x in row.indices) {
                        require(row[x] ushr 24 == 255)
                        for (channel in 0..2) {
                            delta += abs(((row[x] shr (16 - 8 * channel)) and 255) - image.getUnsigned(x, y, channel))
                        }
                    }
                }
                // Gross loss/substitution guard, not release-level edge/fidelity acceptance for lossy output.
                if (delta > 20L * 3 * size.width * size.height) refuse(OperationErrorCode.INTEGRITY_FAILED, "export_jpeg_pixel_mismatch")
            } finally { decoded.recycle() }
        }
    }

    @TargetApi(28)
    private fun decode(file: File, size: PixelSize, checkpoint: () -> Unit): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { reader, info, _ ->
            checkpoint()
            if (info.isAnimated || info.mimeType != "image/jpeg" || info.size.width != size.width || info.size.height != size.height) {
                refuse(OperationErrorCode.INTEGRITY_FAILED, "export_jpeg_readback_mismatch")
            }
            reader.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            reader.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            reader.setOnPartialImageListener { false }
        }

    companion object {
        val DEFAULT = JpegSettings(92, "android_bitmap", 1, JpegChromaPolicy.PLATFORM_VERIFIED, JpegSampling.YUV420)
    }
}

internal fun albumBitmap(image: RgbImage, checkpoint: () -> Unit): Bitmap {
    val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
    try {
        val row = IntArray(image.width)
        bitmap.density = Bitmap.DENSITY_NONE
        for (y in 0 until image.height) {
            checkpoint()
            for (x in row.indices) row[x] = opaqueArgb(image, x, y)
            bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
        return bitmap
    } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
    }
}

internal fun refuse(code: OperationErrorCode, key: String): Nothing =
    throw RepositoryFailure(OperationError(code, false, key, null))
