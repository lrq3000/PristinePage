package app.pristinepage.core

import app.pristinepage.core.geometry.GeometrySizing
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

/** Literal/synthetic controls distinguish a LOCAL certificate from global/manual raster admission. Reflection makes missing new APIs JUnit assertions, not compilation errors. */
class ManualStretchValidationTest {
    private val source = SourceBinding("a".repeat(64), PixelSize(101, 101), "literal-srgb-v1")
    private val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
    private fun recipe(d: Double = 0.0) = GeometryRecipe.ManualCurve(source, full, PointPx(.5, .1), PointPx(.5, .9), d)
    private fun prepare(r: GeometryRecipe.ManualCurve = recipe(), output: PixelSize = GeometrySizing.target(r)) = ManualCurve.prepare(r, output) {}
    private fun available() { CoreApi.type("geometry.ManualStretchValidator"); CoreApi.type("geometry.GeometryProofBudget") }
    private fun budget(max: Int = 100000) = CoreApi.create("geometry.GeometryProofBudget", max)
    private fun analyze(c: ManualCurve = prepare(), b: Any = budget(), check: () -> Unit = {}) = CoreApi.function("geometry.ManualStretchValidator", "analyze", c, b, check)!!
    private fun get(v: Any, name: String) = CoreApi.call(v, "get$name")!!
    private fun number(v: Any, name: String) = get(v, name) as Double
    private fun count(v: Any, name: String) = get(v, name) as Int
    private fun rejected(c: ManualCurve, fragment: String) {
        available()
        val error = assertThrows(InvalidInverseMapException::class.java) { analyze(c) }
        assertTrue(error.message, error.message.orEmpty().contains(fragment, ignoreCase = true))
    }

    @Test fun safeSymmetricDepthZeroHasSourceNormalizedFiniteLocalBoundsAndActualSupport() {
        val result = analyze()
        assertTrue(number(result, "ReferenceJacobian") in .8..1.1)
        assertTrue(number(result, "MinimumJacobianBound") in .7..1.0)
        assertTrue(number(result, "MaximumFrobeniusBound") in 1.3..2.0)
        assertEquals(1.0, number(result, "MinimumSupport"), 2e-15)
        assertEquals(1.0, number(result, "MeanSupport"), 2e-15)
        assertTrue(number(result, "MinimumXBound") <= 0.0)
        assertTrue(number(result, "MaximumXBound") >= 100.0)
    }

    @Test fun positiveNegativeAndMaximumPermittedDepthHaveNoncollapsingFullDomainBounds() {
        for (d in listOf(.4, -.4, .95, -.95)) {
            val result = analyze(prepare(recipe(d)))
            assertTrue(number(result, "MinimumJacobianBound") > .03)
            assertTrue(number(result, "MaximumFrobeniusBound") < 3.0)
        }
    }

    @Test fun outputDownscaleAndRotationCannotChangeSourceNormalizedProofUnits() {
        val a = analyze(prepare(recipe(.4)))
        val b = analyze(prepare(recipe(.4).copy(outputQuarterTurns = 1), PixelSize(11, 11)))
        for (key in listOf("ReferenceJacobian", "MinimumJacobianBound", "MaximumFrobeniusBound", "MinimumSupport", "MeanSupport")) assertEquals(number(a, key), number(b, key), 0.0)
    }

    @Test fun certificateRetainsExactCandidateIdentityButContainsNoGlobalOrRasterAdmission() {
        val candidate = prepare(); val result = analyze(candidate)
        CoreApi.call(result, "requireCandidate", candidate)
        assertThrows(IllegalArgumentException::class.java) { CoreApi.call(result, "requireCandidate", prepare()) }
        assertEquals(false, get(result, "GlobalBoundarySimplicityProven"))
        assertEquals(false, get(result, "InjectivityProven"))
        assertEquals(false, get(result, "SampledGridOverlapChecked"))
        assertFalse(result.javaClass.methods.any { it.name in listOf("getImage", "getAdmitted", "isValid", "getMap") })
        assertTrue(result.javaClass.constructors.none { !it.isSynthetic })
    }

    @Test fun validConvexCornerQuadDoesNotConcealCrossedCurvedBoundaries() {
        rejected(prepare(recipe().copy(topMidpoint = PointPx(.5, .9), bottomMidpoint = PointPx(.5, .1))), "nonpositive")
    }

    @Test fun positiveOrientationTwoPixelWideLargeSourceIsStillRejectedForStretch() {
        val q = full.copy(tr = PointPx(2.0 / 11999, 0.0), br = PointPx(2.0 / 11999, 1.0))
        val c = prepare(GeometryRecipe.ManualCurve(source.copy(sourceSize = PixelSize(12000, 12000)), q, depth = .1))
        val d = c.derivativesBaseUnit(.5, .5, ManualCurve.KnotSide.RIGHT)
        assertTrue(d.du.x * d.dv.y - d.du.y * d.dv.x > 0)
        rejected(c, "stretch")
    }

