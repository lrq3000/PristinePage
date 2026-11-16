package app.pristinepage.image

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

/** Actual container bytes, not a fake decoder. Small synthetic structure vectors intentionally do not establish entropy/pixel decode acceptance. Retain isolated temporary files for manual cleanup. */
class SourceContainerTest {
    private fun read(bytes: ByteArray): Any {
        val cls = try { Class.forName("app.pristinepage.image.decode.SourceContainer") }
        catch (_: ClassNotFoundException) { throw AssertionError("Bounded actual-content inspection is absent") }
        val root = File(System.getProperty("user.dir"), "build/test-source-containers").apply { mkdirs() }
        val file = Files.createTempFile(root.toPath(), "synthetic-", ".not-an-image-extension").toFile().apply { writeBytes(bytes) }
        return try { cls.methods.single { it.name == "read" }.invoke(cls.getField("INSTANCE").get(null), file, {})!! }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun get(value: Any, name: String): Any = value.javaClass.methods.single { it.name == name }.invoke(value)!!
    private fun reject(code: OperationErrorCode, bytes: ByteArray) {
        val error = try { read(bytes); throw AssertionError("Unsafe encoded container was accepted") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(code, error.code)
    }
    private fun be(vararg values: Int): ByteArray = ByteArrayOutputStream().also { b -> DataOutputStream(b).use { d -> values.forEach(d::writeInt) } }.toByteArray()
    private fun chunk(kind: String, data: ByteArray): ByteArray {
        val body = kind.toByteArray(Charsets.US_ASCII) + data; val crc = CRC32().apply { update(body) }
        return be(data.size) + body + be(crc.value.toInt())
    }
    private fun png(w: Int = 3, h: Int = 2, extra: ByteArray = byteArrayOf(), depth: Int = 8, color: Int = 2): ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", be(w, h) + byteArrayOf(depth.toByte(), color.toByte(), 0, 0, 0)) + extra + chunk("IDAT", byteArrayOf(0)) + chunk("IEND", byteArrayOf())
    private fun segment(marker: Int, data: ByteArray): ByteArray { val n = data.size + 2; return byteArrayOf(-1, marker.toByte(), (n ushr 8).toByte(), n.toByte()) + data }
    private fun jpeg(components: Int = 3, extra: ByteArray = byteArrayOf()): ByteArray {
        val sof = byteArrayOf(8, 0, 2, 0, 3, components.toByte()) + ByteArray(components * 3) { if (it % 3 == 0) (it / 3 + 1).toByte() else 0x11 }
        return byteArrayOf(-1, -40) + extra + segment(0xc0, sof) + segment(0xda, byteArrayOf(1, 1, 0, 0, 63, 0)) + byteArrayOf(1, 2, 3, -1, 0, 4, -1, -39)
    }
    private fun webp(extra: ByteArray = byteArrayOf()): ByteArray {
        val data = byteArrayOf(0x2f, 2, 0x40, 0, 0) // VP8L signature,width3,height2; entropy intentionally absent.
        val image = "VP8L".toByteArray() + byteArrayOf(5, 0, 0, 0) + data + byteArrayOf(0)
        val body = "WEBP".toByteArray() + extra + image; val n = body.size
        return "RIFF".toByteArray() + byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()) + body
    }

    @Test fun actualMagicSelectsStaticFormatWithoutTrustingAFileExtension() {
        for ((mime, bytes) in listOf("image/png" to png(), "image/jpeg" to jpeg(), "image/webp" to webp())) {
            val header = read(bytes); assertEquals(mime, get(header, "getMime")); assertEquals(PixelSize(3, 2), get(header, "getSize"))
        }
    }

    @Test fun unsupportedMagicAndMultiframeContainersCannotBecomeAnOrdinaryPhoto() {
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, "%PDF-synthetic".toByteArray())
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, png(extra = chunk("acTL", be(2, 0))))
        val vp8x = "VP8X".toByteArray() + byteArrayOf(10, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 1, 0, 0)
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, webp(vp8x))
    }

    @Test fun hugeDimensionsRejectBeforeAnyCompressedRasterAllocation() {
        reject(OperationErrorCode.RESOURCE_LIMIT, png(12001, 1))
        reject(OperationErrorCode.RESOURCE_LIMIT, png(8000, 6001))
        reject(OperationErrorCode.INVALID_INPUT, png(0, 2))
    }

    @Test fun pngCrcAndCompleteChunkStructureAreMandatory() {
        val bad = png(); bad[29] = (bad[29].toInt() xor 1).toByte()
        reject(OperationErrorCode.INVALID_INPUT, bad)
        reject(OperationErrorCode.INVALID_INPUT, png().dropLast(1).toByteArray())
        reject(OperationErrorCode.INVALID_INPUT, png() + byteArrayOf(1))
    }

    @Test fun truncatedJpegAndWebpNeverCountAsCompleteContainerStructure() {
        reject(OperationErrorCode.INVALID_INPUT, jpeg().dropLast(2).toByteArray())
        reject(OperationErrorCode.INVALID_INPUT, jpeg() + byteArrayOf(1))
        reject(OperationErrorCode.INVALID_INPUT, webp().dropLast(1).toByteArray())
    }

    @Test fun unsupportedColorDepthProfilesAndHdrRejectInsteadOfGuessingColor() {
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, png(depth = 16))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, png(extra = chunk("iCCP", byteArrayOf(1, 2, 3))))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, png(extra = chunk("cICP", byteArrayOf(9, 16, 0, 1))))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, jpeg(4))
        reject(OperationErrorCode.UNSUPPORTED_FORMAT, jpeg(extra = segment(0xe2, "ICC_PROFILE\u0000synthetic".toByteArray())))
    }

    @Test fun oversizedDeclaredMetadataDoesNotRequestItsClaimedArray() {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        reject(OperationErrorCode.INVALID_INPUT, signature + be(Int.MAX_VALUE) + "IHDR".toByteArray())
        reject(OperationErrorCode.INVALID_INPUT, byteArrayOf(-1, -40, -1, -31, 127, -1, 1))
    }

    @Test fun malformedExifCannotPassContainerPreflightIntoTheGeneralMetadataParser() {
        for ((type, count) in listOf(0 to 1, 3 to 1_000_000_000)) {
            val tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(1).putShort(0x112).putShort(type.toShort()).putInt(count).putInt(8).putInt(0).array()
            reject(OperationErrorCode.INVALID_INPUT, jpeg(extra = segment(0xe1, "Exif\u0000\u0000".toByteArray() + tiff)))
        }
    }

    @Test fun ordinaryLowDepthGrayscaleAndIndexedPngAreAdmittedForStrictDecode() {
        val gray = read(png(depth = 1, color = 0)); assertEquals(PixelSize(3, 2), get(gray, "getSize"))
        val paletteHeader = chunk("IHDR", be(3, 2) + byteArrayOf(2, 3, 0, 0, 0))
        val indexed = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + paletteHeader + chunk("PLTE", byteArrayOf(0, 0, 0, -1, -1, -1)) + chunk("IDAT", byteArrayOf(0)) + chunk("IEND", byteArrayOf())
        assertEquals(PixelSize(3, 2), get(read(indexed), "getSize"))
    }
}
