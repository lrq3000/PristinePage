package app.pristinepage.core

import app.pristinepage.core.geometry.GeometryWorkLimitException
import app.pristinepage.core.geometry.InvalidInverseMapException
import app.pristinepage.core.geometry.ManualCurve
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** True quadratic/line boundary tests, not a sampled grid or raster acceptance. A simple boundary still needs the separate local determinant/stretch/visibility certificate before any image may be admitted. */
class ManualBoundaryValidationTest {
    private val binding = SourceBinding("e".repeat(64), PixelSize(101, 101), "literal-srgb-v1")
    private val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
    private fun curve(top: Double = .1, bottom: Double = .9, depth: Double = .4) = ManualCurve.prepare(GeometryRecipe.ManualCurve(binding, full, PointPx(.5, top), PointPx(.5, bottom), depth), PixelSize(101, 101), {})
    private fun analyze(curve: ManualCurve, boxes: Int = 100_000, checkpoint: () -> Unit = {}): Any {
        CoreApi.type("geometry.ManualBoundaryValidator")
        val budget = CoreApi.create("geometry.GeometryProofBudget", boxes)
        return checkNotNull(CoreApi.function("geometry.ManualBoundaryValidator", "analyze", curve, budget, checkpoint))
    }
    private fun reject(candidate: ManualCurve) {
        CoreApi.type("geometry.ManualBoundaryValidator")
        assertThrows(InvalidInverseMapException::class.java) { analyze(candidate) }
    }

    @Test fun safeCurvedBoundaryAndAllPrescribedAdjacentEndpointsAreCertified() {
        for (depth in listOf(-.95, 0.0, .4, .95)) {
            val report = analyze(curve(depth = depth))
            assertEquals(6, CoreApi.call(report, "getBoundaryPairs"))
            assertTrue((CoreApi.call(report, "getBoxes") as Int) > 0)
        }
    }

    @Test fun simpleCurvesWithSmallInteriorGapAreNotRejectedForOverlappingControlHulls() {
        // Actual maximum top y=49.99, minimum bottom y=50.01. Their initial control hulls overlap heavily; certifying their centers or rejecting hull overlap would both be wrong.
        analyze(curve(.4999, .5001))
    }

    @Test fun crossingTopBottomAndNonAdjacentTangencyCannotPassAsAClosedBoundary() {
        reject(curve(.9, .1))
        reject(curve(.5, .5))
        // The top's on-curve midpoint is the opposite BR corner. Sharing TR with the adjacent right edge does not authorize another contact at BR.
        reject(ManualCurve.prepare(GeometryRecipe.ManualCurve(binding, full, topMidpoint = PointPx(1.0, 1.0)), PixelSize(101, 101), {}))
    }

    @Test fun anOffArcLatticeTrueQuadraticTangencyIsRejectedWithoutPolylineSubstitution() {
        val q = QuadPx(PointPx(.1, .1), PointPx(.9, .3), PointPx(.9, .4125), PointPx(.1, .4125))
        val candidate = ManualCurve.prepare(GeometryRecipe.ManualCurve(binding, q, topMidpoint = PointPx(.3, .4)), PixelSize(84, 33), {})
        // Qtop(.625)=(41.25,41.25), exactly on the opposite horizontal bottom. The arc coordinate is not the quadratic parameter or a fixed sampled-grid knot.
        assertEquals(PointPx(41.25, 41.25), candidate.topBoundary!!.pointAtParameter(.625))
        reject(candidate)
    }

    @Test fun aCollinearQuadraticThatRetracesItselfCannotBeAcceptedAsASimpleEdge() {
        val candidate = ManualCurve.prepare(GeometryRecipe.ManualCurve(binding, full, topMidpoint = PointPx(.1, 0.0)), PixelSize(101, 101), {})
        // Qx=-60t+160t² reverses at t=.1875 and retraces its negative-x segment. Valid corners alone do not make this boundary injective.
        assertEquals(PointPx(-5.625, 0.0), candidate.topBoundary!!.pointAtParameter(.1875))
        reject(candidate)
    }

    @Test fun sourceAxisScaleAndFinalRotationDoNotChangeBaseBoundaryTopology() {
        val r = GeometryRecipe.ManualCurve(binding.copy(sourceSize = PixelSize(12000, 4000)), full, PointPx(.5, .1), PointPx(.5, .9), .4)
        for (turn in 0..3) {
            val output = if (turn % 2 == 0) PixelSize(120, 40) else PixelSize(40, 120)
            val report = analyze(ManualCurve.prepare(r.copy(outputQuarterTurns = turn), output, {}))
            assertEquals(6, CoreApi.call(report, "getBoundaryPairs"))
        }
    }

    @Test fun resourceCapAndCancellationStayDistinctFromAnInvalidBoundary() {
        CoreApi.type("geometry.ManualBoundaryValidator")
        assertThrows(GeometryWorkLimitException::class.java) { analyze(curve(.4999, .5001), boxes = 1) }
        for (stop in listOf(1, 3)) {
            var calls = 0
            val cancellation = CancellationException("boundary-$stop")
            val caught = assertThrows(CancellationException::class.java) { analyze(curve(), checkpoint = { if (++calls == stop) throw cancellation }) }
            assertSame(cancellation, caught)
        }
    }

    @Test fun tiltedSourceSpaceEdgesUseActualEndpointContactNotOnlyAxisAlignedBounds() {
        val q = QuadPx(PointPx(.1, .1), PointPx(.8, .2), PointPx(.9, .9), PointPx(.2, .8))
        val r = GeometryRecipe.ManualCurve(binding, q, PointPx(.45, .22), PointPx(.55, .78), .4)
        analyze(ManualCurve.prepare(r, app.pristinepage.core.geometry.GeometrySizing.target(r), {}))
    }

    @Test fun localAndGlobalCertificatesShareBudgetAndExactPreparedCandidateIdentity() {
        CoreApi.type("geometry.ManualBoundaryValidator")
        val candidate = curve()
        val budget = CoreApi.create("geometry.GeometryProofBudget", 100_000)
        val local = checkNotNull(CoreApi.function("geometry.ManualStretchValidator", "analyze", candidate, budget, {}))
        val localCount = CoreApi.call(local, "getProcessedBoxes") as Int
        val global = checkNotNull(CoreApi.function("geometry.ManualBoundaryValidator", "analyze", candidate, budget, {}))
        assertTrue((CoreApi.call(global, "getProcessedBoxes") as Int) > localCount)
        assertEquals((CoreApi.call(global, "getProcessedBoxes") as Int) - localCount, CoreApi.call(global, "getBoxes"))
        CoreApi.call(global, "requireCandidate", candidate)
        assertThrows(IllegalArgumentException::class.java) { CoreApi.call(global, "requireCandidate", curve()) }
        assertEquals(false, CoreApi.call(global, "getInjectivityProven"))
        assertEquals(false, CoreApi.call(global, "getSampledGridOverlapChecked"))
    }

    @Test fun cancellationDuringPairSubdivisionAndImmediatelyBeforeReturnDoesNotPublishACertificate() {
        val candidate = curve(.4999, .5001)
        var total = 0
        analyze(candidate, checkpoint = { total++ })
        for (stop in listOf(total - 1, total)) {
            var calls = 0
            val signal = CancellationException("late boundary-$stop")
            assertSame(signal, assertThrows(CancellationException::class.java) { analyze(candidate, checkpoint = { if (++calls == stop) throw signal }) })
            assertEquals(stop, calls)
        }
    }
}
