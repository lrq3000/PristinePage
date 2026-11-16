package app.pristinepage.image

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.image.RgbImage
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.model.*
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual platform codec/owned-file evidence. Synthetic asymmetric pixels/EXIF are generated locally and every file is retained. No picker, provider, Room ingest, API26, ICC or physical-performance claim follows. */
@RunWith(AndroidJUnit4::class)
class OrientationDecodeTest {
    private fun inspector(budget: ImageAllocationBudget): SourceInspector {
        val cls = try { Class.forName("app.pristinepage.image.decode.AndroidSourceInspector") }
        catch (_: ClassNotFoundException) { throw AssertionError("Actual Android source normalization is absent") }
        return try { cls.getConstructor(ImageAllocationBudget::class.java).newInstance(budget) as SourceInspector }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun file(bytes: ByteArray): File = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "t04-decode/${UUID.randomUUID()}.bin").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun decode(inspector: SourceInspector, file: File): Any {
        val method = inspector.javaClass.methods.single { it.name == "decodeFile" }
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try { method.invoke(inspector, file, sha(file.readBytes()), continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
    private fun image(decoded: Any): RgbImage = decoded.javaClass.getMethod("getImage").invoke(decoded) as RgbImage
    private fun inspection(decoded: Any): SourceInspection = decoded.javaClass.getMethod("getInspection").invoke(decoded) as SourceInspection
    private fun be(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun chunk(kind: String, data: ByteArray): ByteArray {
        val body = kind.toByteArray() + data; val crc = CRC32().apply { update(body) }
        return be(data.size) + body + be(crc.value.toInt())
    }
    private fun png(width: Int, height: Int, depth: Int, color: Int, row: ByteArray, extra: ByteArray = byteArrayOf()): ByteArray {
        val z = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(row) } }.toByteArray()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", be(width) + be(height) + byteArrayOf(depth.toByte(), color.toByte(), 0, 0, 0)) + extra + chunk("IDAT", z) + chunk("IEND", byteArrayOf())
    }
    private fun rgbaPng(): ByteArray = png(4, 1, 8, 6, byteArrayOf(0, -1, 0, 64, 0, 64, 32, 0, -128, -1, 0, 0, -1, -1, -1, -1, 1))
    private fun jpeg(): ByteArray {
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt(), 0xff00ffff.toInt(), 0xffff00ff.toInt())
        val bitmap = Bitmap.createBitmap(colors, 3, 2, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }.toByteArray() } finally { bitmap.recycle() }
    }
    private fun exif(jpeg: ByteArray, orientation: Int, type: Int = 3, count: Int = 1, tag: Int = 0x112): ByteArray {
        val tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(1).putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putShort(orientation.toShort()).putShort(0).putInt(0).array()
        val payload = "Exif\u0000\u0000".toByteArray() + tiff
        return jpeg.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (payload.size + 2).toByte()) + payload + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test fun pngStraightAlphaIsComposedExactlyAndOriginalBytesNeverChange() = runBlocking {
        val budget = ImageAllocationBudget(32L * 1024 * 1024); val service = inspector(budget); val bytes = rgbaPng(); val source = file(bytes)
        val decoded = decode(service, source)
        (decoded as AutoCloseable).use {
            assertEquals(listOf(255, 255, 255, 159, 143, 127, 255, 0, 0, 255, 255, 255), image(decoded).toPackedByteArray().map { it.toInt() and 255 })
            assertEquals("image/png", inspection(decoded).mime); assertEquals(1, inspection(decoded).exifOrientation)
            assertTrue(budget.usedBytes > 0)
        }
        assertEquals(0L, budget.usedBytes); assertArrayEquals(bytes, source.readBytes())
        val inspected = service.inspect(source, sha(bytes)); assertEquals(0L, budget.usedBytes); assertEquals("image/png", inspected.mime)
    }

