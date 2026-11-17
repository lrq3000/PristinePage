package app.pristinepage

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.render.AppearanceEngine
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Packaged CPU arithmetic/graph/ownership consumers only. In-memory synthetic inputs do not establish a saved edit, external picker, native optimized parity or representative-page acceptance. */
@RunWith(AndroidJUnit4::class)
class AppearancePipelineIntegrationTest {
    private fun recipe(w: Int, appearance: AppearanceRecipe = AppearanceRecipe.NoneV1, denoise: DenoiseRecipe? = null, detail: DetailRecipe? = null, binary: BinaryRecipe? = null) = Recipe(geometry = GeometryRecipe.None(SourceBinding("d".repeat(64), PixelSize(w, 1), "test-srgb-v1")), appearance = appearance, denoise = denoise, detail = detail, binary = binary)
    private fun neutrals(vararg v: Int) = RgbImage(v.size, 1, 3 * v.size, ByteArray(v.size * 3) { v[it / 3].toByte() })

    @Test fun packagedLateStageFailureKeepsEnhancedInputAndStopsBinaryExecution() {
        val budget = ImageAllocationBudget(2_000_000); val engine = AppearanceEngine(budget)
        neutrals(200,200,200,195).use { input ->
            val prefix = recipe(4, AppearanceRecipe.PaperGainV1(strength = .5)); var prefixCalls = 0
            engine.render(input, prefix, null) { prefixCalls++ }.close()
            var calls = 0
            val request = prefix.copy(denoise = DenoiseRecipe.BilateralLumaV1(), binary = BinaryRecipe.SauvolaV1())
            engine.render(input, request, null, FallbackPolicy.KEEP_STAGE_INPUT) { if (++calls == prefixCalls) throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED,false,"synthetic_stage_failure",null)) }.use {
                assertArrayEquals(ByteArray(12) { intArrayOf(223,223,223,217)[it/3].toByte() }, it.image.toPackedByteArray())
                assertEquals(OperationErrorCode.PROCESSING_FAILED, it.failure!!.code)
                assertEquals(listOf("OK", "FAILED_PROCESSING_FAILED", "SKIPPED_AFTER_STAGE_FAILURE"), it.stages.map { s -> s.outcome })
            }
            assertEquals(0L, budget.usedBytes); assertEquals(195, input.getUnsigned(3,0,0))
        }
    }

    @Test fun packagedPaperAndNaturalAdaptersMatchLiteralSingleBlendOracles() {
        val budget = ImageAllocationBudget(2_000_000); val engine = AppearanceEngine(budget)
        neutrals(200, 200, 200, 195).use { input ->
            engine.render(input, recipe(4, AppearanceRecipe.PaperGainV1(strength = .5)), null, {}).use { result ->
                assertArrayEquals(ByteArray(12) { intArrayOf(223, 223, 223, 217)[it / 3].toByte() }, result.image.toPackedByteArray())
                assertEquals("paper_gain_v1", result.stages.single().stageId)
            }
        }
        neutrals(100, 100, 200, 200).use { input ->
            engine.render(input, recipe(4, AppearanceRecipe.NaturalLV1(strength = .5)), null, {}).use { result -> assertArrayEquals(ByteArray(12) { intArrayOf(104, 106, 194, 196)[it / 3].toByte() }, result.image.toPackedByteArray()) }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun packagedOrderAndFinalRepresentationHaveNoHiddenSecondEnhancement() {
        val budget = ImageAllocationBudget(2_000_000); val engine = AppearanceEngine(budget)
        neutrals(0, 10, 0).use { input ->
            engine.render(input, recipe(3, AppearanceRecipe.PaperGainV1(), DenoiseRecipe.BilateralLumaV1()), null, {}).use { result ->
                assertArrayEquals(byteArrayOf(0, 0, 0, 30, 30, 30, 0, 0, 0), result.image.toPackedByteArray())
                assertEquals(listOf("paper_gain_v1", "bilateral_luma_v1"), result.stages.map { it.stageId })
            }
        }
        neutrals(0, 100, 200).use { input ->
            engine.render(input, recipe(3, binary = BinaryRecipe.SauvolaV1()), null, {}).use { result ->
                assertArrayEquals(byteArrayOf(0, 0, 0, -1, -1, -1, -1, -1, -1), result.image.toPackedByteArray())
                assertTrue(result.stages.last().warningCodes.contains("SAUVOLA_WINDOW_15"))
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun packagedGraphRefusalAndCancellationDoNotEscapeOwnersOrPixels() {
        val budget = ImageAllocationBudget(2_000_000); val engine = AppearanceEngine(budget)
        neutrals(100, 100, 200, 200).use { input -> ProtectionMask(4, 1, FloatArray(4)).use { protection ->
            val invalid = assertThrows(RepositoryFailure::class.java) { engine.render(input, recipe(4, detail = DetailRecipe.LumaDetail7V1(strength = 0.0)), protection, {}).close() }
            assertEquals(OperationErrorCode.INVALID_INPUT, invalid.error.code); assertEquals(0L, budget.usedBytes)
            var calls = 0
            assertThrows(CancellationException::class.java) { engine.render(input, recipe(4, AppearanceRecipe.NaturalLV1()), null) { if (budget.usedBytes > 0 && ++calls == 5) throw CancellationException("packaged appearance") }.close() }
            assertEquals(0L, budget.usedBytes); assertEquals(200, input.getUnsigned(3, 0, 0)); assertEquals(0f, protection.sample(0, 0), 0f)
        } }
    }
}