    @Test fun independentAffineAnisotropyOracleBracketsThousandWithoutMirroringProofHelpers() {
        available()
        // Affine diag(a,1) has M²/J=a+1/a. a=1/999.9 is below1000; a=1/1000 is ABOVE1000, not equal to it. Nonzero subnormal depth preserves direct dispatch, not a new raw-map seam.
        fun candidate(a: Double): ManualCurve {
            val q = full.copy(tr = PointPx(a, 0.0), br = PointPx(a, 1.0))
            return prepare(GeometryRecipe.ManualCurve(source.copy(sourceSize = PixelSize(12000, 12000)), q, depth = Double.MIN_VALUE))
        }
        analyze(candidate(1.0 / 999.9))
        rejected(candidate(1.0 / 1000.0), "stretch")
    }

    @Test fun outsideDerivedControlWithInBoundsTrueCurveIsNotClampedOrRejected() {
        val q = QuadPx(PointPx(.1, .1), PointPx(.9, .1), PointPx(.9, .9), PointPx(.1, .9))
        val c = prepare(GeometryRecipe.ManualCurve(source, q, PointPx(.5, .01), PointPx(.5, .9)))
        val top = c.topBoundary!!
        assertEquals(-8.0, top.control.y, 1e-12)
        val r = analyze(c)
        assertTrue(number(r, "MinimumYBound") in .999999..1.000001)
        assertEquals(-8.0, top.control.y, 1e-12)
    }

    private fun offMidpoint(cy: Double): ManualCurve {
        val q = QuadPx(PointPx(.1, 0.0), PointPx(.9, .4), PointPx(.9, 1.0), PointPx(.1, 1.0))
        return prepare(GeometryRecipe.ManualCurve(source, q, PointPx(.5, (cy + 20.0) / 200), PointPx(.5, 1.0)))
    }

    @Test fun trueOffMidpointExtentBeyondQuarterPixelRejectsDespiteInBoundsHandlesAndCorners() { rejected(offMidpoint(-3.6), "extent") }

    @Test fun allowedSubquarterExtentRecordsRealBilinearSupportRatherThanCoverageOrClampedCoordinates() {
        val c = offMidpoint(-3.4); val r = analyze(c)
        // Independent quadratic min=-3.4²/(40+6.8)=-0.247008547008547; support is measured at actual129² map positions, not at this continuum minimum.
        assertEquals(-.247008547008547, number(r, "MinimumYBound"), 1e-10)
        assertTrue(number(r, "MinimumSupport") in .75.. .8)
        assertTrue(number(r, "MeanSupport") in .995..1.0)
    }

    @Test fun subquarterExtentsOnBothAxesCanStillFailActualMinimumSupport() {
        // Source21², valid convex corners(0,0),(18,16),(20,20),(16,18); top C=(-1.9,-2.2). Independent extrema are(-.165596330275229,-.237254901960784), each inside the quarter-pixel allowance, but their joint bilinear footprint is not75% source-supported.
        val q = QuadPx(PointPx(0.0, 0.0), PointPx(.9, .8), PointPx(1.0, 1.0), PointPx(.8, .9))
        val r = GeometryRecipe.ManualCurve(source.copy(sourceSize = PixelSize(21, 21)), q, PointPx(.1775, .145), depth = .1)
        rejected(prepare(r), "support")
    }

    @Test fun straightCandidateIsRefusedForSeparateExistingPerspectiveDispatcher() {
        available()
        val c = prepare(GeometryRecipe.ManualCurve(source, full))
        assertEquals(ManualCurve.Dispatch.PERSPECTIVE_STRAIGHT_CANDIDATE, c.dispatch)
        val e = assertThrows(IllegalArgumentException::class.java) { analyze(c) }
        assertTrue(e.message.orEmpty().contains("perspective", true))
    }

    @Test fun asymmetricJointPartitionIncludesBothBoundariesAndBothOneSidedEndpoints() {
        val c = prepare(recipe(.4).copy(topMidpoint = PointPx(.3, .2), bottomMidpoint = PointPx(.6, .85)))
        val r = analyze(c)
        val union = (c.topBoundary!!.arcKnots().toList() + c.bottomBoundary!!.arcKnots().toList()).distinct().sorted()
        assertEquals(union.size - 1, count(r, "JointIntervals"))
        assertEquals(255, count(r, "JointIntervals"))
        assertTrue(count(r, "ProcessedBoxes") >= 255)
        assertTrue(number(r, "MinimumJacobianBound") > 0)
    }

