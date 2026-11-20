package app.pristinepage.image

import app.pristinepage.core.image.*
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.render.GeometryEngine
import org.junit.Assert.*
import org.junit.Test

class BezierGeometryEngineTest {
    private val size = PixelSize(121, 91)
    private val source = SourceBinding("a".repeat(64), size, "literal-srgb-v1")
    private val frame = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
    private val straight = BezierContour.straight(frame)
    private fun pixels() = RgbImage(121, 91, 363, ByteArray(121 * 91 * 3) { when (it % 3) {
        0 -> (it / 3 % 121).toByte()
        1 -> (it / 3 / 121).toByte()
        else -> 37
    } })

    @Test fun cubicHandleWarpsRgbAndBothMasksAfterClockwiseTurn() {
        val budget = ImageAllocationBudget(4_000_000)
        val recipe = GeometryRecipe.Bezier(source, straight.moveHandle(ContourSide.TOP, 0, true, PointPx(1.0 / 3, .1)),
            outputQuarterTurns = 1)
        val output = OutputSpec(PixelSize(91, 121), RenderPurpose.EXPORT)
        pixels().use { rgb ->
            ProtectionMask(121, 91, FloatArray(121 * 91) { (it % 121) / 120f }).use { protection ->
                CoverageMask(121, 91, FloatArray(121 * 91) { (it / 121) / 90f }).use { coverage ->
                    GeometryEngine(budget).render(rgb, source, recipe, output, protection, coverage) {}.use { raster ->
                        // Final (90,60)→base(.5,0). For a +9 px outgoing handle, the Bernstein weight is 3/8.
                        assertEquals(60, raster.image.getUnsigned(90, 60, 0))
                        assertEquals(3, raster.image.getUnsigned(90, 60, 1))
                        assertEquals(.5f, checkNotNull(raster.protection).sample(90, 60), 1e-7f)
                        assertEquals(.0375f, checkNotNull(raster.coverage).sample(90, 60), 1e-7f)
                        assertEquals("atelier_bezier_v1", raster.stage.stageId)
                    }
                }
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun aStrongBowFailsClosedAndLeavesTheInputsAndBudgetIntact() {
        val budget = ImageAllocationBudget(4_000_000)
        val recipe = GeometryRecipe.Bezier(source, straight.moveHandle(ContourSide.TOP, 0, true, PointPx(1.0 / 3, .98)))
        pixels().use { input ->
            val failure = assertThrows(RepositoryFailure::class.java) {
                GeometryEngine(budget).render(input, source, recipe, OutputSpec(size, RenderPurpose.PREVIEW), null, null) {}.close()
            }
            assertEquals(OperationErrorCode.INVALID_MAP, failure.error.code)
            assertEquals(0, input.getUnsigned(60, 0, 1))
        }
        assertEquals(0L, budget.usedBytes)
    }
}
