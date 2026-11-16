package app.pristinepage.image

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.CommittedSource
import app.pristinepage.data.repository.CommittedSources
import app.pristinepage.image.decode.DecodeAdmission
import app.pristinepage.image.decode.DecodedSource
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Only unavailable Android codec/Room lookup are substituted. Actual SourceDecoder business checks, complete identity values, RGB storage and shared reservations execute on host; actual cross-module Room/codec tests are required separately. */
class CommittedSourceDecodeTest {
    private class Fixture {
        val id = SourceId(UUID.randomUUID().toString())
        val pixels = ByteArray(18) { (it + 1).toByte() }
        private val parent = File(System.getProperty("user.dir"), "build/test-committed-decode").apply { mkdirs() }
        val file = Files.createTempFile(parent.toPath(), "source-", ".bin").toFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val hash = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
        val info: SourceInspection
        var stored: CommittedSource
        var actual: SourceInspection
        var actualBytes = hash
        val budget = ImageAllocationBudget(4L * 1024 * 1024)
        var loaded = 0; var decoded = 0
        var cancelOnDelivery = false
        var onLoad: () -> Unit = {}
        var onDecode: () -> Unit = {}
        var lastImage: RgbImage? = null
        init {
            // Independent tagged encoding of literal fixture RGB; do not use the production source hash as this consumer's oracle.
            val stream = ByteArrayOutputStream(); val version = "fixture-normalizer-v1"
            DataOutputStream(stream).use { out -> out.write("pristinepage-source-pixels-v1\u0000".toByteArray()); out.writeInt(version.toByteArray().size); out.write(version.toByteArray()); out.writeInt(3); out.writeInt(2); out.writeLong(18); out.write(pixels) }
            val pixelHash = MessageDigest.getInstance("SHA-256").digest(stream.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            info = SourceInspection("image/png", PixelSize(3, 2), PixelSize(3, 2), 1, null, version, pixelHash)
            actual = info
            stored = CommittedSource(id, SourceBlobId(UUID.randomUUID().toString()), hash, 4, info, file)
        }
        suspend fun decode(output: OutputSpec = OutputSpec(PixelSize(1, 1), RenderPurpose.PREVIEW), source: String = id.value): DecodedSource {
            val type = try { Class.forName("app.pristinepage.image.decode.SourceDecoder") }
            catch (_: ClassNotFoundException) { throw AssertionError("Committed-source consumer decoder is absent") }
            val port = Class.forName("app.pristinepage.image.decode.SourceFileDecoder")
            val sources = CommittedSources { requested -> assertEquals(id, requested); loaded++; onLoad(); stored }
            val codec = Proxy.newProxyInstance(port.classLoader, arrayOf(port)) { _, method, args ->
                assertEquals("decodeFile", method.name); assertEquals(file, args[0]); assertEquals(hash, args[1]); decoded++; onDecode()
                val permit = DecodeAdmission(budget).admit(4, 3, 2, 1)
                val image = RgbImage(3, 2, 9, pixels).also { lastImage = it }
                try {
                    val result = DecodedSource::class.java.declaredConstructors.single { it.parameterCount == 4 }.newInstance(actual, image, permit, actualBytes)
                    if (cancelOnDelivery) (args[2] as Continuation<*>).context.cancel(CancellationException("synthetic delivery cancellation"))
                    result
                } catch (failure: Throwable) { image.close(); permit.close(); if (failure is InvocationTargetException) throw failure.targetException else throw failure }
            }
            val consumer = try { type.constructors.single { it.parameterCount == 2 }.newInstance(sources, codec) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try { type.methods.single { it.name == "decode" }.invoke(consumer, source, output, continuation) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
    }
    private suspend fun error(f: Fixture, output: OutputSpec = OutputSpec(PixelSize(1, 1), RenderPurpose.CARD), source: String = f.id.value): OperationError = try { f.decode(output, source).close(); throw AssertionError("Changed/invalid committed source was accepted") } catch (failure: RepositoryFailure) { failure.error }

    @Test fun previewDestinationCannotReplaceFullSourcePixelsHashOrOwnedReservation() = runTest {
        val f = Fixture(); val result = f.decode()
        result.use {
            assertEquals(PixelSize(3, 2), it.inspection.normalizedSize); assertEquals(f.info, it.inspection); assertArrayEquals(f.pixels, it.image.toPackedByteArray())
            assertEquals(f.hash, result.javaClass.getMethod("getSourceByteHash").invoke(result)); assertTrue(f.budget.usedBytes > 0)
        }
        result.close(); assertEquals(0L, f.budget.usedBytes); assertEquals(1, f.loaded); assertEquals(1, f.decoded)
    }

    @Test fun nonSourceAspectDestinationDoesNotResampleBeforeGeometry() = runTest {
        val f = Fixture(); f.decode(OutputSpec(PixelSize(2, 4), RenderPurpose.EXPORT)).use { assertEquals(3, it.image.width); assertEquals(2, it.image.height); assertArrayEquals(f.pixels, it.image.toPackedByteArray()) }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun invalidOpaqueIdAndImpossibleDestinationRefuseBeforeLookupOrCodec() = runTest {
        val f = Fixture(); assertEquals(OperationErrorCode.INVALID_INPUT, error(f, source = "../external").code)
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, error(f, OutputSpec(PixelSize(Int.MAX_VALUE, Int.MAX_VALUE), RenderPurpose.EXPORT)).code)
        assertEquals(0, f.loaded); assertEquals(0, f.decoded); assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun wrongLookupBindingCannotDecodeAnUnrelatedPrivateSource() = runTest {
        val f = Fixture(); val old = f.stored; f.stored = CommittedSource(SourceId(UUID.randomUUID().toString()), old.sourceBlobId, old.sourceByteHash, old.byteCount, old.inspection, old.file)
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(f).code); assertEquals(0, f.decoded); assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun unavailableHistoricalConversionFailsWithoutRehashingOrLeakingTheRaster() = runTest {
        val f = Fixture(); f.actual = f.info.copy(conversionFingerprint = "new-unaccepted-conversion")
        assertEquals(OperationErrorCode.UNSUPPORTED_VERSION, error(f).code); assertEquals(f.info, f.stored.inspection); assertEquals(0L, f.budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { f.lastImage!!.toPackedByteArray() }
    }

    @Test fun bytePixelSizeExifProfileAndMimeDriftCannotReturnANewSourceIdentity() = runTest {
        for (mutation in 0..5) {
            val f = Fixture()
            when (mutation) {
                0 -> f.actualBytes = "e".repeat(64)
                1 -> f.actual = f.info.copy(sourcePixelHash = "b".repeat(64))
                2 -> f.actual = f.info.copy(encodedSize = PixelSize(2, 3), normalizedSize = PixelSize(2, 3))
                3 -> f.actual = f.info.copy(exifOrientation = 2)
                4 -> f.actual = f.info.copy(profileSha256 = "c".repeat(64))
                else -> f.actual = f.info.copy(mime = "image/jpeg")
            }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(f).code); assertEquals(f.info, f.stored.inspection); assertEquals(0L, f.budget.usedBytes)
            assertThrows(IllegalStateException::class.java) { f.lastImage!!.toPackedByteArray() }
        }
    }

    @Test fun lookupCancellationPropagatesAndCannotCallTheCodec() = runTest {
        val f = Fixture(); f.onLoad = { throw CancellationException("synthetic lookup cancellation") }
        try { f.decode(); throw AssertionError("Cancellation was swallowed") } catch (_: CancellationException) { /* No owned image was delivered. */ }
        assertEquals(0, f.decoded); assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun cancellationAfterCodecDeliveryClosesUndeliveredImageAndReservation() = runTest {
        val f = Fixture(); f.cancelOnDelivery = true; var canceled = false
        launch { try { f.decode().close(); throw AssertionError("Canceled raster was delivered") } catch (_: CancellationException) { canceled = true } }.join()
        assertTrue(canceled); assertEquals(0L, f.budget.usedBytes); assertThrows(IllegalStateException::class.java) { f.lastImage!!.toPackedByteArray() }
    }

    @Test fun codecFailurePropagatesWithoutFallbackOrMutationOfRecordedBinding() = runTest {
        val f = Fixture(); val failure = OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_codec_read_failed", f.id.value)
        f.onDecode = { throw RepositoryFailure(failure) }
        assertEquals(failure, error(f)); assertEquals(f.info, f.stored.inspection); assertEquals(0L, f.budget.usedBytes)
    }
}
