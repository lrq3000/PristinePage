package app.pristinepage

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.image.*
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.render.GeometryEngine
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual packaged CPU proof/sampling only. Synthetic in-memory fixtures do not prove editor/save/import, optimized-native equivalence or physical performance/fidelity. */
@RunWith(AndroidJUnit4::class)
class ManualGeometryIntegrationTest {
    private val binding = SourceBinding("c".repeat(64), PixelSize(101, 101), "literal-srgb-v1")
    private val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
    private fun recipe(turns: Int = 0) = GeometryRecipe.ManualCurve(binding, full, PointPx(.5, .1), PointPx(.5, .9), .4, outputQuarterTurns = turns)
    private fun source() = RgbImage(101, 101, 303, ByteArray(30603) { when (it % 3) { 0 -> (it / 3 % 101).toByte(); 1 -> (it / 3 / 101).toByte(); else -> 37 } })
    private val output = OutputSpec(PixelSize(21, 21), RenderPurpose.PREVIEW)

    @Test fun packagedDirectManualProofAndRotatedTypedSamplesMatchLiteralOracles() {
        val budget = ImageAllocationBudget(32_000_000); val engine = GeometryEngine(budget)
        source().use { input -> ProtectionMask(101, 101, FloatArray(10201) { (it % 101) / 100f }).use { protection -> CoverageMask(101, 101, FloatArray(10201) { (it / 101) / 100f }).use { coverage ->
            engine.render(input, binding, recipe(1), output, protection, coverage, {}).use { result ->
                assertEquals(50, result.image.getUnsigned(15, 10, 0)); assertEquals(36, result.image.getUnsigned(15, 10, 1))
                assertEquals(58, result.image.getUnsigned(10, 10, 1)); assertEquals(37, result.image.getUnsigned(15, 10, 2))
                assertEquals(.5f, result.protection!!.sample(15, 10), 1e-6f); assertEquals(.36f, result.coverage!!.sample(15, 10), 1e-6f)
                assertEquals("manual_ruled_v1", result.stage.stageId)
                val validation = result.javaClass.getMethod("getManualValidation").invoke(result)
                assertNotNull(validation); assertEquals(true, validation!!.javaClass.getMethod("getInjectivityProven").invoke(validation))
            }
            assertEquals(100, input.getUnsigned(100, 100, 0)); assertEquals(1f, protection.sample(100, 100), 0f)
        } } }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun packagedFoldAndAdmissionRefuseWithoutSubstitutePixels() {
        source().use { input ->
            val budget = ImageAllocationBudget(32_000_000)
            val folded = recipe().copy(topMidpoint = PointPx(.5, .9), bottomMidpoint = PointPx(.5, .1))
            val invalid = assertThrows(RepositoryFailure::class.java) { GeometryEngine(budget).render(input, binding, folded, output, null, null, {}).close() }
            assertEquals(OperationErrorCode.INVALID_MAP, invalid.error.code); assertEquals(0L, budget.usedBytes)
            val denied = ImageAllocationBudget(1_000_000)
            val resource = assertThrows(RepositoryFailure::class.java) { GeometryEngine(denied).render(input, binding, recipe(), output, null, null, {}).close() }
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, resource.error.code); assertEquals(0L, denied.usedBytes); assertEquals(50, input.getUnsigned(50, 50, 0))
        }
    }

    @Test fun packagedManualCancellationAndExactStraightDispatchPreserveOwners() {
        val budget = ImageAllocationBudget(32_000_000); val engine = GeometryEngine(budget)
        source().use { input ->
            var calls = 0
            assertThrows(CancellationException::class.java) { engine.render(input, binding, recipe(), output, null, null) { if (budget.usedBytes > 0 && ++calls == 600) throw CancellationException("packaged manual proof") }.close() }
            assertEquals(0L, budget.usedBytes)
            engine.render(input, binding, GeometryRecipe.ManualCurve(binding, full), OutputSpec(binding.sourceSize, RenderPurpose.PREVIEW), null, null, {}).use { result ->
                assertArrayEquals(input.toPackedByteArray(), result.image.toPackedByteArray()); assertEquals("EXACT_PERMUTATION", result.stage.outcome)
                assertTrue(result.stage.warningCodes.contains("MANUAL_STRAIGHT_PERSPECTIVE"))
            }
        }
        assertEquals(0L, budget.usedBytes)
    }
}
