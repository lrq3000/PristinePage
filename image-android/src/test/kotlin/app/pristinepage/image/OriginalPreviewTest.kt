package app.pristinepage.image

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.CommittedSource
import app.pristinepage.data.repository.CommittedSources
import app.pristinepage.data.repository.ScopedCommittedOriginals
import app.pristinepage.image.decode.*
import app.pristinepage.image.preview.OriginalPreviewRaster
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual loader, SourceDecoder identity checks, coordinates, RGB ownership and shared accounting; only Room/private-byte lookup and Android codec are substituted. Native codec/display/lifecycle remain separate consuming gates. No test file is written. */
class OriginalPreviewTest {
    @Test fun smallerDisplayDoesNotRedefineFullSourceIdentityAndUsesIndependentHalfPixelSamples() = runBlocking<Unit> {
        val f = Fixture(); f.load(2).use { preview ->
            assertEquals(f.id.value, preview.sourceId); assertEquals("a".repeat(64), preview.sourceByteHash)
            assertEquals(f.info, preview.inspection); assertEquals(PixelSize(4, 2), preview.inspection.normalizedSize)
            assertEquals(OutputSpec(PixelSize(2, 1), RenderPurpose.PREVIEW), preview.output)
            assertArrayEquals(byteArrayOf(60, 60, 100, 229.toByte(), 100, 100), preview.image.toPackedByteArray())
            assertTrue(f.budget.usedBytes > 0); assertArrayEquals(f.pixels, f.pixelsBefore)
        }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun noUpscalingKeepsExactRgbChannelsAndNoFullSourceLeaseEscapes() = runBlocking<Unit> {
        val f = Fixture(); val result = f.load(1024)
        assertEquals(PixelSize(4, 2), result.output.size); assertArrayEquals(f.pixels, result.image.toPackedByteArray())
        assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.image.toPackedByteArray() }
        result.close(); result.close(); assertEquals(0L, f.budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { result.image.toPackedByteArray() }
    }

    @Test fun singletonPreviewIsStillAnExplicitApproximationNotAnUpscaledDetailRaster() = runBlocking<Unit> {
        val f = Fixture(); f.load(1).use {
            assertEquals(PixelSize(1, 1), it.output.size); assertEquals(RenderPurpose.PREVIEW, it.output.purpose)
            assertArrayEquals(byteArrayOf(160.toByte(), 80, 100), it.image.toPackedByteArray())
        }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun invalidIdOrUnboundedDisplayLimitRefusesBeforeAnySourceLookupOrCodecWork() = runBlocking<Unit> {
        for (limit in listOf(0, -1, 1025, Int.MAX_VALUE)) {
            val f = Fixture(); assertEquals(OperationErrorCode.INVALID_INPUT, error { f.load(limit) }.code)
            assertEquals(0, f.lookups); assertEquals(0, f.decoded); assertEquals(0L, f.budget.usedBytes)
        }
        val f = Fixture(); assertEquals(OperationErrorCode.INVALID_INPUT, error { f.load(2, "../arbitrary") }.code)
        assertEquals(0, f.lookups); assertEquals(0, f.decoded)
    }

    @Test fun displayAdmissionFailureClosesTheAlreadyDecodedSourceWithoutPublishingAnImage() = runBlocking<Unit> {
        // The existing4x2 fake decode estimate is4 encoded+14*8 pixels+4*4 row+1MiB workspace =1,048,708bytes. A one-byte remainder cannot admit even this two-pixel preview; no huge allocation is attempted.
        val f = Fixture(1_048_709)
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, error { f.load(2) }.code)
        assertNotNull(f.lastDecoded); assertEquals(0L, f.budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.image.toPackedByteArray() }
    }

    @Test fun metadataChangingBetweenSizingAndDecodeCannotPublishUnderTheEarlierBinding() = runBlocking<Unit> {
        val f = Fixture(); f.changeAfterSizing = true
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error { f.load(2) }.code)
        assertEquals(0L, f.budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.image.toPackedByteArray() }
    }

