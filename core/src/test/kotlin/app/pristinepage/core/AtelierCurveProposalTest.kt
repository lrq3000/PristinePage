package app.pristinepage.core

import app.pristinepage.core.geometry.AtelierCurveProposal
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.core.recipe.TargetRule
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AtelierCurveProposalTest {
    private val width = 176
    private val height = 144
    private val frame = GeometryRecipe.Perspective(
        SourceBinding("a".repeat(64), PixelSize(width, height), "curve-test"),
        QuadPx(point(20.0, 22.0), point(154.0, 22.0), point(154.0, 118.0), point(20.0, 118.0)),
        targetRule = TargetRule.FixedRatio(1.4), outputQuarterTurns = 1,
    )

    @Test fun proposesEditableControlsWhenTheOutlineAndInteriorLinesAgree() {
        val detected = AtelierCurveProposal.detect(frame, width, height) { x, y -> pixel(x, y, 7.0, 7.0) }
            ?: throw AssertionError("A bowed page with compatible printed lines should have a proposal")
        assertEquals(frame.source, detected.source)
        assertEquals(frame.quad, detected.quad)
        assertEquals(frame.targetRule, detected.targetRule)
        assertEquals(frame.outputQuarterTurns, detected.outputQuarterTurns)
        assertEquals(29.0, detected.topMidpoint.y * (height - 1), 1.0)
        assertEquals(125.0, detected.bottomMidpoint.y * (height - 1), 1.0)
        assertEquals(point(20.0, 70.0), detected.leftMidpoint)
        assertEquals(point(154.0, 70.0), detected.rightMidpoint)
    }

    @Test fun flatOrUnsupportedInteriorDoesNotGetAnInventedCurve() {
        for ((edge, lines) in listOf(0.0 to 0.0, 7.0 to null, 7.0 to 0.0, 7.0 to -7.0)) {
            assertNull("edge=$edge, interior=$lines", AtelierCurveProposal.detect(frame, width, height) { x, y ->
                pixel(x, y, edge, lines)
            })
        }
    }

    @Test fun aTraceableBoundaryWithoutItsCornersIsNotAFullPage() {
        assertNull(AtelierCurveProposal.detect(frame, width, height) { x, y ->
            if (x < 31 && y < 44) 50 else pixel(x, y, 7.0, 7.0)
        })
    }

    @Test fun aSmallMissingCornerIsNotHiddenByGoodFartherProfiles() {
        assertNull(AtelierCurveProposal.detect(frame, width, height) { x, y ->
            if (x in 20..24 && y in 22..24) 50 else pixel(x, y, 7.0, 7.0)
        })
    }

    @Test fun aCurveCannotRemoveAContrastingMarkInsideTheOriginalFrame() {
        assertNull(AtelierCurveProposal.detect(frame, width, height) { x, y ->
            if (x in 83..84 && y in 24..26) 105 else pixel(x, y, 7.0, 7.0)
        })
    }

    private fun point(x: Double, y: Double) = PointPx(x / (width - 1), y / (height - 1))

    // Generate the input independently of the estimator and rectifier: x is unchanged and each column has a quadratic vertical translation.
    private fun pixel(x: Int, y: Int, edgeSag: Double, lineSag: Double?): Int {
        val t = (x - 20.0) / 134
        val shape = 4 * t * (1 - t)
        if (t !in 0.0..1.0 || y < 22 + edgeSag * shape || y > 118 + edgeSag * shape) return 50
        return if (lineSag != null && x in 29..144 && x % 14 < 11 &&
            listOf(36, 48, 60, 72, 84, 96, 108).any { abs(it + lineSag * shape - y) <= .9 }) 45 else 222
    }
}
