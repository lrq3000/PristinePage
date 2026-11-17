package app.pristinepage

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.image.CoverageMask
import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.core.recipe.TargetRule
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.render.GeometryEngine
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual installed Android CPU execution of the packaged solver/proof/sampler. Generated in-memory inputs only; no picker result, library mutation, storage permission or optimized/native-backend parity claim. */
@RunWith(AndroidJUnit4::class)
class PerspectiveGeometryIntegrationTest {
    private val binding = SourceBinding("c".repeat(64), PixelSize(160, 120), "test-srgb-v1")
    private val quad = QuadPx(PointPx(20.0 / 159, 10.0 / 119), PointPx(127.27272727272727 / 159, 13.636363636363635 / 119), PointPx(115.38461538461539 / 159, 80.76923076923076 / 119), PointPx(25.0 / 159, 83.33333333333334 / 119))
    private fun source() = RgbImage(160, 120, 480, ByteArray(160 * 120 * 3) { i -> when (i % 3) { 0 -> (i / 3 % 160).toByte(); 1 -> (i / 3 / 160).toByte(); else -> 0 } })

    @Test fun packagedNonAffineSolveProofAndTypedMasksMatchTheIndependentPixelOracle() {
        val budget = ImageAllocationBudget(2_000_000)
        source().use { source -> ProtectionMask(160, 120, FloatArray(160 * 120) { (it % 160).toFloat() / 159 }).use { mask ->
            CoverageMask(160, 120, FloatArray(160 * 120) { (it / 160).toFloat() / 119 }).use { coverage ->
                val recipe = GeometryRecipe.Perspective(binding, quad, TargetRule.FixedRatio(11.0 / 7))
                GeometryEngine(budget).render(source, binding, recipe, OutputSpec(PixelSize(11, 7), RenderPurpose.PREVIEW), mask, coverage, {}).use { result ->
                    assertEquals(74, result.image.getUnsigned(5, 3, 0)); assertEquals(50, result.image.getUnsigned(5, 3, 1))
                    assertEquals(.3600162870881771, result.perspectiveValidation!!.referenceJacobian, 1e-12)
                    assertEquals(73.91304347826087 / 159, result.protection!!.sample(5, 3).toDouble(), 1e-6)
                    assertEquals(50.0 / 119, result.coverage!!.sample(5, 3).toDouble(), 1e-6)
                    assertEquals("perspective_v1", result.stage.stageId)
                }
                assertEquals(0L, budget.usedBytes); assertEquals(1f, mask.sample(159, 0), 0f)
            }
        } }
    }

    @Test fun packagedQuarterTurnComposesThePerspectiveMapAndKeepsTheSource() {
        val budget = ImageAllocationBudget(2_000_000)
        source().use { source ->
            val recipe = GeometryRecipe.Perspective(binding, quad, TargetRule.FixedRatio(1.0), outputQuarterTurns = 1)
            GeometryEngine(budget).render(source, binding, recipe, OutputSpec(PixelSize(11, 11), RenderPurpose.PREVIEW), null, null, {}).use { result ->
                assertEquals(25, result.image.getUnsigned(0, 0, 0)); assertEquals(83, result.image.getUnsigned(0, 0, 1))
                assertEquals(54, result.image.getUnsigned(7, 3, 0)); assertEquals(35, result.image.getUnsigned(7, 3, 1))
                assertTrue(result.stage.warningCodes.contains("OUTPUT_QUARTER_TURNS_1"))
            }
            assertEquals(0L, budget.usedBytes); assertEquals(20, source.getUnsigned(20, 10, 0))
        }
    }

    @Test fun packagedAdmissionAndProofCancellationNeverReturnAnUnaccountedRaster() {
        source().use { source ->
            val recipe = GeometryRecipe.Perspective(binding, quad, TargetRule.FixedRatio(1.0))
            val output = OutputSpec(PixelSize(11, 11), RenderPurpose.PREVIEW)
            val denied = ImageAllocationBudget(100_000)
            val failure = assertThrows(RepositoryFailure::class.java) { GeometryEngine(denied).render(source, binding, recipe, output, null, null, {}).close() }
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code); assertEquals(0L, denied.usedBytes)
            val budget = ImageAllocationBudget(2_000_000)
            var calls = 0
            assertThrows(CancellationException::class.java) { GeometryEngine(budget).render(source, binding, recipe, output, null, null) { if (budget.usedBytes > 0 && ++calls == 5) throw CancellationException("native proof cancellation") }.close() }
            assertEquals(0L, budget.usedBytes); assertEquals(20, source.getUnsigned(20, 10, 0))
        }
    }
}
