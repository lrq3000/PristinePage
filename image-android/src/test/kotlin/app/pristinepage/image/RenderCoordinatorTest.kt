package app.pristinepage.image

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import app.pristinepage.image.decode.*
import app.pristinepage.image.ocr.SavedOcrPreparer
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.DefaultRenderCoordinator
import app.pristinepage.image.render.GeometryEngine
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Only Room lookup and Android byte decoding are substituted. Actual SourceDecoder, geometry, appearance, shared budget and returned whole-result ownership execute; this is not a picker/editor/persistent-save journey. */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderCoordinatorTest {
    private class Fixture(val width: Int = 3, val height: Int = 2, val pixels: ByteArray = ByteArray(width * height * 3) { (it + 1).toByte() }, capacity: Long = 32_000_000) {
        val id = SourceId(UUID.randomUUID().toString()); val page = PageId(UUID.randomUUID().toString())
        val budget = ImageAllocationBudget(capacity)
        val info = SourceInspection("image/png", PixelSize(width, height), PixelSize(width, height), 1, null, "fixture-srgb-v1", "b".repeat(64))
        val binding = SourceBinding(info.sourcePixelHash, info.normalizedSize, info.conversionFingerprint)
        private val blobId = SourceBlobId(UUID.randomUUID().toString())
        private val byteHash = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
        val file: File
        var loads = 0; var decodes = 0
        var lastDecoded: RgbImage? = null
        var beforeLoad: suspend () -> Unit = {}
        var beforeDecode: suspend () -> Unit = {}
        var afterDecode: suspend () -> Unit = {}
        var decodeAdmissionBudget = budget
        var decodeAdmissionWidth = width.toLong()
        init {
            val parent = File(System.getProperty("user.dir"), "build/test-render-coordinator").apply { mkdirs() }
            file = Files.createTempFile(parent.toPath(), "source-", ".bin").toFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        }
        fun request(recipe: Recipe = Recipe(geometry = GeometryRecipe.None(binding)), output: PixelSize = PixelSize(width, height)) = RenderRequest(id.value, binding.sourcePixelHash, page.value, 1, recipe, OutputSpec(output, RenderPurpose.PREVIEW), 5, null, FallbackPolicy.FAIL)
        fun admitted() = ExportSourceBinding(
            SourceRow(id.value, blobId.value, info.sourcePixelHash, width, height, info.exifOrientation,
                info.conversionFingerprint, null, 1, "READY", 1),
            SourceBlobRow(blobId.value, byteHash, 4, info.mime, width, height,
                "sources/${blobId.value}/original.bin", "READY", 1, UUID.randomUUID().toString(), 1),
        )
        fun committed() = CommittedSource(id, blobId, byteHash, 4, info, file)
        fun ordinarySources(value: () -> CommittedSource = ::committed) = CommittedSources { requested ->
            assertEquals(id, requested); loads++; beforeLoad(); value()
        }
        fun coordinator(dispatcher: CoroutineDispatcher, sources: CommittedSources = ordinarySources()): DefaultRenderCoordinator {
            val decoder = SourceDecoder(sources, SourceFileDecoder { requested, hash ->
                assertEquals(file, requested); assertEquals(byteHash, hash); decodes++; beforeDecode()
                val permit = DecodeAdmission(decodeAdmissionBudget).admit(4, decodeAdmissionWidth, height.toLong(), 1)
                val image = RgbImage(width, height, width * 3, pixels).also { lastDecoded = it }
                val result = DecodedSource(info, image, permit, hash)
                try { afterDecode(); result } catch (e: Throwable) { result.close(); throw e }
            })
            return DefaultRenderCoordinator(decoder, GeometryEngine(budget), AppearanceEngine(budget), dispatcher)
        }
    }
    private fun neutrals(vararg v: Int) = ByteArray(v.size * 3) { v[it / 3].toByte() }

    @Test fun producerRouteCannotFallBackToOrdinaryUnboundSourceRead() = runTest {
        val f = Fixture()
        val coordinator = f.coordinator(UnconfinedTestDispatcher(testScheduler))
        val request = f.request().copy(output = OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT), generation = 0)
        val expected = f.admitted()
        // This fixture supports ordinary reads only. A lost bound port must never reuse ordinary render, even if its returned bytes would match.
        coordinator.renderForProducer(request, expected).use {
            assertSame(request, it.request)
            assertEquals(RenderStatus.UNAVAILABLE, it.status)
            assertNull(it.image)
        }
        assertEquals(0, f.loads)
        assertEquals(0, f.decodes)
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun producerRouteConsumesTheBoundViewOnceAndTransfersTheWholeFinalOwner() = runTest {
        val f = Fixture()
        val expected = f.admitted()
        val alternate = Files.createTempFile(f.file.parentFile.toPath(), "other-source-", ".bin").toFile().apply {
            writeBytes(byteArrayOf(4, 3, 2, 1))
        }
        var boundLoads = 0
        val ordinary = f.ordinarySources {
            CommittedSource(f.id, f.committed().sourceBlobId,
                "ee10da4aefe61a37df1dee937ca3221afa3b2351f9ea34edbbb769573c6785f7", 4, f.info, alternate)
        }
        val sources = object : CommittedSources by ordinary, ProducerCommittedSources {
            override suspend fun loadForProducer(sourceId: SourceId, expected: ExportSourceBinding): CommittedSource {
                assertEquals(f.id, sourceId)
                assertSame(frozen, expected)
                boundLoads++
                return f.committed()
            }

            private val frozen = expected
        }
        val coordinator = f.coordinator(UnconfinedTestDispatcher(testScheduler), sources)
        val recipe = Recipe(geometry = GeometryRecipe.None(f.binding, outputQuarterTurns = 1), binary = BinaryRecipe.GrayscaleV1)
        val request = f.request(recipe, PixelSize(2, 3)).copy(output = OutputSpec(PixelSize(2, 3), RenderPurpose.EXPORT), generation = 0)
        val result = coordinator.renderForProducer(request, expected)
        val image = result.use {
            assertSame(request, it.request)
            assertEquals(RenderStatus.OK, it.status)
            assertEquals(listOf("source_decode_v1", "geometry_none_v1", "none_v1", "grayscale_v1"), it.stages.map { stage -> stage.stageId })
            // Clockwise permutation of the six input triples, then floor-weighted grayscale. Neither transform is identity.
            val raster = checkNotNull(it.image)
            assertEquals(PixelSize(2, 3), PixelSize(raster.width, raster.height))
            assertArrayEquals(neutrals(10, 1, 13, 4, 16, 7), raster.toPackedByteArray())
            assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.toPackedByteArray() }
            assertTrue(f.budget.usedBytes > 0)
            raster
        }
        assertThrows(IllegalStateException::class.java) { image.toPackedByteArray() }
        assertEquals(1, boundLoads)
        assertEquals(0, f.loads)
        assertEquals(1, f.decodes)
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun sourceGeometryAppearanceComposeOnceAndTransferTheWholeFinalOwner() = runTest {
        val f = Fixture(); val r = f.request(Recipe(geometry = GeometryRecipe.None(f.binding, outputQuarterTurns = 1)), PixelSize(2, 3))
        val result = f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(r)
        result.use {
            assertSame(r, it.request); assertEquals(RenderStatus.OK, it.status)
            assertArrayEquals(byteArrayOf(10,11,12,1,2,3,13,14,15,4,5,6,16,17,18,7,8,9), it.image!!.toPackedByteArray())
            assertEquals(listOf("source_decode_v1", "geometry_none_v1", "none_v1"), it.stages.map { stage -> stage.stageId })
            assertTrue(f.budget.usedBytes > 0)
            assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.toPackedByteArray() }
        }
        result.close(); assertEquals(0L, f.budget.usedBytes); assertEquals(1, f.loads); assertEquals(1, f.decodes)
        assertArrayEquals(byteArrayOf(1,2,3,4), f.file.readBytes())
    }

    @Test fun geometryFeedsCurrentAppearanceWithoutASecondBlend() = runTest {
        val f = Fixture(4, 1, neutrals(200,200,200,195))
        val recipe = Recipe(geometry = GeometryRecipe.None(f.binding), appearance = AppearanceRecipe.PaperGainV1(strength = .5))
        f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(f.request(recipe)).use { result -> assertEquals(RenderStatus.OK, result.status); assertArrayEquals(neutrals(223,223,223,217), result.image!!.toPackedByteArray()) }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun finalSauvolaConsumesActualUpstreamDenoiseNotRawGeometry() = runTest {
        val f = Fixture(3, 1, neutrals(0,10,0))
        val recipe = Recipe(geometry = GeometryRecipe.None(f.binding), denoise = DenoiseRecipe.BilateralLumaV1(), binary = BinaryRecipe.SauvolaV1())
        f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(f.request(recipe)).use { result ->
            // Bilateral[4,6,4] gives all-white; thresholding the original[0,10,0] would give[0,255,0].
            assertEquals(RenderStatus.OK, result.status); assertArrayEquals(neutrals(255,255,255), result.image!!.toPackedByteArray())
            assertEquals(listOf("source_decode_v1", "geometry_none_v1", "none_v1", "bilateral_luma_v1", "sauvola_v1"), result.stages.map { it.stageId })
        }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun malformedRequestIdentityRejectsBeforeLookupOrDecode() = runTest {
        val f = Fixture(); val coordinator = f.coordinator(UnconfinedTestDispatcher(testScheduler)); val request = f.request()
        for (r in listOf(request.copy(sourceId = "../source"), request.copy(pageId = "not-opaque"), request.copy(sourcePixelHash = "c".repeat(64)), request.copy(recipeRevision = 0), request.copy(generation = -1))) {
            coordinator.render(r).use { assertEquals(RenderStatus.INVALID_INPUT, it.status); assertNull(it.image) }
        }
        assertEquals(0, f.loads); assertEquals(0, f.decodes); assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun ocrRejectsOversizedOrFallbackRequestsBeforeSourceLookup() = runTest {
        val f = Fixture(2401, 2)
        val coordinator = f.coordinator(UnconfinedTestDispatcher(testScheduler))
        val request = f.request().copy(output = OutputSpec(PixelSize(2400, 1), RenderPurpose.OCR))
        for (invalid in listOf(
            request.copy(output = OutputSpec(PixelSize(2401, 2), RenderPurpose.OCR)),
            request.copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT),
        )) {
            coordinator.render(invalid).use { assertEquals(RenderStatus.INVALID_INPUT, it.status); assertNull(it.image) }
        }
        assertEquals(0, f.loads)
        assertEquals(0, f.decodes)
        assertEquals(0L, f.budget.usedBytes)
        coordinator.render(request).use { assertEquals(RenderStatus.OK, it.status); assertEquals(2400, it.image?.width) }
        assertEquals(1, f.decodes)
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun ocrCanRenderA12MegapixelSourceWithoutKeepingTheDecodeChargeThroughAppearance() = runTest {
        val pixels = ByteArray(3024 * 4032 * 3) { if (it % 3 == 1) 120 else 60 }
        val f = Fixture(3024, 4032, pixels, capacity = 192L * 1024 * 1024)
        val request = f.request(output = PixelSize(1800, 2400)).copy(output = OutputSpec(PixelSize(1800, 2400), RenderPurpose.OCR))
        f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(request).use { result ->
            assertEquals(RenderStatus.OK, result.status)
            val cropped = checkNotNull(result.image)
            assertEquals(PixelSize(1800, 2400), PixelSize(cropped.width, cropped.height))
            assertEquals(120, cropped.getUnsigned(1799, 2399, 1))
            assertEquals(60, cropped.getUnsigned(0, 0, 0))
            assertEquals(listOf("source_decode_v1", "source_resize_half_pixel_v1", "none_v1"), result.stages.map { it.stageId })
            assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.getUnsigned(0, 0, 0) }
            assertEquals(25_985_536L, f.budget.usedBytes) // Only the identity appearance's 6N + 64 KiB result permit.
        }
        assertEquals(1, f.loads)
        assertEquals(1, f.decodes)
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun ocrRefusesAnImpossibleIdleLedgerBeforeDecodeAndDoesNotRequestRedelivery() = runTest {
        // 1,048,676 decode + 65,572 geometry, one byte beyond this idle ledger.
        val f = Fixture(capacity = 1_114_247L)
        val failure = ocrPreparationFailure(f, UnconfinedTestDispatcher(testScheduler))
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.code)
        assertFalse(failure.retryable)
        assertEquals(1, f.loads)
        assertEquals(0, f.decodes)
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun ocrDefersWhenAnotherConsumerUsesHeadroomAndCanRenderAfterItEnds() = runTest {
        val f = Fixture(capacity = 1_114_248L)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        f.budget.reserve(1).use {
            val failure = ocrPreparationFailure(f, dispatcher)
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.code)
            assertTrue(failure.retryable)
            assertEquals(0, f.decodes)
            assertEquals(1L, f.budget.usedBytes)
        }
        val request = f.request().copy(output = OutputSpec(PixelSize(3, 2), RenderPurpose.OCR))
        f.coordinator(dispatcher).render(request).use { assertEquals(RenderStatus.OK, it.status) }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun ocrDefersWhenHeadroomIsLostAfterDecodeAdmission() = runTest {
        val f = Fixture(capacity = 1_114_248L)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        var competing: AutoCloseable? = null
        f.afterDecode = { competing = f.budget.reserve(1) }
        try {
            val failure = ocrPreparationFailure(f, dispatcher)
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.code)
            assertTrue(failure.retryable)
            assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.getUnsigned(0, 0, 0) }
            assertEquals(1L, f.budget.usedBytes)
        } finally { competing?.close() }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun ocrDoesNotRetryAnImpossiblePermitOnASeparateDecoderLedger() = runTest {
        val f = Fixture(capacity = 1_114_248L)
        f.decodeAdmissionBudget = ImageAllocationBudget(1_048_675L)
        val failure = ocrPreparationFailure(f, UnconfinedTestDispatcher(testScheduler))
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.code)
        assertFalse(failure.retryable)
        assertEquals(0L, f.decodeAdmissionBudget.usedBytes)
    }

    @Test fun ocrDoesNotRetryWhenActualSourceHeaderRequiresAnImpossibleReservation() = runTest {
        val f = Fixture(capacity = 1_114_248L)
        // Substitute only the codec/header side: the admitted file metadata still claims a 3×2 source.
        f.decodeAdmissionWidth = 3_000L
        val failure = ocrPreparationFailure(f, UnconfinedTestDispatcher(testScheduler))
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.code)
        assertFalse(failure.retryable)
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun ocrDoesNotRetryWhenActualDecodeAndGeometryChargesCannotCoexistAtIdle() = runTest {
        val f = Fixture(capacity = 1_114_248L)
        // Decode itself fits but the real permit leaves 32 fewer bytes than the independently estimated 3×2 source.
        f.decodeAdmissionWidth = 4L
        val failure = ocrPreparationFailure(f, UnconfinedTestDispatcher(testScheduler))
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.code)
        assertFalse(failure.retryable)
        assertEquals(0L, f.budget.usedBytes)
    }

    private suspend fun ocrPreparationFailure(f: Fixture, dispatcher: CoroutineDispatcher): OperationError {
        val recipe = f.request().recipe
        val saved = EditablePage(DocumentId(UUID.randomUUID().toString()), 1, f.page, f.id, 1, RecipeCodec.hash(recipe), recipe)
        val configuration = OcrConfiguration("fixture", 1, OcrLayout.AUTOMATIC, OcrLanguagePacks(listOf(OcrLanguagePack("eng", "a".repeat(64)))))
        return try {
            SavedOcrPreparer(f.coordinator(dispatcher), f.budget) { _, _ ->
                throw AssertionError("Pre-start resource refusal reached native preparation")
            }.prepare(saved, configuration) { throw AssertionError("Resource refusal transferred a session") }
            throw AssertionError("OCR preparation did not refuse")
        } catch (failure: RepositoryFailure) { failure.error }
    }

    @Test fun verifiedDecodedIdentityStillMustMatchTheRequestedRecipeSource() = runTest {
        val f = Fixture(); val changed = f.binding.copy(orientationColorConversionVersion = "other-conversion")
        f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(f.request(Recipe(geometry = GeometryRecipe.None(changed)))).use { assertEquals(RenderStatus.INVALID_INPUT, it.status); assertNull(it.image) }
        assertEquals(1, f.decodes); assertEquals(0L, f.budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.toPackedByteArray() }
    }

    @Test fun cardAbovePurposeLimitRejectsBeforeSourceLookupOrAllocation() = runTest {
        val f = Fixture(225, 1); val request = f.request().copy(output = OutputSpec(PixelSize(225, 1), RenderPurpose.CARD))
        f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(request).use { assertEquals(RenderStatus.INVALID_INPUT, it.status); assertNull(it.image) }
        assertEquals(0, f.loads); assertEquals(0, f.decodes); assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun previewAbovePurposeLimitRejectsBeforeSourceLookupOrAllocation() = runTest {
        val f = Fixture(1025, 1)
        f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(f.request()).use { assertEquals(RenderStatus.INVALID_INPUT, it.status); assertNull(it.image) }
        assertEquals(0, f.loads); assertEquals(0, f.decodes); assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun knownInvalidTargetOutputRejectsBeforeDecodeCanHideTheInputError() = runTest {
        val f = Fixture(6, 4); val coordinator = f.coordinator(UnconfinedTestDispatcher(testScheduler))
        val full = QuadPx(PointPx(0.0,0.0), PointPx(1.0,0.0), PointPx(1.0,1.0), PointPx(0.0,1.0))
        val perspective = Recipe(geometry = GeometryRecipe.Perspective(f.binding, full))
        // Upscale, impossible common-floor aspect, then a singleton geometric axis. All are known invalid without reading source bytes.
        for (request in listOf(f.request(output = PixelSize(7,4)), f.request(output = PixelSize(3,3)), f.request(perspective, PixelSize(1,1)))) {
            coordinator.render(request).use { assertEquals(RenderStatus.INVALID_INPUT, it.status); assertNull(it.image) }
        }
        assertEquals(0, f.loads); assertEquals(0, f.decodes); assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun purposeBoundariesAndExplicitFullContextKeepTheirRequestedDimensions() = runTest {
        for ((width, purpose) in listOf(224 to RenderPurpose.CARD, 1024 to RenderPurpose.PREVIEW, 1025 to RenderPurpose.DETAIL_CROP, 4001 to RenderPurpose.EXPORT)) {
            val f = Fixture(width, 1); val request = f.request().copy(output = OutputSpec(PixelSize(width,1), purpose))
            f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(request).use {
                assertEquals(RenderStatus.OK, it.status); assertSame(request, it.request)
                val image = checkNotNull(it.image)
                assertEquals(width, image.width); assertEquals(1, image.height); assertArrayEquals(f.pixels, image.toPackedByteArray())
            }
            assertEquals(1, f.decodes); assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun typedSourceFailuresKeepDistinctSafeStatusesAndNeverExposeRawMessages() = runTest {
        val rows = listOf(OperationErrorCode.STORAGE_FAILED to RenderStatus.STORAGE_FAILED, OperationErrorCode.INTEGRITY_FAILED to RenderStatus.STORAGE_FAILED, OperationErrorCode.PROVIDER_UNAVAILABLE to RenderStatus.UNAVAILABLE, OperationErrorCode.RESOURCE_LIMIT to RenderStatus.RESOURCE_LIMIT, OperationErrorCode.INVALID_INPUT to RenderStatus.INVALID_INPUT)
        for ((code, status) in rows) {
            val f = Fixture(); f.beforeDecode = { throw RepositoryFailure(OperationError(code, false, "synthetic_source_failure", null)) }
            f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(f.request()).use { assertEquals(status, it.status); assertNull(it.image); assertTrue(it.warnings.contains("synthetic_source_failure")) }
            assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun unavailableGraphFallbackAndMaskCapabilitiesAreNotSilentlyIgnored() = runTest {
        val f = Fixture(); val coordinator = f.coordinator(UnconfinedTestDispatcher(testScheduler))
        val provider = AppearanceRecipe.ProviderAppearanceV1("not-installed", 1, "d".repeat(64), "test", "e".repeat(64), 0.0)
        for (r in listOf(f.request(Recipe(geometry = GeometryRecipe.None(f.binding), appearance = provider)), f.request().copy(protectionMaskId = UUID.randomUUID().toString()))) coordinator.render(r).use { assertEquals(RenderStatus.UNAVAILABLE, it.status); assertNull(it.image) }
        assertEquals(0, f.loads); assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun failedManualKeepPolicyTransfersWholeUnrotatedSourceWithoutResizingOrAppearance() = runTest {
        val f = Fixture(101,101)
        val full = QuadPx(PointPx(0.0,0.0), PointPx(1.0,0.0), PointPx(1.0,1.0), PointPx(0.0,1.0))
        val manual = GeometryRecipe.ManualCurve(f.binding, full, PointPx(.5,.9), PointPx(.5,.1), outputQuarterTurns = 1)
        val request = f.request(Recipe(geometry = manual, appearance = AppearanceRecipe.PaperGainV1(), binary = BinaryRecipe.SauvolaV1()), PixelSize(21,21)).copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT)
        f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(request).use {
            assertEquals(RenderStatus.DEGRADED, it.status); assertSame(request, it.request)
            assertSame(f.lastDecoded, it.image); assertArrayEquals(f.pixels, it.image!!.toPackedByteArray())
            assertEquals(101, it.image!!.width); assertTrue(f.budget.usedBytes > 0)
            assertTrue(it.warnings.contains("OUTPUT_ROTATION_OMITTED_1"))
            assertEquals(listOf("source_decode_v1", "manual_ruled_v1", "paper_gain_v1", "sauvola_v1"), it.stages.map { s -> s.stageId })
            assertEquals(listOf("OK", "FAILED_INVALID_MAP", "SKIPPED_AFTER_STAGE_FAILURE", "SKIPPED_AFTER_STAGE_FAILURE"), it.stages.map { s -> s.outcome })
        }
        assertEquals(0L, f.budget.usedBytes); assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.toPackedByteArray() }
    }

    @Test fun unavailableAppearanceKeepPolicyRetainsRotatedGeometryAndSkipsBinaryAtZeroStrength() = runTest {
        val f = Fixture()
        val missing = AppearanceRecipe.ProviderAppearanceV1("missing",1,"d".repeat(64),"test","e".repeat(64),0.0)
        val request = f.request(Recipe(geometry = GeometryRecipe.None(f.binding, outputQuarterTurns = 1), appearance = missing, binary = BinaryRecipe.SauvolaV1()), PixelSize(2,3)).copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT)
        f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(request).use {
            assertEquals(RenderStatus.DEGRADED, it.status)
            assertArrayEquals(byteArrayOf(10,11,12,1,2,3,13,14,15,4,5,6,16,17,18,7,8,9), it.image!!.toPackedByteArray())
            assertEquals(listOf("OK", "EXACT_PERMUTATION", "FAILED_PROVIDER_UNAVAILABLE", "SKIPPED_AFTER_STAGE_FAILURE"), it.stages.map { s -> s.outcome })
            assertEquals("missing", it.stages[2].provider!!.id)
            assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.toPackedByteArray() }
            assertTrue(f.budget.usedBytes > 0)
        }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun successfulKeepRequestReturnsOkButNeverConvertsLateAppearanceAdmissionIntoPixels() = runTest {
        val f = Fixture(capacity = 1_180_000); val coordinator = f.coordinator(UnconfinedTestDispatcher(testScheduler))
        val request = f.request().copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT)
        f.budget.reserve(65_752).use {
            coordinator.render(request).use { result ->
                assertEquals(RenderStatus.RESOURCE_LIMIT, result.status); assertNull(result.image)
                assertEquals(listOf("source_decode_v1", "geometry_none_v1", "appearance_graph_v1"), result.stages.map { s -> s.stageId })
                assertEquals(listOf("OK", "EXACT_PERMUTATION", "FAILED_RESOURCE_LIMIT"), result.stages.map { s -> s.outcome })
            }
            assertEquals(65_752L, f.budget.usedBytes); assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.toPackedByteArray() }
        }
        coordinator.render(request.copy(generation = 6)).use { assertEquals(RenderStatus.OK, it.status); assertArrayEquals(f.pixels, it.image!!.toPackedByteArray()) }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun keepPolicyStillRejectsInvalidMaskedGraphAndSourceErrorsWithoutFallback() = runTest {
        val f = Fixture(); val coordinator = f.coordinator(UnconfinedTestDispatcher(testScheduler))
        val request = f.request(Recipe(geometry = GeometryRecipe.None(f.binding), detail = DetailRecipe.LumaDetail7V1(strength = 0.0))).copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT, protectionMaskId = UUID.randomUUID().toString())
        coordinator.render(request).use { assertEquals(RenderStatus.INVALID_INPUT, it.status); assertNull(it.image) }
        assertEquals(0, f.loads); assertEquals(0, f.decodes); assertEquals(0L, f.budget.usedBytes)
        f.beforeDecode = { throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, false, "synthetic_decode_failure", null)) }
        coordinator.render(f.request().copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT)).use { assertEquals(RenderStatus.PROCESSING_FAILED, it.status); assertNull(it.image) }
        assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun canceledDeliveryOfDegradedSourceClosesItsTransferredDecoderOwner() = runTest {
        val f = Fixture(101,101); val worker = QueuedDispatcher()
        val full = QuadPx(PointPx(0.0,0.0), PointPx(1.0,0.0), PointPx(1.0,1.0), PointPx(0.0,1.0))
        val manual = GeometryRecipe.ManualCurve(f.binding, full, PointPx(.5,.9), PointPx(.5,.1))
        val request = f.request(Recipe(geometry = manual), PixelSize(21,21)).copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT)
        var delivered = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) { f.coordinator(worker).render(request).use { delivered = true } }
        worker.runOne(); assertTrue(f.budget.usedBytes > 0); assertFalse(delivered)
        job.cancel(); runCurrent(); job.join()
        assertFalse(delivered); assertEquals(0L, f.budget.usedBytes); assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.toPackedByteArray() }
    }

    @Test fun invalidManualMapAndAdmissionReturnNoPixelsAndCloseDecodedOwners() = runTest {
        val full = QuadPx(PointPx(0.0,0.0), PointPx(1.0,0.0), PointPx(1.0,1.0), PointPx(0.0,1.0))
        for ((capacity, expected) in listOf(32_000_000L to RenderStatus.INVALID_MAP, 6_000_000L to RenderStatus.RESOURCE_LIMIT)) {
            val f = Fixture(101, 101, capacity = capacity)
            val manual = GeometryRecipe.ManualCurve(f.binding, full, PointPx(.5,.9), PointPx(.5,.1))
            f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(f.request(Recipe(geometry = manual), PixelSize(21,21))).use { assertEquals(expected, it.status); assertNull(it.image) }
            assertEquals(0L, f.budget.usedBytes); assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.toPackedByteArray() }
        }
    }

    @Test fun unexpectedExceptionsAndAllocationErrorsReturnSafeFailuresWithoutFallback() = runTest {
        for (oom in listOf(false, true)) {
            val f = Fixture(); f.beforeDecode = { if (oom) throw OutOfMemoryError("synthetic, no allocation attempted") else throw IllegalStateException("private/path/raw-uri-must-not-escape") }
            f.coordinator(UnconfinedTestDispatcher(testScheduler)).render(f.request()).use { result ->
                assertEquals(if (oom) RenderStatus.RESOURCE_LIMIT else RenderStatus.PROCESSING_FAILED, result.status); assertNull(result.image)
                assertFalse(result.warnings.joinToString().contains("private/path"))
            }
            assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun cancellationAfterDecodePropagatesAndClosesItsUndeliveredOwner() = runTest {
        val f = Fixture(); f.afterDecode = { currentCoroutineContext().cancel(CancellationException("synthetic delivery")) }
        val coordinator = f.coordinator(UnconfinedTestDispatcher(testScheduler)); var canceled = false
        launch { try { coordinator.render(f.request()).close(); fail("Canceled render returned") } catch (_: CancellationException) { canceled = true } }.join()
        assertTrue(canceled); assertEquals(0L, f.budget.usedBytes); assertThrows(IllegalStateException::class.java) { f.lastDecoded!!.toPackedByteArray() }
    }

    @Test fun cancellationDuringDispatcherDeliveryClosesAnAlreadyCompletedResult() = runTest {
        // Unconfined execution can return inline without a dispatch-delivery gap. Drive a real queued worker dispatch explicitly, leaving only its return continuation queued on the caller scheduler.
        val worker = QueuedDispatcher()
        val f = Fixture(); val coordinator = f.coordinator(worker); var delivered = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.render(f.request()).use { delivered = true } }
        assertEquals(0L, f.budget.usedBytes)
        worker.runOne()
        assertTrue(f.budget.usedBytes > 0); assertFalse(delivered)
        job.cancel(); runCurrent(); job.join()
        assertFalse(delivered); assertEquals(0L, f.budget.usedBytes)
    }

    @Test fun canceledQueuedRequestCannotDecodeOrConsumeASecondProcessingSlot() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeLoad = { entered.complete(Unit); release.await() }
        val coordinator = f.coordinator(UnconfinedTestDispatcher(testScheduler))
        val first = launch { coordinator.render(f.request()).close() }; entered.await()
        val second = launch { coordinator.render(f.request().copy(generation = 6)).close() }
        runCurrent(); assertEquals(1, f.loads); second.cancelAndJoin(); release.complete(Unit); first.join()
        assertEquals(1, f.decodes); assertEquals(0L, f.budget.usedBytes)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runOne() { tasks.removeFirst().run() }
    }
}
