package app.pristinepage.image.export

import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.db.ExportPageRow
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.ExportSourceBinding
import app.pristinepage.image.decode.DecodeAdmission
import app.pristinepage.image.decode.ImageAllocationBudget
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrdinaryPngPreflightTest {
    private val profile = OrdinaryExportProfile("classical-v1", "lossless-rgb-v1", "cpu-reference", "RGB8")
    private val frozenSource = "10000000-0000-4000-8000-000000000001"
    private val frozenBlob = "10000000-0000-4000-8000-000000000002"
    private val pageId = "10000000-0000-4000-8000-000000000003"
    private val fixtureBuild = "pristinepage-jvm-test-build"
    private val api28Conversion = "android-imagedecoder-v2/api28/245572580cf335c0/srgb-straight-white-exif-v1"
    private val normalized = SourceBinding("b".repeat(64), PixelSize(3, 2), api28Conversion)
    private val recipe = Recipe(geometry = GeometryRecipe.None(normalized, outputQuarterTurns = 1))
    private val row = ExportPageRow(
        "10000000-0000-4000-8000-000000000004", 2, pageId, frozenSource, frozenBlob, "a".repeat(64),
        normalized.sourcePixelHash, 3, 2, normalized.orientationColorConversionVersion, 7, 1,
        RecipeCodec.encode(recipe), RecipeCodec.hash(recipe), "[]".toByteArray(), profile.providerDescriptor(),
        OrdinaryExportProfile.emptyAssetSetHash, profile.rendererVersion, 2, 3, 2, 3, "FAIL", null, null,
    )
    private val source = SourceRow(frozenSource, frozenBlob, normalized.sourcePixelHash, 3, 2, 6, normalized.orientationColorConversionVersion, null, 1, "READY", 0)
    private val blob = SourceBlobRow(
        frozenBlob, row.byteSha256, 746, "image/jpeg", 2, 3, "sources/$frozenBlob/original.bin", "READY", 1,
        "10000000-0000-4000-8000-000000000005", 0,
    )
    private val pinned = ExportSourceBinding(source, blob)
    private val alternateBlob = "10000000-0000-4000-8000-000000000006"
    private val alternate = ExportSourceBinding(
        source.copy(sourceId = "10000000-0000-4000-8000-000000000007", sourceBlobId = alternateBlob, exifOrientation = 1),
        blob.copy(sourceBlobId = alternateBlob, byteSha256 = "c".repeat(64), encodedWidth = 3, encodedHeight = 2,
            relativePath = "sources/$alternateBlob/original.bin"),
    )

    @Test fun exactCodecPeakFitsWithRetainedChargeButOneByteLessDoesNot() = runTest {
        // Complete appearance 65,572 + one native phase 1,048,632 + exact cap 100,000 = 1,214,204. Adding encode or either 131,072-byte scan would falsely refuse.
        val budget = ImageAllocationBudget(1_214_204L + 17)
        budget.reserve(17).use {
            assertNull(check(budget, cap = 100_000))
            assertEquals(17L, budget.usedBytes)
            budget.reserve(1).use {
                assertError(OperationErrorCode.RESOURCE_LIMIT, "image_allocation_limit", check(budget, cap = 100_000))
                assertEquals(18L, budget.usedBytes)
            }
            assertEquals(17L, budget.usedBytes)
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun changingOnlyTheStagedSinkCapChangesFit() = runTest {
        val budget = ImageAllocationBudget(1_214_204)
        assertNull(check(budget, cap = 100_000))
        assertError(OperationErrorCode.RESOURCE_LIMIT, "image_allocation_limit", check(budget, cap = 100_001))
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun decodeGeometryAndAppearanceOverlapInsteadOfTakingTheirMaximum() = runTest {
        // Decode 1,049,414 + both 65,572-byte stages = 1,180,558. Parser 264,382 and codec 1,118,300 do not overlap that peak.
        assertThreshold(1_180_558)
    }

    @Test fun parserCanDominateAndDoesNotOverlapTheRenderPhase() = runTest {
        // 262,144 + 3 * 700,000 = 2,362,144, independently of the smaller 1,879,812-byte render phase.
        assertThreshold(2_362_144, binding = alternate.copy(blob = alternate.blob.copy(byteCount = 700_000)))
    }

    @Test fun parserClampsByteCountOfActualJobPinAboveOneMiB() = runTest {
        // The JOB pin is 1 MiB + 1, but its parser charge is 262,144 + 3 * 1,048,576 = 3,407,872, above the 2,228,393-byte render peak.
        assertThreshold(3_407_872, binding = alternate.copy(blob = alternate.blob.copy(byteCount = 1_048_577)))
    }

    @Test fun byteDistinctJobPinUsesItsOwnCountEvenWhenAxesMatch() = runTest {
        val rotated = alternate.copy(source = alternate.source.copy(exifOrientation = 6),
            blob = alternate.blob.copy(encodedWidth = 2, encodedHeight = 3))
        assertNull(check(ImageAllocationBudget(1_180_558), binding = rotated))
        assertThreshold(1_180_559, binding = rotated.copy(blob = rotated.blob.copy(byteCount = 747)))
    }

    @Test fun normalizedEquivalentJobPinUsesItsOwnEncodedRowWidth() = runTest {
        // Swapping encoded axes and undoing EXIF preserves 3x2 normalized RGB, but the actual ARGB row charge is four bytes greater.
        assertNull(check(ImageAllocationBudget(1_180_558)))
        assertThreshold(1_180_562, binding = alternate)
    }

    @Test fun rotatedManualWorkspaceAndOrderedAppearanceScratchAreCounted() = runTest {
        val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
        val manual = GeometryRecipe.ManualCurve(normalized, full, PointPx(.5, .1), PointPx(.5, .9), .4, outputQuarterTurns = 1)
        // The direct branch adds 13 MiB proof + 65,536 preparation + (32*3 + 8*2) base-axis bytes. Grayscale needs 8,228 bytes; after bilateral it retains 18 additional bytes, instead of a single 36-byte detachment.
        val graph = recipe.copy(geometry = manual, denoise = DenoiseRecipe.BilateralLumaV1(), binary = BinaryRecipe.GrayscaleV1)
        assertThreshold(14_885_904, page = withRecipe(graph))
    }

    @Test fun exactStraightFullFrameRotationDoesNotPayForMapProof() = runTest {
        val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
        assertThreshold(1_180_558, page = withRecipe(recipe.copy(geometry = GeometryRecipe.ManualCurve(normalized, full, outputQuarterTurns = 1))))
    }

    @Test fun pressureAfterFitStillRefusesARealDecodePermitWithoutLeaking() = runTest {
        val budget = ImageAllocationBudget(1_180_561)
        budget.reserve(3).use {
            assertNull(check(budget))
            assertEquals(3L, budget.usedBytes)
            budget.reserve(131_145).use {
                val error = assertThrows(RepositoryFailure::class.java) { DecodeAdmission(budget).admit(746, 2, 3, 6) }.error
                assertEquals(OperationErrorCode.RESOURCE_LIMIT, error.code)
                assertEquals(131_148L, budget.usedBytes)
            }
            DecodeAdmission(budget).admit(746, 2, 3, 6).use { assertEquals(1_049_417L, budget.usedBytes) }
            assertEquals(3L, budget.usedBytes)
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun explicitFormatAndActualSdkGateThePngPath() = runTest {
        val budget = ImageAllocationBudget(Long.MAX_VALUE)
        for (format in listOf(ExportFormat.JPEG, ExportFormat.PDF)) {
            assertError(OperationErrorCode.UNSUPPORTED_FORMAT, "export_png_format", check(budget, format = format))
        }
        for (sdk in listOf(26, 27)) assertError(OperationErrorCode.PROVIDER_UNAVAILABLE, "export_png_api_unavailable", check(budget, sdk = { sdk }))
        assertNull(check(budget, sdk = { 28 }))
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun invalidCapsAndOverflowReturnStructuredRefusalWithoutAllocation() = runTest {
        val budget = ImageAllocationBudget(Long.MAX_VALUE)
        for (cap in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertError(OperationErrorCode.INVALID_INPUT, "export_png_invalid_cap", check(budget, cap = cap))
        }
        assertError(OperationErrorCode.RESOURCE_LIMIT, "export_png_bitmap_limit", check(budget, cap = Long.MAX_VALUE))
        assertError(OperationErrorCode.RESOURCE_LIMIT, "source_metadata_limit", check(budget, binding = pinned.copy(blob = blob.copy(byteCount = Long.MAX_VALUE))))
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun combinedReadbackPeakFitsAtLongMaxButOverflowsOneByteLater() = runTest {
        // Output is 2x3: native readback adds 1,048,632 bytes, then retained appearance adds 65,572 more.
        val cap = Long.MAX_VALUE - 1_114_204
        val budget = ImageAllocationBudget(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE - 65_571, pngNativeBytes(2, 3, cap + 1))
        assertNull(check(budget, cap = cap))
        assertEquals(0L, budget.usedBytes)
        assertError(OperationErrorCode.RESOURCE_LIMIT, "image_allocation_limit", check(budget, cap = cap + 1))
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun frozenProfileRecipeSizingAndUnavailableAppearanceUseExistingGates() = runTest {
        val budget = ImageAllocationBudget(Long.MAX_VALUE)
        for (page in listOf(row.copy(rendererVersion = "past"), row.copy(providers = "{}".toByteArray()),
            row.copy(fallbackPolicy = "KEEP_STAGE_INPUT"), row.copy(masks = "[{}]".toByteArray()))) {
            assertError(OperationErrorCode.PROVIDER_UNAVAILABLE, "export_frozen_capability_unavailable", check(budget, page = page))
        }
        assertError(OperationErrorCode.UNSUPPORTED_VERSION, "export_recipe_unsupported", check(budget, page = row.copy(recipeSchemaVersion = 2)))
        assertError(OperationErrorCode.INTEGRITY_FAILED, "export_frozen_recipe_mismatch", check(budget, page = row.copy(recipeSha256 = "a".repeat(64))))
        assertError(OperationErrorCode.INTEGRITY_FAILED, "export_frozen_output_invalid", check(budget, page = row.copy(
            outputWidth = 3, outputHeight = 2, requestedOutputWidth = 3, requestedOutputHeight = 2)))
        assertError(OperationErrorCode.PROVIDER_UNAVAILABLE, "appearance_method_not_implemented",
            check(budget, page = withRecipe(recipe.copy(appearance = AppearanceRecipe.LocalMaxGainV1()))))
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun contradictoryRetainedMetadataCannotStandInForTheFrozenPixelBinding() = runTest {
        val budget = ImageAllocationBudget(Long.MAX_VALUE)
        for (binding in listOf(pinned.copy(source = source.copy(exifOrientation = 1)),
            pinned.copy(source = source.copy(conversionFingerprint = "different")),
            pinned.copy(source = source.copy(pixelSha256 = "e".repeat(64))),
            pinned.copy(source = source.copy(sourceBlobId = alternateBlob)),
            pinned.copy(blob = blob.copy(byteSha256 = "e".repeat(64))),
            alternate.copy(source = alternate.source.copy(sourceId = frozenSource)))) {
            assertError(OperationErrorCode.INTEGRITY_FAILED, "export_png_source_binding_mismatch", check(budget, binding = binding))
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun coherentRecordedConversionIsUnavailableOnDifferentDecoderSdk() = runTest {
        val budget = ImageAllocationBudget(2_000_000)
        budget.reserve(17).use {
            // API 29 cannot reproduce an API 28 fingerprint, regardless of the running build string.
            val actual = check(budget, sdk = { 29 })
            assertEquals(17L, budget.usedBytes)
            assertError(OperationErrorCode.UNSUPPORTED_VERSION, "source_conversion_unavailable", actual)
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun exactProducerWithEmbeddedProfileRefusesBeforeAllocationFit() = runTest {
        val producer = pinned
        val profiled = producer.copy(source = producer.source.copy(profileSha256 = "d".repeat(64)))
        val budget = ImageAllocationBudget(2_000_000)
        val preflight = OrdinaryPngPreflight(profile, budget, { 28 }, { fixtureBuild })
        val frozen = snapshot(row)
        budget.reserve(17).use {
            // Same source/blob/byte identity and matching conversion; the retained profile digest is the only changed input.
            assertNull(preflight.check(ExportFormat.PNG, frozen, producer, 4096))
            assertError(OperationErrorCode.UNSUPPORTED_FORMAT, "source_profile_unsupported",
                preflight.check(ExportFormat.PNG, frozen, profiled, 4096))
            assertEquals(17L, budget.usedBytes)
            budget.reserve(budget.capacityBytes - 17).use {
                assertError(OperationErrorCode.RESOURCE_LIMIT, "image_allocation_limit",
                    preflight.check(ExportFormat.PNG, frozen, producer, 4096))
                assertError(OperationErrorCode.UNSUPPORTED_FORMAT, "source_profile_unsupported",
                    preflight.check(ExportFormat.PNG, frozen, profiled, 4096))
                assertEquals(budget.capacityBytes, budget.usedBytes)
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun cancellationDuringSdkObservationPropagatesWithoutTouchingCharges() = runTest {
        val budget = ImageAllocationBudget(2_000_000)
        val owner = Job()
        budget.reserve(17).use {
            val canceled = try {
                withContext(owner) {
                    check(budget, sdk = { owner.cancel(); 28 })
                    fail("A canceled observation returned normally")
                }
                null
            } catch (failure: CancellationException) { failure }
            assertNotNull(canceled)
            assertEquals(17L, budget.usedBytes)
        }
        assertEquals(0L, budget.usedBytes)
    }

    private fun withRecipe(graph: Recipe) = row.copy(recipe = RecipeCodec.encode(graph), recipeSha256 = RecipeCodec.hash(graph))

    private suspend fun assertThreshold(peak: Long, binding: ExportSourceBinding = pinned, page: ExportPageRow = row) {
        assertNull(check(ImageAllocationBudget(peak), binding = binding, page = page))
        val short = ImageAllocationBudget(peak - 1)
        assertError(OperationErrorCode.RESOURCE_LIMIT, "image_allocation_limit", check(short, binding = binding, page = page))
        assertEquals(0L, short.usedBytes)
    }

    private fun assertError(code: OperationErrorCode, key: String, actual: OperationError?) {
        assertEquals(OperationError(code, false, key, pageId), actual)
    }

    private suspend fun check(
        budget: ImageAllocationBudget, cap: Long = 4096, binding: ExportSourceBinding = pinned,
        page: ExportPageRow = row, format: ExportFormat = ExportFormat.PNG, sdk: () -> Int = { 28 },
    ): OperationError? = OrdinaryPngPreflight(profile, budget, sdk, { fixtureBuild }).check(format, snapshot(page), binding, cap)

    private fun snapshot(page: ExportPageRow): ExportPageSnapshot =
        ExportPageSnapshot::class.java.getConstructor(ExportPageRow::class.java).newInstance(page)

}
