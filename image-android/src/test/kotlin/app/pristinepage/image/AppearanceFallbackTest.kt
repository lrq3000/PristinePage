package app.pristinepage.image

import app.pristinepage.core.image.*
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.*
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.AppearanceRaster
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Real ordered algorithms and admission; deterministic checkpoint faults simulate a failed internal stage. Pixel expectations are literal, not recomputed by the renderer. */
class AppearanceFallbackTest {
    private fun recipe(appearance: AppearanceRecipe = AppearanceRecipe.PaperGainV1(strength = .5), detail: DetailRecipe? = null, binary: BinaryRecipe? = null, denoise: DenoiseRecipe? = null) = Recipe(geometry = GeometryRecipe.None(SourceBinding("a".repeat(64), PixelSize(4,1), "fixture-srgb-v1")), appearance = appearance, detail = detail, binary = binary, denoise = denoise)
    private fun input() = RgbImage(4, 1, 12, byteArrayOf(-56,-56,-56,-56,-56,-56,-56,-56,-56,-61,-61,-61))
    private fun render(engine: AppearanceEngine, image: RgbImage, recipe: Recipe, policy: FallbackPolicy = FallbackPolicy.KEEP_STAGE_INPUT, mask: ProtectionMask? = null, checkpoint: () -> Unit = {}): AppearanceRaster {
        val method = try { engine.javaClass.getMethod("render", RgbImage::class.java, Recipe::class.java, ProtectionMask::class.java, FallbackPolicy::class.java, Function0::class.java) }
        catch (_: NoSuchMethodException) { throw AssertionError("Exact failed-stage appearance fallback is absent") }
        return try { method.invoke(engine, image, recipe, mask, policy, checkpoint) as AppearanceRaster } catch (e: InvocationTargetException) { throw e.targetException }
    }
    private fun failure(raster: AppearanceRaster) = raster.javaClass.getMethod("getFailure").invoke(raster) as OperationError?
    private fun expect(image: RgbImage, vararg gray: Int) = assertArrayEquals(ByteArray(3 * gray.size) { gray[it / 3].toByte() }, image.toPackedByteArray())
    private fun fault() = RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, false, "synthetic_stage_failure", null))

    /** The prefix run locates only the fault stimulus, never expected pixels. Subtract its row-pack checkpoint and final checkpoint; the next graph's following loop checkpoint plus apply-entry checkpoint locate the next actual stage. */
    private fun nextStageApplyCheckpoint(engine: AppearanceEngine, image: RgbImage): Int {
        var calls = 0
        engine.render(image, recipe(), null) { calls++ }.close()
        return calls - image.height + 1
    }

    @Test fun lateDetailFailureKeepsSuccessfulAppearancePixelsAndSkipsFinalGrayscale() {
        val budget = ImageAllocationBudget(2_000_000); val engine = AppearanceEngine(budget)
        input().use { input ->
            val stop = nextStageApplyCheckpoint(engine, input); var calls = 0
            val request = recipe(detail = DetailRecipe.LumaDetail7V1(), binary = BinaryRecipe.GrayscaleV1)
            render(engine, input, request, checkpoint = { if (++calls == stop) throw fault() }).use {
                expect(it.image, 223,223,223,217)
                assertEquals(OperationErrorCode.PROCESSING_FAILED, failure(it)!!.code)
                assertEquals(listOf("paper_gain_v1", "luma_detail7_v1", "grayscale_v1"), it.stages.map { s -> s.stageId })
                assertEquals(listOf("OK", "FAILED_PROCESSING_FAILED", "SKIPPED_AFTER_STAGE_FAILURE"), it.stages.map { s -> s.outcome })
                assertTrue(budget.usedBytes > 0)
            }
            expect(input, 200,200,200,195); assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun missingAppearanceAtZeroStrengthKeepsInputButNeverLabelsIdentityOrRunsBinary() {
        val budget = ImageAllocationBudget(2_000_000); val engine = AppearanceEngine(budget)
        val provider = AppearanceRecipe.ProviderAppearanceV1("missing", 1, "d".repeat(64), "test", "e".repeat(64), 0.0)
        input().use { input ->
            render(engine, input, recipe(provider, binary = BinaryRecipe.SauvolaV1())).use {
                expect(it.image, 200,200,200,195); assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, failure(it)!!.code)
                assertEquals(listOf("FAILED_PROVIDER_UNAVAILABLE", "SKIPPED_AFTER_STAGE_FAILURE"), it.stages.map { s -> s.outcome })
            }
            val error = assertThrows(RepositoryFailure::class.java) { render(engine, input, recipe(provider), FallbackPolicy.FAIL).close() }
            assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, error.error.code); assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun lateNoiseFailureSkipsThresholdingInActualPixelsNotOnlyStageLabels() {
        val budget = ImageAllocationBudget(2_000_000); val engine = AppearanceEngine(budget)
        input().use { input ->
            val stop = nextStageApplyCheckpoint(engine, input); var calls = 0
            render(engine, input, recipe(denoise = DenoiseRecipe.BilateralLumaV1(), binary = BinaryRecipe.SauvolaV1()), checkpoint = { if (++calls == stop) throw fault() }).use {
                expect(it.image,223,223,223,217) // Continuing Sauvola would produce255,255,255,255.
                assertEquals(listOf("OK", "FAILED_PROCESSING_FAILED", "SKIPPED_AFTER_STAGE_FAILURE"), it.stages.map { s -> s.outcome })
            }
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun unknownStageExceptionUsesASafeKeyButAllocationErrorNeverReturnsFallback() {
        val budget = ImageAllocationBudget(2_000_000); val engine = AppearanceEngine(budget)
        input().use { input ->
            val stop = nextStageApplyCheckpoint(engine, input); var calls = 0
            render(engine, input, recipe(detail = DetailRecipe.LumaDetail7V1()), checkpoint = { if (++calls == stop) throw IllegalStateException("private/path/never-publish") }).use {
                expect(it.image,223,223,223,217); assertEquals("appearance_processing_failed", failure(it)!!.safeMessageKey)
                assertFalse(it.stages.joinToString().contains("private/path"))
            }
            calls = 0; val error = OutOfMemoryError("synthetic, no allocation attempted")
            assertSame(error, assertThrows(OutOfMemoryError::class.java) { render(engine, input, recipe(detail = DetailRecipe.LumaDetail7V1()), checkpoint = { if (++calls == stop) throw error }).close() })
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun resourceStorageInvalidAndCancellationNeverBecomeFallbackPixels() {
        val budget = ImageAllocationBudget(2_000_000); val engine = AppearanceEngine(budget)
        input().use { input ->
            val stop = nextStageApplyCheckpoint(engine, input)
            for (code in listOf(OperationErrorCode.RESOURCE_LIMIT, OperationErrorCode.STORAGE_FAILED, OperationErrorCode.INVALID_INPUT, OperationErrorCode.CANCELED)) {
                var calls = 0; val signal = RepositoryFailure(OperationError(code, false, "synthetic_no_fallback", null))
                assertSame(signal, assertThrows(RepositoryFailure::class.java) { render(engine, input, recipe(detail = DetailRecipe.LumaDetail7V1()), checkpoint = { if (++calls == stop) throw signal }).close() })
                assertEquals(0L, budget.usedBytes)
            }
            var calls = 0; val signal = CancellationException("stage canceled")
            assertSame(signal, assertThrows(CancellationException::class.java) { render(engine, input, recipe(detail = DetailRecipe.LumaDetail7V1()), checkpoint = { if (++calls == stop) throw signal }).close() })
            assertEquals(0L, budget.usedBytes); expect(input, 200,200,200,195)
        }
    }

    @Test fun fallbackDoesNotMakeZeroStrengthMaskedDetailAValidGraph() {
        val budget = ImageAllocationBudget(2_000_000)
        input().use { input -> ProtectionMask(4,1,FloatArray(4)).use { mask ->
            var admitted = 0
            val error = assertThrows(RepositoryFailure::class.java) { render(AppearanceEngine(budget), input, recipe(detail = DetailRecipe.LumaDetail7V1(strength = 0.0)), mask = mask, checkpoint = { if (budget.usedBytes > 0) admitted++ }).close() }
            assertEquals(OperationErrorCode.INVALID_INPUT, error.error.code); assertEquals(0, admitted); assertEquals(0L, budget.usedBytes)
        } }
    }

    @Test fun fallbackPacksPaddedCallerInputAndRetainsItsWholePermitUntilClose() {
        val budget = ImageAllocationBudget(2_000_000)
        val provider = AppearanceRecipe.ProviderAppearanceV1("missing",1,"d".repeat(64),"test","e".repeat(64))
        val source = RgbImage(1,2,30,ByteArray(33) { if (it < 3) (it+1).toByte() else if (it >= 30) (it-26).toByte() else 77 })
        val result = render(AppearanceEngine(budget), source, recipe(provider))
        source.close()
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6), result.image.toPackedByteArray()); assertEquals(3, result.image.rowStride); assertTrue(budget.usedBytes > 0)
        result.close(); result.close(); assertEquals(0L, budget.usedBytes)
        assertThrows(IllegalStateException::class.java) { result.image.toPackedByteArray() }
    }

    @Test fun successfulKeepPolicyDoesNotManufactureAFailureOrChangeSingleBlend() {
        val budget = ImageAllocationBudget(2_000_000)
        input().use { input -> render(AppearanceEngine(budget), input, recipe()).use { expect(it.image, 223,223,223,217); assertNull(failure(it)); assertEquals("OK", it.stages.single().outcome) } }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun cancellationWhilePackingFallbackClosesTheAlreadyRetainedStageAndPermit() {
        val budget = ImageAllocationBudget(2_000_000); val engine = AppearanceEngine(budget)
        val provider = AppearanceRecipe.ProviderAppearanceV1("missing",1,"d".repeat(64),"test","e".repeat(64))
        input().use { input ->
            var total = 0; render(engine, input, recipe(provider), checkpoint = { total++ }).close()
            var calls = 0; val signal = CancellationException("fallback delivery")
            assertSame(signal, assertThrows(CancellationException::class.java) { render(engine, input, recipe(provider), checkpoint = { if (++calls == total) throw signal }).close() })
            assertEquals(0L, budget.usedBytes); expect(input,200,200,200,195)
        }
    }
}