    @Test fun manualEM1LiteralComposedDerivativesAtEachActualStoredKnot32() {
        val c = prepare(recipe(.4).copy(topMidpoint = PointPx(.3, .2), bottomMidpoint = PointPx(.6, .85)))
        // Independently recomputed using70-digit stdlib Decimal power-basis/128-chord inversion; each actual stored knot executes equality-side selection, not a rounded displayed u.
        val rows = listOf(doubleArrayOf(c.topBoundary!!.arcKnot(32), 97.0954017139465, 14.16210740439296, 96.8683545328157, 14.010742616972427, 6.701761208831652, 84.9641704113192), doubleArrayOf(c.bottomBoundary!!.arcKnot(32), 107.44876204702118, -9.962975541834645, 107.79304135258757, -10.04904536822624, 5.772317570790303, 74.32329541524375))
        for (r in rows) for ((index, side) in listOf(ManualCurve.KnotSide.LEFT, ManualCurve.KnotSide.RIGHT).withIndex()) {
            val d = c.derivativesBaseUnit(r[0], .4, side)
            assertEquals(r[1 + index * 2], d.du.x, 1e-9); assertEquals(r[2 + index * 2], d.du.y, 1e-9)
            assertEquals(r[5], d.dv.x, 1e-9); assertEquals(r[6], d.dv.y, 1e-9)
        }
    }

    @Test fun sharedBudgetRefusesBeforeSchedulingRootsAndDoesNotResetAcrossStages() {
        available()
        val b = budget(1)
        val e = assertThrows(GeometryWorkLimitException::class.java) { analyze(b = b) }
        assertEquals(GeometryWorkLimitException::class.java, e.javaClass)
        val before = CoreApi.call(b, "snapshot")!!
        assertEquals(0, count(before, "ScheduledBoxes")); assertEquals(0, count(before, "ProcessedBoxes"))
        CoreApi.call(b, "schedule", 1)
        val after = CoreApi.call(b, "snapshot")!!
        assertEquals(1, count(after, "ScheduledBoxes")); assertEquals(0, count(before, "ScheduledBoxes"))
        assertThrows(GeometryWorkLimitException::class.java) { CoreApi.call(b, "schedule", 1) }
        CoreApi.call(b, "processed")
        assertThrows(IllegalStateException::class.java) { CoreApi.call(b, "processed") }
    }

    @Test fun completedProofChargesCumulativeScheduledAndProcessedCountsAndSecondProofCannotResetThem() {
        val b = budget(128); val c = prepare(GeometryRecipe.ManualCurve(source, full, depth = .1)); val r = analyze(c, b)
        assertEquals(128, count(r, "ScheduledBoxes")); assertEquals(128, count(r, "ProcessedBoxes"))
        assertThrows(GeometryWorkLimitException::class.java) { analyze(c, b) }
        assertEquals(128, count(CoreApi.call(b, "snapshot")!!, "ScheduledBoxes"))
    }

