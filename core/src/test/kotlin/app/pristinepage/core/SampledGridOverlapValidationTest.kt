package app.pristinepage.core

import app.pristinepage.core.geometry.GeometryProofBudget
import app.pristinepage.core.geometry.GeometryWorkLimitException
import app.pristinepage.core.geometry.InvalidInverseMapException
import app.pristinepage.core.geometry.ManualCurve
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** A sampled diagnostic must reject implementation regressions without pretending to certify the continuous manual map. Private grid tests exercise the same cell/index predicates, never an alternate public admitted-map factory. */
class SampledGridOverlapValidationTest {
    private val binding = SourceBinding("f".repeat(64), PixelSize(101, 101), "literal-srgb-v1")
    private val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
    private fun candidate() = ManualCurve.prepare(GeometryRecipe.ManualCurve(binding, full, PointPx(.5, .1), PointPx(.5, .9), .4), PixelSize(101, 101), {})
    private fun analyze(c: ManualCurve = candidate(), b: GeometryProofBudget = GeometryProofBudget(), pairs: Int = 1_000_000, check: () -> Unit = {}): Any {
        CoreApi.type("geometry.SampledGridOverlapValidator")
        return CoreApi.function("geometry.SampledGridOverlapValidator", "analyze", c, b, pairs, check)!!
    }
    private fun get(r: Any, key: String) = CoreApi.call(r, "get$key")!!
    private fun grid(points: Array<PointPx>, width: Int, height: Int, pairs: Int = 1_000_000): Any {
        CoreApi.type("geometry.SampledGridOverlapValidator")
        val method = Class.forName("app.pristinepage.core.geometry.SampledGridOverlapValidatorKt").declaredMethods.single { it.name == "analyzeGrid" }.apply { isAccessible = true }
        return try { method.invoke(null, points, width, height, GeometryProofBudget(), pairs, {})!! } catch (e: InvocationTargetException) { throw e.targetException }
    }

    @Test fun fixedGridAcceptsOnlyPrescribedNeighborContactsAndRetainsExactCandidate() {
        val c = candidate(); val r = analyze(c)
        assertEquals(1024, get(r, "Cells")); assertEquals(33, get(r, "Columns")); assertEquals(33, get(r, "Rows"))
        assertTrue(get(r, "SampledGridOverlapChecked") as Boolean); assertEquals(false, get(r, "InjectivityProven"))
        CoreApi.call(r, "requireCandidate", c)
        assertThrows(IllegalArgumentException::class.java) { CoreApi.call(r, "requireCandidate", candidate()) }
        assertTrue((get(r, "CandidatePairs") as Int) in 3906..10000)
    }

    @Test fun localCornerFoldAndMirrorRejectEvenWithAPositiveCenterOrUniformSign() {
        CoreApi.type("geometry.SampledGridOverlapValidator")
        assertThrows(InvalidInverseMapException::class.java) { grid(arrayOf(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(0.0, 1.0), PointPx(-.2, 1.0)), 2, 2) }
        assertThrows(InvalidInverseMapException::class.java) { grid(arrayOf(PointPx(1.0, 0.0), PointPx(0.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0)), 2, 2) }
    }

    @Test fun twiceWrappedPositiveCellsFailNonlocalPairChecksAcrossRowsAndColumns() {
        CoreApi.type("geometry.SampledGridOverlapValidator")
        val nodes = Array(17 * 3) { n -> val i = n % 17; val j = n / 17; val theta = 4 * Math.PI * i / 16; val radius = 2 - j / 2.0; PointPx(3 + radius * cos(theta), 3 + radius * sin(theta)) }
        assertThrows(InvalidInverseMapException::class.java) { grid(nodes, 17, 3) }
    }

    @Test fun exactIdentityGridAllowsSharedEdgesAndDiagonalVerticesButNotForeignTouches() {
        val nodes = Array(9) { PointPx((it % 3).toDouble(), (it / 3).toDouble()) }
        grid(nodes, 3, 3)
        CoreApi.type("geometry.SampledGridOverlapValidator")
        // Two rows wrapped around a complete annulus: end cells are not grid neighbors, although their distinct endpoints occupy the same coordinates exactly.
        val ring = arrayOf(PointPx(2.0, 0.0), PointPx(0.0, 2.0), PointPx(-2.0, 0.0), PointPx(0.0, -2.0), PointPx(2.0, 0.0), PointPx(1.0, 0.0), PointPx(0.0, 1.0), PointPx(-1.0, 0.0), PointPx(0.0, -1.0), PointPx(1.0, 0.0))
        assertThrows(InvalidInverseMapException::class.java) { grid(ring, 5, 2) }
    }

    @Test fun pairCapAndSharedWorkRefusalCannotSkipUncheckedCells() {
        CoreApi.type("geometry.SampledGridOverlapValidator")
        assertThrows(GeometryWorkLimitException::class.java) { analyze(pairs = 1) }
        val b = GeometryProofBudget(1)
        assertThrows(GeometryWorkLimitException::class.java) { analyze(b = b) }
        assertEquals(0, b.snapshot().scheduledBoxes)
        for (invalid in listOf(0, 1_000_001, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { analyze(pairs = invalid) }
    }

    @Test fun indexConstructionAndQueryExpansionConsumeTheSameNonrefundableWorkAllowance() {
        CoreApi.type("geometry.SampledGridOverlapValidator")
        // The complete fixed index is3071 boxes. Its first root-query visit needs another scheduled unit, not a reset/refund of completed construction.
        val b = GeometryProofBudget(3071)
        assertThrows(GeometryWorkLimitException::class.java) { analyze(b = b) }
        assertEquals(3071, b.snapshot().scheduledBoxes); assertEquals(3071, b.snapshot().processedBoxes)
        assertThrows(GeometryWorkLimitException::class.java) { b.schedule(1) }
    }

    @Test fun exactTiltedCellsRetainTheirSharedEdgeWithoutAnAxisAlignedShortcut() {
        val nodes = Array(9) { n -> val x = (n % 3).toDouble(); val y = (n / 3).toDouble(); PointPx(2 * x + y, x + 3 * y) }
        grid(nodes, 3, 3)
    }

    @Test fun cancellationAtGridIndexPairAndReturnBoundariesPropagatesWithoutReport() {
        var total = 0; analyze(check = { total++ })
        for (stop in listOf(1, 20, 40, total - 1, total)) {
            var calls = 0; val signal = CancellationException("sampled-$stop")
            assertSame(signal, assertThrows(CancellationException::class.java) { analyze(check = { if (++calls == stop) throw signal }) })
        }
    }
}
