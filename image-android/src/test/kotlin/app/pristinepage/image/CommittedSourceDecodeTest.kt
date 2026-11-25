package app.pristinepage.image

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.CommittedSource
import app.pristinepage.data.repository.CommittedSourceBinding
import app.pristinepage.data.repository.CommittedSources
import app.pristinepage.data.repository.ExportSourceBinding
import app.pristinepage.data.repository.ProducerCommittedSources
import app.pristinepage.data.repository.ScopedCommittedOriginals
import app.pristinepage.image.decode.DecodeAdmission
import app.pristinepage.image.decode.DecodedSource
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.image.decode.SourceFileDecoder
import app.pristinepage.image.decode.PortableSourcePixels
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertFailsWith

/** Only unavailable Android codec/Room lookup are substituted. Actual SourceDecoder business checks, complete identity values, RGB storage and shared reservations execute on host; actual cross-module Room/codec tests are required separately. */
class CommittedSourceDecodeTest {
    @Test fun scopedOriginalIsLentThroughDecodeButNotThroughUseOfTheOwnedRaster() = runTest {
        val f = Fixture()
        var held = false
        var released = false
        val binding = CommittedSourceBinding(f.id, f.stored.sourceBlobId, f.hash, 4, f.info)
        val sources = ScopedCommittedOriginals { id, producer, read ->
            assertEquals(f.id, id)
            assertNull(producer)
            held = true
            try { read(binding, f.file) }
            finally { held = false; released = true }
        }
        val decoder = SourceDecoder(sources, SourceFileDecoder { file, hash ->
            assertTrue(held)
            assertEquals(f.file, file)
            assertEquals(f.hash, hash)
            DecodedSource(f.info, RgbImage(3, 2, 9, f.pixels), DecodeAdmission(f.budget).admit(4, 3, 2, 1), hash)
        })
        decoder.decode(f.id.value, OutputSpec(PixelSize(1, 1), RenderPurpose.PREVIEW)).use {
            assertTrue(released)
            assertFalse(held)
            assertArrayEquals(f.pixels, it.image.toPackedByteArray())
        }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun aSelectedOriginalRefusedAfterDecodeCannotDeliverOrLeakTheDecodedRaster() = runTest {
        val f = Fixture()
        var decoded: RgbImage? = null
        val loss = RepositoryFailure(OperationError(OperationErrorCode.PERMISSION_LOST, true, "library_reconnect_required", null))
        val sources = ScopedCommittedOriginals { _, _, read ->
            read(CommittedSourceBinding(f.id, f.stored.sourceBlobId, f.hash, 4, f.info), f.file)
            assertTrue(f.budget.usedBytes > 0)
            throw loss
        }
        val decoder = SourceDecoder(sources, SourceFileDecoder { _, _ ->
            val image = RgbImage(3, 2, 9, f.pixels).also { decoded = it }
            DecodedSource(f.info, image, DecodeAdmission(f.budget).admit(4, 3, 2, 1), f.hash)
        })
        val thrown = assertFailsWith<RepositoryFailure> {
            decoder.decode(f.id.value, OutputSpec(PixelSize(1, 1), RenderPurpose.PREVIEW)).close()
        }
        assertSame(loss, thrown)
        assertEquals(0L, f.budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { checkNotNull(decoded).toPackedByteArray() }
    }

    @Test fun cancellationAfterTheBorrowedFileCallbackClosesTheUndeliveredRaster() = runTest {
        val f = Fixture()
        var decoded: RgbImage? = null
        var pastCallback = false
        var delivered = false
        val sources = ScopedCommittedOriginals { _, _, read ->
            read(f.stored.binding(), f.file)
            pastCallback = true
            currentCoroutineContext().cancel()
        }
        val decoder = SourceDecoder(sources, SourceFileDecoder { _, _ ->
            val image = RgbImage(3, 2, 9, f.pixels).also { decoded = it }
            DecodedSource(f.info, image, DecodeAdmission(f.budget).admit(4, 3, 2, 1), f.hash)
        })
        launch {
            decoder.decode(f.id.value, OutputSpec(PixelSize(1, 1), RenderPurpose.PREVIEW)).use { delivered = true }
        }.join()
        assertTrue(pastCallback)
        assertFalse(delivered)
        assertEquals(0L, f.budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { checkNotNull(decoded).toPackedByteArray() }
    }

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
        var codecFile = file
        var codecHash = hash
        val budget = ImageAllocationBudget(4L * 1024 * 1024)
        var loaded = 0; var decoded = 0
        var ordinaryLoads = 0
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
        private fun decoder(): SourceDecoder {
            val sources = object : CommittedSources, ProducerCommittedSources {
                override suspend fun load(sourceId: SourceId): CommittedSource {
                    ordinaryLoads++
                    return read(sourceId)
                }

                override suspend fun loadForProducer(sourceId: SourceId, expected: ExportSourceBinding): CommittedSource {
                    // Return the configured projection even if it disagrees; these decoder tests witness its independent check before codec.
                    assertEquals(0, ordinaryLoads)
                    assertEquals(id.value, expected.source.sourceId)
                    return read(sourceId)
                }

                private fun read(sourceId: SourceId): CommittedSource {
                    assertEquals(id, sourceId); loaded++; onLoad(); return stored
                }
            }
            val codec = SourceFileDecoder { file, digest ->
                decoded++; assertEquals(codecFile, file); assertEquals(codecHash, digest); onDecode()
                val permit = DecodeAdmission(budget).admit(4, 3, 2, 1)
                val image = RgbImage(3, 2, 9, pixels).also { lastImage = it }
                try {
                    val result = DecodedSource(actual, image, permit, actualBytes)
                    if (cancelOnDelivery) currentCoroutineContext().cancel(CancellationException("synthetic delivery cancellation"))
                    result
                } catch (failure: Throwable) { image.close(); permit.close(); throw failure }
            }
            return SourceDecoder(sources, codec)
        }

        suspend fun decode(output: OutputSpec = OutputSpec(PixelSize(1, 1), RenderPurpose.PREVIEW), source: String = id.value): DecodedSource =
            decoder().decode(source, output)

        fun producerDecode(): suspend (String, OutputSpec, ExportSourceBinding) -> DecodedSource = decoder()::decodeForProducer

        fun admitted(): ExportSourceBinding {
            val original = stored
            val s = original.inspection
            val blobId = original.sourceBlobId.value
            return ExportSourceBinding(
                SourceRow(original.sourceId.value, blobId, s.sourcePixelHash, s.normalizedSize.width, s.normalizedSize.height,
                    s.exifOrientation, s.conversionFingerprint, s.profileSha256, 1, "READY", 1),
                SourceBlobRow(blobId, original.sourceByteHash, original.byteCount, s.mime, s.encodedSize.width, s.encodedSize.height,
                    "sources/$blobId/original.bin", "READY", 1, UUID.randomUUID().toString(), 1),
            )
        }

        // Distinct equal-length bytes under the same IDs. The codec fixture can normalize either original to the same full inspection and RGB.
        fun equivalentBytes(): CommittedSource {
            val alternate = Files.createTempFile(parent.toPath(), "source-", ".bin").toFile().apply { writeBytes(byteArrayOf(4, 3, 2, 1)) }
            return CommittedSource(id, stored.sourceBlobId, "ee10da4aefe61a37df1dee937ca3221afa3b2351f9ea34edbbb769573c6785f7", 4, info, alternate)
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

    @Test fun portableProofAcceptsAnotherBuildOnlyAfterAuthenticatingTheActualRasterWithTheHistoricalBinding() {
        val f = Fixture()
        val old = "android-imagedecoder-v2/api28/0123456789abcdef/srgb-straight-white-exif-v1"
        val current = "android-imagedecoder-v2/api35/fedcba9876543210/srgb-straight-white-exif-v1"
        val recorded = f.info.copy(conversionFingerprint = old, sourcePixelHash = independentPixels(old, f.pixels))
        val observed = f.info.copy(conversionFingerprint = current, sourcePixelHash = independentPixels(current, f.pixels))
        RgbImage(3, 2, 9, f.pixels).use {
            assertTrue(PortableSourcePixels.proves(recorded, observed, it))
            assertFalse(PortableSourcePixels.proves(recorded.copy(sourcePixelHash = "e".repeat(64)), observed, it))
            assertFalse(PortableSourcePixels.proves(recorded.copy(exifOrientation = 2), observed, it))
            assertFalse(PortableSourcePixels.proves(recorded.copy(conversionFingerprint = "future-policy"), observed, it))
            assertFalse(PortableSourcePixels.proves(recorded.copy(mime = "image/jpeg"),
                observed.copy(mime = "image/jpeg", conversionFingerprint = "$current/jpeg-camera-view-v1"), it))
            val profiled = "$old/matrix-srgb-profile-v1"
            assertFalse(PortableSourcePixels.proves(recorded.copy(conversionFingerprint = profiled,
                profileSha256 = "a".repeat(64), sourcePixelHash = independentPixels(profiled, f.pixels)),
                observed.copy(conversionFingerprint = "$current/matrix-srgb-profile-v1", profileSha256 = "b".repeat(64)), it))
        }
        val wrongPixels = f.pixels.copyOf().apply { this[0] = 0 }
        RgbImage(3, 2, 9, wrongPixels).use {
            assertFalse(PortableSourcePixels.proves(recorded, observed.copy(sourcePixelHash = independentPixels(current, wrongPixels)), it))
        }
    }

    private fun independentPixels(fingerprint: String, pixels: ByteArray): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { stream ->
            stream.write("pristinepage-source-pixels-v1\u0000".toByteArray())
            val policy = fingerprint.toByteArray()
            stream.writeInt(policy.size)
            stream.write(policy)
            stream.writeInt(3)
            stream.writeInt(2)
            stream.writeLong(pixels.size.toLong())
            stream.write(pixels)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
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

    private val exportOutput = OutputSpec(PixelSize(2, 1), RenderPurpose.EXPORT)

    private suspend fun producerError(decode: suspend () -> DecodedSource): OperationError =
        try { decode().close(); throw AssertionError("Producer decode unexpectedly delivered a raster") }
        catch (failure: RepositoryFailure) { failure.error }

    private fun assertProducerRefusedBeforeCodec(f: Fixture, label: String, failure: OperationError) {
        assertEquals(label, OperationErrorCode.INTEGRITY_FAILED, failure.code)
        assertFalse(label, failure.retryable)
        assertEquals(label, f.id.value, failure.affectedId)
        assertEquals(label, 1, f.loaded)
        assertEquals(label, 0, f.decoded)
        assertEquals(label, 0L, f.budget.usedBytes)
    }

    @Test fun producerDecodeChecksAndConsumesOneExactLoadWithoutReopening() = runTest {
        val f = Fixture()
        val decode = f.producerDecode()
        val expected = f.admitted()
        val alternate = f.equivalentBytes()
        f.onLoad = { if (f.loaded > 1) f.stored = alternate }
        f.onDecode = { assertEquals("Codec must consume the already checked load", 1, f.loaded) }

        decode(f.id.value, exportOutput, expected).use {
            assertEquals(f.hash, it.sourceByteHash)
            assertEquals(f.info, it.inspection)
            assertEquals(PixelSize(3, 2), PixelSize(it.image.width, it.image.height))
            assertArrayEquals(f.pixels, it.image.toPackedByteArray())
            assertTrue(f.budget.usedBytes > 0)
        }
        assertEquals(1, f.loaded)
        assertEquals(0, f.ordinaryLoads)
        assertEquals(1, f.decoded)
        assertEquals(0L, f.budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { f.lastImage!!.toPackedByteArray() }
    }

    @Test fun producerDecodeRejectsSameLengthNormalizedEquivalentDifferentBytesBeforeCodec() = runTest {
        val f = Fixture()
        val decode = f.producerDecode()
        val expected = f.admitted()
        val replacement = f.equivalentBytes()
        f.stored = replacement
        f.codecFile = replacement.file
        f.codecHash = replacement.sourceByteHash
        f.actualBytes = replacement.sourceByteHash
        val failure = producerError { decode(f.id.value, exportOutput, expected) }
        assertProducerRefusedBeforeCodec(f, "same IDs and normalization, different original digest", failure)

        // A matching producer expectation can consume B: the preceding refusal is not caused by the fixture codec or changed pixels.
        decode(f.id.value, exportOutput, expected.copy(blob = expected.blob.copy(byteSha256 = replacement.sourceByteHash))).use {
            assertEquals(replacement.sourceByteHash, it.sourceByteHash)
            assertEquals(f.info, it.inspection)
            assertArrayEquals(f.pixels, it.image.toPackedByteArray())
            assertTrue(f.budget.usedBytes > 0)
        }
        assertEquals(2, f.loaded)
        assertEquals(1, f.decoded)
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun producerDecodeRejectsIndependentSourceBlobCountAndInspectionDriftBeforeCodec() = runTest {
        val f = Fixture()
        val decode = f.producerDecode()
        val expected = f.admitted()
        val original = f.stored
        val info = original.inspection
        fun changed(sourceId: SourceId = original.sourceId, blobId: SourceBlobId = original.sourceBlobId,
            count: Long = original.byteCount, inspection: SourceInspection = info) =
            CommittedSource(sourceId, blobId, original.sourceByteHash, count, inspection, original.file)
        for ((field, value) in listOf(
            "source ID" to changed(sourceId = SourceId(UUID.randomUUID().toString())),
            "blob ID" to changed(blobId = SourceBlobId(UUID.randomUUID().toString())),
            "byte count" to changed(count = 5),
            "MIME" to changed(inspection = info.copy(mime = "image/jpeg")),
            "encoded and normalized size" to changed(inspection = info.copy(encodedSize = PixelSize(2, 3), normalizedSize = PixelSize(2, 3))),
            "encoded size and EXIF with unchanged normalized size" to changed(inspection = info.copy(encodedSize = PixelSize(2, 3), exifOrientation = 6)),
            "EXIF" to changed(inspection = info.copy(exifOrientation = 2)),
            "profile" to changed(inspection = info.copy(profileSha256 = "c".repeat(64))),
            "conversion fingerprint" to changed(inspection = info.copy(conversionFingerprint = "new-unaccepted-conversion")),
            "pixel hash" to changed(inspection = info.copy(sourcePixelHash = "b".repeat(64))),
        )) {
            f.loaded = 0
            f.stored = value
            f.actual = value.inspection
            val failure = producerError { decode(f.id.value, exportOutput, expected) }
            assertProducerRefusedBeforeCodec(f, field, failure)
        }
    }

    @Test fun producerDecodePreflightsOpaqueScopeAndDestinationWithoutLookupOrCodec() = runTest {
        val f = Fixture()
        val decode = f.producerDecode()
        val expected = f.admitted()
        val malformed = producerError { decode("../external", exportOutput, expected) }
        assertEquals(OperationErrorCode.INVALID_INPUT, malformed.code)
        assertNull(malformed.affectedId)
        val crossScope = producerError { decode(UUID.randomUUID().toString(), exportOutput, expected) }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, crossScope.code)
        val tooLarge = OutputSpec(PixelSize(Int.MAX_VALUE, Int.MAX_VALUE), RenderPurpose.EXPORT)
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, producerError { decode(f.id.value, tooLarge, expected) }.code)
        assertEquals(0, f.loaded)
        assertEquals(0, f.decoded)
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun producerDecodeStillChecksTheActualCodecResultAndClosesItsWholeOwner() = runTest {
        val f = Fixture()
        val decode = f.producerDecode()
        val expected = f.admitted()
        f.actual = f.info.copy(profileSha256 = "c".repeat(64))
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, producerError { decode(f.id.value, exportOutput, expected) }.code)
        assertEquals(1, f.loaded)
        assertEquals(1, f.decoded)
        assertEquals(0L, f.budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { f.lastImage!!.toPackedByteArray() }
    }

    @Test fun producerDecodeCannotBypassSharedFullSourceBudgetForASmallDestination() = runTest {
        val f = Fixture()
        val decode = f.producerDecode()
        val expected = f.admitted()
        f.budget.reserve(f.budget.capacityBytes - 1).use {
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, producerError { decode(f.id.value, exportOutput, expected) }.code)
            assertEquals(1, f.loaded)
            assertEquals(1, f.decoded)
            assertNull(f.lastImage)
            assertEquals(f.budget.capacityBytes - 1, f.budget.usedBytes)
        }
        assertEquals(0L, f.budget.usedBytes)
    }
}