    @Test fun invalidBudgetLimitsAndNegativeSchedulingCannotWrapOrExtendAllowance() {
        available()
        for (limit in listOf(0, -1, 100001, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { budget(limit) }
        val b = budget()
        for (n in listOf(0, -1, Int.MIN_VALUE)) assertThrows(IllegalArgumentException::class.java) { CoreApi.call(b, "schedule", n) }
        assertThrows(GeometryWorkLimitException::class.java) { CoreApi.call(b, "schedule", Int.MAX_VALUE) }
        assertEquals(0, count(CoreApi.call(b, "snapshot")!!, "ScheduledBoxes"))
    }

    @Test fun cancellationAtEntryEveryLatticeRowSubdivisionAndReturnPropagatesOriginalObject() {
        available(); val c = prepare(); var total = 0; analyze(c, check = { total++ })
        for (at in listOf(1, 3, 65, 130, 134, total)) {
            var calls = 0; val signal = CancellationException("checkpoint-$at")
            assertSame(signal, assertThrows(CancellationException::class.java) { analyze(c, check = { if (++calls == at) throw signal }) })
            assertEquals(at, calls)
        }
    }

    @Test fun workAndScratchAreBoundedIndependentOfRequestedOutputRaster() {
        available()
        val bytes = CoreApi.function("geometry.ManualStretchValidator", "workspaceBytes") as Long
        assertTrue(bytes in 16641L * 8 + 8192..1048576L)
        val r = recipe(.4).copy(source = source.copy(sourceSize = PixelSize(12000, 4000)))
        var small = 0; var large = 0
        val a = analyze(prepare(r, PixelSize(120, 40)), check = { small++ })
        val b = analyze(prepare(r, PixelSize(12000, 4000)), check = { large++ })
        assertEquals(small, large); assertTrue(small in 130..100000)
        assertEquals(count(a, "ProcessedBoxes"), count(b, "ProcessedBoxes"))
        assertTrue(count(a, "MaximumDepth") <= 20)
    }

    @Test fun nearThresholdUncertaintyFailsClosedAtDepthTwentyWithoutExpandingAFullTree() {
        available()
        // Decimal oracle: a+1/a=999.99999999900000800000... <1000, but only by1e-9. Scalar lattice values do not establish the outward interval inequality at this uncertainty margin.
        val a = .001000001000003
        val q = full.copy(tr = PointPx(a, 0.0), br = PointPx(a, 1.0))
        val c = prepare(GeometryRecipe.ManualCurve(source.copy(sourceSize = PixelSize(12000, 12000)), q, depth = Double.MIN_VALUE))
        val b = budget()
        val error = assertThrows(InvalidInverseMapException::class.java) { analyze(c, b) }
        assertTrue(error.message, error.message.orEmpty().contains("depth20"))
        val counts = CoreApi.call(b, "snapshot")!!
        assertEquals(168, count(counts, "ScheduledBoxes"))
        assertEquals(21, count(counts, "ProcessedBoxes"))
    }

    @Test fun inconclusiveBoxChargesBothChildrenBeforeAllocatingEitherOne() {
        available()
        val a = .001000001000003
        val q = full.copy(tr = PointPx(a, 0.0), br = PointPx(a, 1.0))
        val c = prepare(GeometryRecipe.ManualCurve(source.copy(sourceSize = PixelSize(12000, 12000)), q, depth = Double.MIN_VALUE))
        val b = budget(129)
        assertThrows(GeometryWorkLimitException::class.java) { analyze(c, b) }
        val counts = CoreApi.call(b, "snapshot")!!
        assertEquals(128, count(counts, "ScheduledBoxes"))
        assertEquals(1, count(counts, "ProcessedBoxes"))
    }

    @Test fun fullJointPartitionCertificateBoundsContainActualBothSidedComposedDerivatives() {
        val c = prepare(recipe(.4).copy(topMidpoint = PointPx(.3, .2), bottomMidpoint = PointPx(.6, .85)))
        val r = analyze(c)
        for (u in (c.topBoundary!!.arcKnots().toList() + c.bottomBoundary!!.arcKnots().toList()).distinct()) for (v in listOf(0.0, .4, 1.0)) for (side in ManualCurve.KnotSide.entries) {
            val d = c.derivativesBaseUnit(u, v, side)
            val j = (d.du.x * d.dv.y - d.du.y * d.dv.x) / 10000
            val norm = kotlin.math.sqrt((d.du.x * d.du.x + d.du.y * d.du.y + d.dv.x * d.dv.x + d.dv.y * d.dv.y) / 10000)
            assertTrue(j >= number(r, "MinimumJacobianBound"))
            assertTrue(norm <= number(r, "MaximumFrobeniusBound"))
            val p = c.mapBaseUnit(u, v)
            assertTrue(p.x >= number(r, "MinimumXBound") && p.x <= number(r, "MaximumXBound"))
            assertTrue(p.y >= number(r, "MinimumYBound") && p.y <= number(r, "MaximumYBound"))
        }
    }

    @Test fun privateCubicExtremaHandleConstantLinearQuadraticTwoRootsAndUncertainDoubleRoot() {
        available()
        // Private scalar numerical routine only, never an alternate public raw-map construction port. Oracles are independent extrema of1,1-z,2-3z+3z² and2+.75z-3z²+2z³.
        val range = Class.forName("app.pristinepage.core.geometry.R")
        val constructor = range.getDeclaredConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType).apply { isAccessible = true }
        val routine = Class.forName("app.pristinepage.core.geometry.ManualStretchValidatorKt").declaredMethods.single { it.name == "cubicMinimum" }.apply { isAccessible = true }
        fun minimum(vararg coefficients: Double): Double {
            val values = java.lang.reflect.Array.newInstance(range, 4)
            for (i in 0..3) java.lang.reflect.Array.set(values, i, constructor.newInstance(coefficients.getOrElse(i) { 0.0 }, coefficients.getOrElse(i) { 0.0 }))
            return routine.invoke(null, values) as Double
        }
        assertEquals(1.0, minimum(1.0), 1e-12)
        assertEquals(0.0, minimum(1.0, -1.0), 1e-12)
        assertEquals(1.25, minimum(2.0, -3.0, 3.0), 1e-12)
        assertEquals(1.698223304703363, minimum(2.0, .75, -3.0, 2.0), 1e-12)
        assertEquals(1.0, minimum(1.0, 1.0, 0.0, 1.0), 1e-12)
        assertEquals(.78906250000084375, minimum(1.0, 2.25e-12, -1.1250000000015, 1.0), 1e-12)
        assertEquals(Double.NEGATIVE_INFINITY, minimum(1.0, .75, -1.5, 1.0), 0.0)
    }
}
