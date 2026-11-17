package app.pristinepage.image

import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Literal scalar oracles exercise the actual ordered Android-side consumer, not a mocked algorithm call log. Geometry is already complete; this adapter may not blend again or rotate the raster. */
class AppearanceEngineTest {
    private fun binding(width: Int, height: Int) = SourceBinding("a".repeat(64), PixelSize(width, height), "test-srgb-v1")
    private fun recipe(width: Int, height: Int, appearance: AppearanceRecipe = AppearanceRecipe.NoneV1, denoise: DenoiseRecipe? = null, detail: DetailRecipe? = null, binary: BinaryRecipe? = null) = Recipe(geometry = GeometryRecipe.None(binding(width, height)), appearance = appearance, denoise = denoise, detail = detail, binary = binary)
    private fun engine(budget: ImageAllocationBudget): Any {
        val type = try { Class.forName("app.pristinepage.image.render.AppearanceEngine") } catch (_: ClassNotFoundException) { fail("Budgeted ordered appearance consumer is absent"); error("unreachable") }
        return type.getConstructor(ImageAllocationBudget::class.java).newInstance(budget)
    }
    private fun render(engine: Any, input: RgbImage, recipe: Recipe, protection: ProtectionMask? = null, checkpoint: () -> Unit = {}): AutoCloseable = try {
        engine.javaClass.getMethod("render", RgbImage::class.java, Recipe::class.java, ProtectionMask::class.java, Function0::class.java).invoke(engine, input, recipe, protection, checkpoint) as AutoCloseable
    } catch (failure: InvocationTargetException) { throw failure.targetException }
    private fun image(result: Any) = result.javaClass.getMethod("getImage").invoke(result) as RgbImage
    @Suppress("UNCHECKED_CAST") private fun stages(result: Any) = result.javaClass.getMethod("getStages").invoke(result) as List<StageRecord>
    private fun neutrals(vararg values: Int) = RgbImage(values.size, 1, values.size * 3, ByteArray(values.size * 3) { values[it / 3].toByte() })
    private fun expectNeutrals(result: Any, vararg values: Int) = assertArrayEquals(ByteArray(values.size * 3) { values[it / 3].toByte() }, image(result).toPackedByteArray())

