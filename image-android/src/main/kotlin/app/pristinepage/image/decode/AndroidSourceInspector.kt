package app.pristinepage.image.decode

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceInspection
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The whole result owns its image AND its tracked decode reservation. The image is borrowed within this scope: consumers must not retain a separate RgbImage lease past close without arranging shared-budget ownership transfer. */
class DecodedSource internal constructor(val inspection: SourceInspection, val image: RgbImage, private val permit: DecodePermit, val sourceByteHash: String) : AutoCloseable {
    init { requireSha256(sourceByteHash) }
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) { try { image.close() } finally { permit.close() } } }
}

/** Closed private bytes only; no URI/provider/database access, original mutation or source downsampling. API28+'s strict partial-image policy is used first. API26/27 and embedded ICC support remain unavailable until separately validated, not silently emulated through a permissive partial decoder. */
class AndroidSourceInspector @JvmOverloads constructor(private val budget: ImageAllocationBudget, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : SourceInspector, SourceFileDecoder {
    override suspend fun inspect(file: File, expectedByteSha256: String): SourceInspection = decodeFile(file, expectedByteSha256).use { it.inspection }

    override suspend fun decodeFile(file: File, expectedByteSha256: String): DecodedSource {
        var completed: DecodedSource? = null
        try {
            return withContext(dispatcher) {
                val context = currentCoroutineContext()
                context.ensureActive()
                requireSha256(expectedByteSha256)
                if (Build.VERSION.SDK_INT < 28) refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "strict_decode_api_unavailable")
                if (!file.isFile) refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "source_file_unavailable")
                // Bound and account the parser workspace BEFORE inspecting metadata. This reservation ends once only scalar header values remain; the larger decode permit then accounts all live raster buffers/workspace.
                val parserBytes = 262144L + 3 * minOf(maxOf(0L, file.length()), 1048576L)
                val header = budget.reserve(parserBytes).use {
                    val parsed = SourceContainer.read(file) { context.ensureActive() }
                    verifyBytes(file, expectedByteSha256) { context.ensureActive() }
                    parsed
                }
                val orientation = header.exifOrientation
                context.ensureActive()
                val permit = DecodeAdmission(budget).admit(file.length(), header.size.width.toLong(), header.size.height.toLong(), orientation)
                var transferred = false
                try {
                    val bitmap = decodeStrict(file, header, permit) { context.ensureActive() }
                    try {
                        context.ensureActive()
                        // ImageDecoder applies EXIF itself; native asymmetric1..8 fixtures must verify this. Do NOT pass the stored EXIF to CanonicalSourcePixels a second time.
                        val image = CanonicalSourcePixels.normalize(bitmap.width, bitmap.height, 1, { y, row -> bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1) }) { context.ensureActive() }
                        try {
                            val fingerprint = conversionFingerprint()
                            val hash = CanonicalSourcePixels.hash(image, fingerprint) { context.ensureActive() }
                            val inspection = SourceInspection(header.mime, header.size, permit.size, orientation, null, fingerprint, hash)
                            context.ensureActive()
                            DecodedSource(inspection, image, permit, expectedByteSha256).also { completed = it; transferred = true }
                        } finally { if (!transferred) image.close() }
                    } finally { bitmap.recycle() }
                } finally { if (!transferred) permit.close() }
            }
        } catch (canceled: CancellationException) {
            // withContext can cancel on delivery after a completed native decode. Close the undelivered owned result as well as all in-block failure paths.
            completed?.close(); throw canceled
        } catch (failure: RepositoryFailure) { completed?.close(); throw failure }
        catch (_: FileNotFoundException) { completed?.close(); refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "source_file_unavailable") }
        catch (_: IllegalArgumentException) { completed?.close(); refuse(OperationErrorCode.INVALID_INPUT, "invalid_source_decode") }
        catch (_: IOException) { completed?.close(); refuse(OperationErrorCode.STORAGE_FAILED, "source_decode_read_failed", true) }
    }

    @TargetApi(28)
    private fun decodeStrict(file: File, header: EncodedSourceHeader, permit: DecodePermit, checkpoint: () -> Unit): Bitmap {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                checkpoint()
                if (info.isAnimated || info.mimeType != header.mime) refuse(OperationErrorCode.UNSUPPORTED_FORMAT, "source_codec_format_unsupported")
                if (info.size.width != permit.size.width || info.size.height != permit.size.height) refuse(OperationErrorCode.INVALID_INPUT, "source_decode_dimension_mismatch")
                if (info.colorSpace != null && !checkNotNull(info.colorSpace).isSrgb) refuse(OperationErrorCode.UNSUPPORTED_FORMAT, "source_profile_unsupported")
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isUnpremultipliedRequired = true
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }
        } catch (_: ImageDecoder.DecodeException) { refuse(OperationErrorCode.INVALID_INPUT, "incomplete_source_decode") }
        try {
            checkpoint()
            if (bitmap.width != permit.size.width || bitmap.height != permit.size.height) refuse(OperationErrorCode.INVALID_INPUT, "source_decode_dimension_mismatch")
            if (bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.colorSpace?.isSrgb != true || (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap())) refuse(OperationErrorCode.UNSUPPORTED_FORMAT, "source_codec_color_unsupported")
            return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    private fun verifyBytes(file: File, expected: String, checkpoint: () -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                checkpoint()
                val n = input.read(buffer); if (n < 0) break
                count += n
                if (count > 104857600) refuse(OperationErrorCode.RESOURCE_LIMIT, "encoded_source_limit")
                digest.update(buffer, 0, n)
            }
        }
        if (count != file.length() || hex(digest.digest()) != expected) refuse(OperationErrorCode.INTEGRITY_FAILED, "source_byte_identity_mismatch")
        checkpoint()
    }

    private fun conversionFingerprint(): String = "android-imagedecoder-v2/api${Build.VERSION.SDK_INT}/${hex(MessageDigest.getInstance("SHA-256").digest(Build.FINGERPRINT.toByteArray(Charsets.UTF_8))).take(16)}/srgb-straight-white-exif-v1"
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun refuse(code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing = throw RepositoryFailure(OperationError(code, retryable, key, null))
}
