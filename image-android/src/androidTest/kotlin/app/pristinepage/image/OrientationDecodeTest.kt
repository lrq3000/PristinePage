package app.pristinepage.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.image.RgbImage
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.model.*
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import app.pristinepage.image.EncodedImageFixtures.be
import app.pristinepage.image.EncodedImageFixtures.chunk
import app.pristinepage.image.EncodedImageFixtures.exif
import app.pristinepage.image.EncodedImageFixtures.jpeg
import app.pristinepage.image.EncodedImageFixtures.png
import app.pristinepage.image.EncodedImageFixtures.rgbaPng
import app.pristinepage.image.EncodedImageFixtures.sha
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
    private suspend fun decode(inspector: SourceInspector, file: File): Any {
        val method = inspector.javaClass.methods.single { it.name == "decodeFile" }
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try { method.invoke(inspector, file, sha(file.readBytes()), continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
    private fun image(decoded: Any): RgbImage = decoded.javaClass.getMethod("getImage").invoke(decoded) as RgbImage
    private fun inspection(decoded: Any): SourceInspection = decoded.javaClass.getMethod("getInspection").invoke(decoded) as SourceInspection

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
        for (bytes in listOf(exif(jpeg(), 9), rgbaPng().dropLast(1).toByteArray(), jpeg().dropLast(2).toByteArray())) {
            val source = file(bytes)
            val error = try { service.inspect(source, sha(bytes)); throw AssertionError("Invalid source decoded") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INVALID_INPUT, error.code); assertEquals(0L, budget.usedBytes); assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test fun cameraJpegWithUndefinedOrientationAndTrailerDecodesUnrotatedWithoutChangingTheOriginal() = runBlocking {
        val budget = ImageAllocationBudget(32L * 1024 * 1024)
        val service = inspector(budget)
        val normal = jpeg()
        val baseline = decode(service, file(normal))
        val pixels = (baseline as AutoCloseable).use { image(baseline).toPackedByteArray() }
        val original = exif(normal, 0) + ByteArray(5420) { if (it % 5 == 0) 42 else 0 }
        val source = file(original)
        val compatible = decode(service, source)
        (compatible as AutoCloseable).use {
            assertEquals(1, inspection(compatible).exifOrientation)
            assertArrayEquals(pixels, image(compatible).toPackedByteArray())
        }
        assertArrayEquals(original, source.readBytes())
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun projectedCameraJpegPreservesValidRotationAndHasAnIndependentConversionIdentity() = runBlocking {
        val budget = ImageAllocationBudget(32L * 1024 * 1024)
        val service = inspector(budget)
        val normal = jpeg()
        val strict = decode(service, file(exif(normal, 6)))
        val fingerprint = inspection(strict).conversionFingerprint
        val expected = (strict as AutoCloseable).use { image(strict).toPackedByteArray() }
        val tiff = ByteBuffer.allocate(56).order(ByteOrder.BIG_ENDIAN).putShort(0x4d4d).putShort(42).putInt(8)
        tiff.putShort(2).putShort(0x112).putShort(3).putInt(1).putShort(6).putShort(0)
        tiff.putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(38).putInt(0)
        tiff.putShort(1).putShort(0x010d).putShort(7).putInt(0).putInt(0).putInt(0)
        val app1 = "Exif\u0000\u0000".toByteArray() + tiff.array()
        val bytes = normal.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (app1.size + 2).toByte()) +
            app1 + normal.copyOfRange(2, normal.size) + byteArrayOf(42, 0)
        val source = file(bytes)
        val projected = decode(service, source)
        (projected as AutoCloseable).use {
            assertEquals(6, inspection(projected).exifOrientation)
            assertNotEquals(fingerprint, inspection(projected).conversionFingerprint)
            assertArrayEquals(expected, image(projected).toPackedByteArray())
        }
        assertArrayEquals(bytes, source.readBytes())
        assertEquals(0L, budget.usedBytes)
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