    @Test fun canceledCallerCannotStartSourceWorkOrLeakAReservation() = runBlocking<Unit> {
        val f = Fixture(); var propagated = false
        launch {
            coroutineContext.cancel()
            try { f.load(2).close(); fail("Canceled preview was published") }
            catch (_: CancellationException) { propagated = true }
        }.join()
        assertTrue(propagated); assertEquals(0, f.lookups); assertEquals(0, f.decoded); assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun scopedOriginalPreviewNeedsOnlyOneVerifiedLoanAndKeepsExactPixels() = runBlocking<Unit> {
        val f = Fixture()
        f.loadScoped(2).use { preview ->
            assertEquals(OutputSpec(PixelSize(2, 1), RenderPurpose.PREVIEW), preview.output)
            assertEquals(f.info, preview.inspection)
            assertArrayEquals(byteArrayOf(60, 60, 100, 229.toByte(), 100, 100), preview.image.toPackedByteArray())
            assertEquals(1, f.lookups)
            assertEquals(1, f.decoded)
            assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.image.toPackedByteArray() }
        }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun scopedOriginalRefusalAfterDecodeDoesNotDeliverOrRetainPixels() = runBlocking<Unit> {
        val f = Fixture()
        assertEquals(OperationErrorCode.PERMISSION_LOST, error { f.loadScoped(2, refuseAfterDecode = true) }.code)
        assertEquals(1, f.decoded)
        assertEquals(0L, f.budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.image.toPackedByteArray() }
    }

    private class Fixture(capacity: Long = 4L * 1024 * 1024) {
        val id = SourceId("11111111-1111-4111-8111-111111111111")
        val pixels = intArrayOf(0,0,0,100,20,40,200,40,80,240,60,120,20,100,200,120,120,160,220,140,120,255,160,80).map(Int::toByte).toByteArray()
        val pixelsBefore = pixels.copyOf()
        val info = SourceInspection("image/png", PixelSize(4,2), PixelSize(4,2), 1, null, "fixture-normalizer-v1", "b".repeat(64))
        val budget = ImageAllocationBudget(capacity)
        var lookups = 0; var decoded = 0; var changeAfterSizing = false; var lastDecoded: DecodedSource? = null
        private val file = File("opaque-fixture-not-opened.bin")
        private var returnedInfo = info
        private val sources = CommittedSources { requested ->
            assertEquals(id, requested); lookups++
            returnedInfo = if (changeAfterSizing && lookups > 1) info.copy(sourcePixelHash = "c".repeat(64)) else info
            CommittedSource(id, SourceBlobId("22222222-2222-4222-8222-222222222222"), "a".repeat(64), 4, returnedInfo, file)
        }
        private val codec = SourceFileDecoder { input, expected ->
            assertEquals(file, input); assertEquals("a".repeat(64), expected); decoded++
            val permit = DecodeAdmission(budget).admit(4,4,2,1)
            DecodedSource(returnedInfo, RgbImage(4,2,12,pixels), permit, expected).also { lastDecoded = it }
        }
        private val decoder = SourceDecoder(sources, codec)

        suspend fun loadScoped(limit: Int, refuseAfterDecode: Boolean = false): OriginalPreviewRaster {
            val scoped = ScopedCommittedOriginals { requested, expected, consume ->
                assertNull(expected)
                val original = sources.load(requested)
                consume(original.binding(), file)
                if (refuseAfterDecode) throw RepositoryFailure(OperationError(OperationErrorCode.PERMISSION_LOST, true, "library_reconnect_required", null))
            }
            val type = Class.forName("app.pristinepage.image.preview.OriginalPreviewLoader")
            val constructor = try { type.getConstructor(SourceDecoder::class.java, ImageAllocationBudget::class.java) }
            catch (_: NoSuchMethodException) { throw AssertionError("The loader still requires unscoped original lookup") }
            return invoke(type, constructor.newInstance(SourceDecoder(scoped, codec), budget), id.value, limit)
        }

        suspend fun load(limit: Int, source: String = id.value): OriginalPreviewRaster {
            val type = try { Class.forName("app.pristinepage.image.preview.OriginalPreviewLoader") }
            catch (_: ClassNotFoundException) { throw AssertionError("Verified original preview loader is absent") }
            val loader = type.getConstructor(CommittedSources::class.java, SourceDecoder::class.java, ImageAllocationBudget::class.java).newInstance(sources, decoder, budget)
            return invoke(type, loader, source, limit)
        }

        private suspend fun invoke(type: Class<*>, loader: Any, source: String, limit: Int): OriginalPreviewRaster {
            return kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
                try { type.getMethod("load", String::class.java, Int::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java).invoke(loader, source, limit, continuation) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
    }
    private companion object {
        suspend fun error(action: suspend () -> OriginalPreviewRaster): OperationError {
            try { action().close(); throw AssertionError("Unsafe preview unexpectedly accepted") }
            catch (failure: RepositoryFailure) { return failure.error }
        }
    }
}