    @Test fun jpegExifOneThroughEightPermuteTheActualDecodedPixelsOnce() = runBlocking {
        val budget = ImageAllocationBudget(32L * 1024 * 1024); val service = inspector(budget); val bytes = jpeg()
        val baseline = decode(service, file(bytes)); val base = (baseline as AutoCloseable).use { image(baseline).toPackedByteArray() }
        val permutations = listOf(intArrayOf(0, 1, 2, 3, 4, 5), intArrayOf(2, 1, 0, 5, 4, 3), intArrayOf(5, 4, 3, 2, 1, 0), intArrayOf(3, 4, 5, 0, 1, 2), intArrayOf(0, 3, 1, 4, 2, 5), intArrayOf(3, 0, 4, 1, 5, 2), intArrayOf(5, 2, 4, 1, 3, 0), intArrayOf(2, 5, 1, 4, 0, 3))
        for (orientation in 1..8) {
            val original = exif(bytes, orientation); val source = file(original); val decoded = decode(service, source)
            (decoded as AutoCloseable).use {
                val info = inspection(decoded); val rgb = image(decoded)
                assertEquals(orientation, info.exifOrientation); assertEquals(if (orientation >= 5) 2 else 3, rgb.width); assertEquals(if (orientation >= 5) 3 else 2, rgb.height)
                val expected = permutations[orientation - 1].flatMap { listOf(base[it * 3], base[it * 3 + 1], base[it * 3 + 2]) }.toByteArray()
                assertArrayEquals(expected, rgb.toPackedByteArray())
            }
            assertArrayEquals(original, source.readBytes()); assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun invalidExifAndTruncatedBytesRefuseWithoutLeakingTrackedMemory() = runBlocking {
        val budget = ImageAllocationBudget(32L * 1024 * 1024); val service = inspector(budget)
        for (bytes in listOf(exif(jpeg(), 0), exif(jpeg(), 9), rgbaPng().dropLast(1).toByteArray(), jpeg().dropLast(2).toByteArray())) {
            val source = file(bytes)
            val error = try { service.inspect(source, sha(bytes)); throw AssertionError("Invalid source decoded") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INVALID_INPUT, error.code); assertEquals(0L, budget.usedBytes); assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test fun changedByteIdentityCannotCreateAnInspection() = runBlocking {
        val budget = ImageAllocationBudget(32L * 1024 * 1024); val service = inspector(budget); val source = file(rgbaPng())
        val error = try { service.inspect(source, "0".repeat(64)); throw AssertionError("Wrong byte identity accepted") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); assertEquals(0L, budget.usedBytes)
    }

    @Test fun retainedDecodeHoldsTheSharedPermitUntilItsWholeResultCloses() = runBlocking {
        val budget = ImageAllocationBudget(1_500_000); val service = inspector(budget); val source = file(rgbaPng()); val first = decode(service, source)
        try {
            val held = budget.usedBytes
            val error = try { service.inspect(source, sha(source.readBytes())); throw AssertionError("Concurrent decode escaped accounting") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, error.code); assertEquals(held, budget.usedBytes)
        } finally { (first as AutoCloseable).close() }
        assertEquals(0L, budget.usedBytes); service.inspect(source, sha(source.readBytes())); assertEquals(0L, budget.usedBytes)
    }

    @Test fun lowBitGrayscaleAndPalettePngDecodeToTheirExactOpaqueColors() = runBlocking {
        val budget = ImageAllocationBudget(32L * 1024 * 1024); val service = inspector(budget)
        val grayscale = png(8, 1, 1, 0, byteArrayOf(0, 0x5a))
        val palette = chunk("PLTE", byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1))
        val indexed = png(4, 1, 2, 3, byteArrayOf(0, 0x1b), palette)
        val expected = listOf(listOf(0, 255, 0, 255, 255, 0, 255, 0).flatMap { listOf(it, it, it) }, listOf(255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255))
        for ((index, bytes) in listOf(grayscale, indexed).withIndex()) {
            val source = file(bytes); val decoded = decode(service, source)
            (decoded as AutoCloseable).use { assertEquals(expected[index], image(decoded).toPackedByteArray().map { it.toInt() and 255 }) }
            assertEquals(0L, budget.usedBytes); assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test fun malformedPresentExifAndDeclaredNonSrgbColorRefuseBeforeCodecAcceptance() = runBlocking {
        val budget = ImageAllocationBudget(32L * 1024 * 1024); val service = inspector(budget); val ordinary = jpeg()
        // Counts stay tiny here: the billion-count allocation adversary belongs ONLY to the bounded pure-reader tests, never an old general parser or native codec.
        val cases = listOf(exif(ordinary, 1, type = 0) to OperationErrorCode.INVALID_INPUT, exif(ordinary, 1, count = 2) to OperationErrorCode.INVALID_INPUT, exif(ordinary, 2, tag = 0xa001) to OperationErrorCode.UNSUPPORTED_FORMAT)
        for ((bytes, code) in cases) {
            val source = file(bytes)
            val error = try { service.inspect(source, sha(bytes)); throw AssertionError("Malformed/unsupported EXIF was guessed as absence/sRGB") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(code, error.code); assertEquals(0L, budget.usedBytes); assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test fun completeContainerWithCorruptCompressedPixelsCannotReturnAnInspection() = runBlocking {
        val budget = ImageAllocationBudget(32L * 1024 * 1024); val service = inspector(budget)
        val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", be(3) + be(2) + byteArrayOf(8, 2, 0, 0, 0)) + chunk("IDAT", byteArrayOf(1, 2, 3, 4)) + chunk("IEND", byteArrayOf())
        val source = file(bytes)
        val error = try { service.inspect(source, sha(bytes)); throw AssertionError("Corrupt compressed pixels were accepted from a valid CRC/container") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.INVALID_INPUT, error.code); assertEquals(0L, budget.usedBytes); assertArrayEquals(bytes, source.readBytes())
    }
}