    @Test fun paperStrengthIsAppliedExactlyOnceAndTheCandidateIsNotBlendedAgain() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        neutrals(200, 200, 200, 195).use { input ->
            render(engine, input, recipe(4, 1, AppearanceRecipe.PaperGainV1(strength = .5))).use { result ->
                expectNeutrals(result, 223, 223, 223, 217)
                assertEquals(listOf("paper_gain_v1"), stages(result).map { it.stageId })
                assertEquals("OK", stages(result).single().outcome)
                assertTrue(budget.usedBytes > 0)
            }
            assertEquals(0L, budget.usedBytes); assertEquals(195, input.getUnsigned(3, 0, 0))
        }
    }

    @Test fun naturalUsesThePublishedColorConversionWithoutASecondStrengthPass() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        neutrals(100, 100, 200, 200).use { input ->
            render(engine, input, recipe(4, 1, AppearanceRecipe.NaturalLV1(strength = .5))).use { result ->
                expectNeutrals(result, 104, 106, 194, 196)
                assertEquals(listOf("natural_l_v1"), stages(result).map { it.stageId })
            }
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun paperBeforeBilateralIsObservableInPixelsNotOnlyStageLabels() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        neutrals(0, 10, 0).use { input ->
            render(engine, input, recipe(3, 1, AppearanceRecipe.PaperGainV1(), DenoiseRecipe.BilateralLumaV1())).use { result ->
                // Paper first gives[0,30,0]; bilateral's nearest-rounded samples remain[0,30,0]. Noise first would give[4,6,4], then gain→[12,18,12].
                expectNeutrals(result, 0, 30, 0)
                assertEquals(listOf("paper_gain_v1", "bilateral_luma_v1"), stages(result).map { it.stageId })
            }
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun detailAndFinalGrayscaleExecuteOnceAfterTheSelectedAppearance() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        neutrals(100, 100, 100, 164, 100, 100, 100).use { input ->
            render(engine, input, recipe(7, 1, detail = DetailRecipe.LumaDetail7V1(), binary = BinaryRecipe.GrayscaleV1)).use { result ->
                expectNeutrals(result, 100, 99, 95, 172, 95, 99, 100)
                assertEquals(listOf("none_v1", "luma_detail7_v1", "grayscale_v1"), stages(result).map { it.stageId })
            }
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun grayscaleIsAnUnblendedFinalRepresentationWithNoHiddenWhitening() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        RgbImage(3, 1, 9, byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1)).use { input ->
            render(engine, input, recipe(3, 1, binary = BinaryRecipe.GrayscaleV1)).use { result -> expectNeutrals(result, 76, 149, 28) }
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun sauvolaUsesTheCurrentStageAndRecordsItsResolvedWindow() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        neutrals(0, 100, 200).use { input ->
            render(engine, input, recipe(3, 1, binary = BinaryRecipe.SauvolaV1())).use { result ->
                expectNeutrals(result, 0, 255, 255)
                assertEquals(listOf("none_v1", "sauvola_v1"), stages(result).map { it.stageId })
                assertTrue(stages(result).last().warningCodes.contains("SAUVOLA_WINDOW_15"))
            }
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun protectionIsConsumedByBothSupportedAdaptersWithoutChangingTheMask() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        neutrals(0, 10, 0).use { input -> ProtectionMask(3, 1, floatArrayOf(1f, 1f, 1f)).use { protection ->
            render(engine, input, recipe(3, 1, AppearanceRecipe.PaperGainV1(), DenoiseRecipe.BilateralLumaV1()), protection).use { result -> expectNeutrals(result, 0, 10, 0) }
            assertEquals(1f, protection.sample(1, 0), 0f); assertEquals(0L, budget.usedBytes)
        } }
    }

    @Test fun incompatibleLaterStagesRefuseBeforeAnyProcessingEvenWhenTheirStrengthIsZero() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        neutrals(100, 200, 100).use { input -> ProtectionMask(3, 1, FloatArray(3)).use { protection ->
            for (request in listOf(recipe(3, 1, AppearanceRecipe.PaperGainV1(), detail = DetailRecipe.LumaDetail7V1(strength = 0.0)), recipe(3, 1, AppearanceRecipe.NaturalLV1(), binary = BinaryRecipe.GrayscaleV1), recipe(3, 1, binary = BinaryRecipe.SauvolaV1()))) {
                var admittedCalls = 0
                val failure = assertThrows(RepositoryFailure::class.java) { render(engine, input, request, protection) { if (budget.usedBytes > 0) admittedCalls++ }.close() }
                assertEquals(OperationErrorCode.INVALID_INPUT, failure.error.code); assertEquals(0, admittedCalls); assertEquals(0L, budget.usedBytes)
            }
            assertEquals(200, input.getUnsigned(1, 0, 0))
        } }
    }

    @Test fun unavailableProviderRemainsUnavailableAtZeroStrengthRatherThanReturningIdentity() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        neutrals(100).use { input ->
            val unavailable = AppearanceRecipe.ProviderAppearanceV1("not-installed", 1, "a".repeat(64), "test", "b".repeat(64), 0.0)
            val failure = assertThrows(RepositoryFailure::class.java) { render(engine, input, recipe(1, 1, unavailable)).close() }
            assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, failure.error.code); assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun graphAdmissionPrecedesAnyAlgorithmAndDoesNotChangeTheSource() {
        val budget = ImageAllocationBudget(1); val engine = engine(budget)
        neutrals(100, 100, 200, 200).use { input ->
            var admittedCalls = 0
            val failure = assertThrows(RepositoryFailure::class.java) { render(engine, input, recipe(4, 1, AppearanceRecipe.NaturalLV1(), DenoiseRecipe.BilateralLumaV1())) { if (budget.usedBytes > 0) admittedCalls++ }.close() }
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code); assertEquals(0, admittedCalls); assertEquals(0L, budget.usedBytes)
            assertEquals(200, input.getUnsigned(3, 0, 0))
        }
    }

    @Test fun cancellationDuringWorkAndAtTheFinalBoundaryReleasesEveryOutputPermit() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        neutrals(100, 100, 200, 200).use { input ->
            val request = recipe(4, 1, AppearanceRecipe.NaturalLV1(), DenoiseRecipe.BilateralLumaV1())
            var total = 0
            render(engine, input, request, checkpoint = { total++ }).close()
            for (stop in listOf(5, total)) {
                var current = 0
                assertThrows(CancellationException::class.java) { render(engine, input, request, checkpoint = { if (++current == stop) throw CancellationException("ordered appearance") }).close() }
                assertEquals(0L, budget.usedBytes); assertEquals(200, input.getUnsigned(3, 0, 0))
            }
        }
    }

    @Test fun identityAndZeroStrengthPublishIndependentPackedOutputNotAPaddedSourceLease() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        for (appearance in listOf(AppearanceRecipe.NoneV1, AppearanceRecipe.PaperGainV1(strength = 0.0), AppearanceRecipe.NaturalLV1(lightnessBlend = 0.0))) {
            val source = RgbImage(1, 2, 30, ByteArray(33) { if (it < 3) (it + 1).toByte() else if (it >= 30) (it - 26).toByte() else 77 })
            render(engine, source, recipe(1, 2, appearance)).use { result ->
                source.close()
                assertEquals(3, image(result).rowStride)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), image(result).toPackedByteArray())
                assertTrue(budget.usedBytes > 0)
                assertEquals("EXACT_IDENTITY", stages(result).single().outcome)
            }
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun exactGraphPeakAndSharedRemainingCapacityAreAdmittedBeforeWork() {
        // N=3: paper setup=8326bytes exceeds bilateral+previousRGB=8222 and final-detach+previousRGB=27; add65536metadata bytes. Literals are independent of the production estimator.
        for (capacity in listOf(73861L, 73862L)) {
            val budget = ImageAllocationBudget(capacity); val engine = engine(budget)
            neutrals(0, 10, 0).use { input ->
                val request = recipe(3, 1, AppearanceRecipe.PaperGainV1(), DenoiseRecipe.BilateralLumaV1())
                if (capacity == 73861L) {
                    val failure = assertThrows(RepositoryFailure::class.java) { render(engine, input, request).close() }
                    assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code)
                } else render(engine, input, request).use { assertEquals(73862L, budget.usedBytes); expectNeutrals(it, 0, 30, 0) }
                assertEquals(0L, budget.usedBytes); assertEquals(10, input.getUnsigned(1, 0, 0))
            }
        }
        val budget = ImageAllocationBudget(74862)
        budget.reserve(1000).use {
            neutrals(0, 10, 0).use { input -> render(engine(budget), input, recipe(3, 1, AppearanceRecipe.PaperGainV1(), DenoiseRecipe.BilateralLumaV1())).use { assertEquals(74862L, budget.usedBytes) } }
            assertEquals(1000L, budget.usedBytes)
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun unexpectedCheckpointFailureUnwindsWithoutFallbackOrSourceClosure() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget); val injected = IllegalStateException("synthetic checkpoint failure")
        neutrals(100, 100, 200, 200).use { input ->
            var calls = 0
            val failure = assertThrows(IllegalStateException::class.java) { render(engine, input, recipe(4, 1, AppearanceRecipe.NaturalLV1()), checkpoint = { if (budget.usedBytes > 0 && ++calls == 5) throw injected }).close() }
            assertSame(injected, failure); assertEquals(0L, budget.usedBytes); assertEquals(200, input.getUnsigned(3, 0, 0))
        }
    }

    @Test fun wholeOutputCloseIsIdempotentAndEndsOnlyItsOwnImageLifetime() {
        val budget = ImageAllocationBudget(2_000_000); val engine = engine(budget)
        neutrals(200).use { input ->
            val result = render(engine, input, recipe(1, 1, AppearanceRecipe.PaperGainV1()))
            assertEquals(245, image(result).getUnsigned(0, 0, 0)); result.close(); result.close()
            assertThrows(IllegalStateException::class.java) { image(result).getUnsigned(0, 0, 0) }
            assertEquals(0L, budget.usedBytes); assertEquals(200, input.getUnsigned(0, 0, 0))
        }
    }
}
