package app.pristinepage.core

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.core.recipe.TargetRule
import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/** Independent GEOMETRY §12 vectors and boundary arithmetic only; no test here certifies topology, stretch, support or raster acceptance. Reflection keeps the initial missing implementation RED in JUnit. */
class ManualCurveMathTest {
    private val source = SourceBinding("a".repeat(64), PixelSize(101, 101), "literal-srgb-v1")
    private val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
    private fun recipe(depth: Double = 0.0) = GeometryRecipe.ManualCurve(source, full, PointPx(.5, .1), PointPx(.5, .9), depth)
    private fun prepare(r: GeometryRecipe.ManualCurve = recipe(), output: PixelSize = PixelSize(101, 101), checkpoint: () -> Unit = {}): Any = checkNotNull(CoreApi.function("geometry.ManualCurve", "prepare", r, output, checkpoint))
    private fun call(value: Any, name: String, vararg args: Any?): Any? = CoreApi.call(value, name, *args)
    private fun boundary(value: Any, top: Boolean = true): Any = checkNotNull(call(value, if (top) "getTopBoundary" else "getBottomBoundary"))
    private fun side(left: Boolean): Any = CoreApi.type("geometry.ManualCurve\$KnotSide").enumConstants.single { it.toString() == if (left) "LEFT" else "RIGHT" }
    private fun map(value: Any, u: Double, v: Double): PointPx = call(value, "mapBaseUnit", u, v) as PointPx
    private fun derivative(value: Any, u: Double, v: Double, left: Boolean = false): Any = checkNotNull(call(value, "derivativesBaseUnit", u, v, side(left)))
    private fun point(x: Double, y: Double, actual: PointPx, tolerance: Double = 1e-9) { assertEquals(x, actual.x, tolerance); assertEquals(y, actual.y, tolerance) }
    private fun numericalRefusal(action: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java, action)
        assertEquals("app.pristinepage.core.geometry.InvalidManualCurveException", failure.javaClass.name)
    }

    @Test fun onCurveHandlesDeriveTwiceTheSagittaRatherThanBecomingControlPoints() {
        val curve = prepare()
        point(50.0, 20.0, call(boundary(curve), "getControl") as PointPx)
        point(50.0, 80.0, call(boundary(curve, false), "getControl") as PointPx)
        point(50.0, 10.0, call(boundary(curve), "pointAtParameter", .5) as PointPx)
        point(50.0, 90.0, call(boundary(curve, false), "pointAtParameter", .5) as PointPx)
    }

    @Test fun literalDepthZeroCenterIsFiftyFifty() { point(50.0, 50.0, map(prepare(), .5, .5)) }
    @Test fun literalPositiveDepthMovesCenterToFiftyFiftyEight() { point(50.0, 58.0, map(prepare(recipe(.4)), .5, .5)) }
    @Test fun literalNegativeDepthMovesCenterToFiftyFortyTwo() { point(50.0, 42.0, map(prepare(recipe(-.4)), .5, .5)) }
    @Test fun literalMaximumDepthMovesCenterToFiftySixtyNine() { point(50.0, 69.0, map(prepare(recipe(.95)), .5, .5)) }

    @Test fun fullEdgesCornersAndSeparateSymmetricArcMidpointsHaveNoInset() {
        val curve = prepare(recipe(.4))
        point(0.0, 0.0, map(curve, 0.0, 0.0)); point(100.0, 0.0, map(curve, 1.0, 0.0))
        point(100.0, 100.0, map(curve, 1.0, 1.0)); point(0.0, 100.0, map(curve, 0.0, 1.0))
        point(0.0, 60.0, map(curve, 0.0, .5)); point(100.0, 60.0, map(curve, 1.0, .5))
        point(50.0, 10.0, map(curve, .5, 0.0)); point(50.0, 90.0, map(curve, .5, 1.0))
        for (edge in listOf(boundary(curve), boundary(curve, false))) {
            assertEquals(0.0, call(edge, "parameterAtArc", 0.0) as Double, 0.0)
            assertEquals(1.0, call(edge, "parameterAtArc", 1.0) as Double, 0.0)
            assertEquals(.5, call(edge, "parameterAtArc", .5) as Double, 1e-14)
        }
    }

    @Test fun asymmetricIndependentChordOracleDistinguishesArcInversionFromParameterInterpolation() {
        val curve = prepare(recipe().copy(topMidpoint = PointPx(.3, .2), bottomMidpoint = PointPx(.6, .85)))
        val top = boundary(curve); val bottom = boundary(curve, false)
        // Independent 60-digit decimal evaluation of Qtop=(20t+80t²,80t-80t²), Qbottom=(140t-40t²,100-60t+60t²), followed by summing exactly 128 Euclidean chords and linear inversion.
        assertEquals(112.63955543314184, call(top, "getTotalChordLength") as Double, 1e-10)
        assertEquals(106.15057785891904, call(bottom, "getTotalChordLength") as Double, 1e-10)
        assertEquals(.6501555407968600, call(top, "parameterAtArc", .5) as Double, 1e-12)
        assertEquals(.4115320522859290, call(bottom, "parameterAtArc", .5) as Double, 1e-12)
        point(46.81928899424580, 18.19626508544020, map(curve, .5, 0.0))
        point(50.84014211768332, 85.46959466636438, map(curve, .5, 1.0))
        assertTrue(abs(map(curve, .5, 0.0).x - 30.0) > 10.0)
    }

    @Test fun lutHas129StrictKnotsAndReadCopiesCannotMutateTheCandidate() {
        val curve = prepare(); val top = boundary(curve)
        val knots = call(top, "arcKnots") as DoubleArray
        assertEquals(129, knots.size); assertEquals(0.0, knots.first(), 0.0); assertEquals(1.0, knots.last(), 0.0)
        assertTrue((1..128).all { knots[it] > knots[it - 1] })
        assertEquals(.25473498252588627, knots[32], 1e-14)
        for (k in 0..128) assertEquals(k / 128.0, call(top, "parameterAtArc", knots[k]) as Double, 1e-13)
        knots.fill(Double.NaN)
        assertEquals(.25473498252588627, call(top, "arcKnot", 32) as Double, 1e-14)
        point(50.0, 50.0, map(curve, .5, .5))
        assertThrows(IllegalArgumentException::class.java) { call(top, "arcKnot", -1) }
        assertThrows(IllegalArgumentException::class.java) { call(top, "arcKnot", 129) }
    }

    @Test fun analyticDerivativesUseSourcePixelsPerUnitBaseDestinationNotOutputPixels() {
        val curve = prepare(recipe(.4), PixelSize(11, 11))
        val derivatives = derivative(curve, .5, .5)
        point(102.60541092128765, 0.0, call(derivatives, "getDu") as PointPx)
        point(0.0, 80.0, call(derivatives, "getDv") as PointPx)
        point(50.0, 58.0, call(curve, "map", 5.0, 5.0) as PointPx)
        val corner = derivative(curve, 0.0, 0.0)
        point(0.0, 140.0, call(corner, "getDv") as PointPx)
    }

    @Test fun explicitKnotSuppliesBothDistinctAnalyticOneSidedDerivatives() {
        val curve = prepare(); val top = boundary(curve)
        val u = call(top, "arcKnot", 32) as Double
        point(25.0, 7.5, map(curve, u, 0.0))
        point(100.55249259873960, 20.11049851974792, call(derivative(curve, u, 0.0, true), "getDu") as PointPx)
        point(100.67342054123554, 20.13468410824711, call(derivative(curve, u, 0.0), "getDu") as PointPx)
        point(100.55249259873960, 20.11049851974792, call(top, "derivativeAtArc", u, side(true)) as PointPx)
        point(100.67342054123554, 20.13468410824711, call(top, "derivativeAtArc", u, side(false)) as PointPx)
        point(100.0, 20.0, call(top, "derivativeAtParameter", .25) as PointPx)
    }

    @Test fun trueQuadraticExtremaIncludeAnOffMidpointPeakNotJustCornersOrHandles() {
        val q = QuadPx(PointPx(.1, .1), PointPx(.9, .3), PointPx(.9, .9), PointPx(.1, .9))
        val curve = prepare(GeometryRecipe.ManualCurve(source, q, PointPx(.3, .4)), PixelSize(84, 81))
        val top = boundary(curve)
        point(10.0, 60.0, call(top, "getControl") as PointPx)
        point(41.25, 41.25, call(top, "pointAtParameter", .625) as PointPx)
        val bounds = checkNotNull(call(top, "getBounds"))
        assertEquals(10.0, call(bounds, "getMinX") as Double, 1e-12)
        assertEquals(90.0, call(bounds, "getMaxX") as Double, 1e-12)
        assertEquals(10.0, call(bounds, "getMinY") as Double, 1e-12)
        assertEquals(41.25, call(bounds, "getMaxY") as Double, 1e-12)
    }

    @Test fun derivedOutOfSourceControlIsNotClampedAndActualBoundaryExtremaRemainObservable() {
        val curve = prepare(recipe().copy(topMidpoint = PointPx(.1, .1)))
        val top = boundary(curve)
        point(-30.0, 20.0, call(top, "getControl") as PointPx)
        val bounds = checkNotNull(call(top, "getBounds"))
        assertEquals(-5.625, call(bounds, "getMinX") as Double, 1e-12)
        point(10.0, 10.0, call(top, "pointAtParameter", .5) as PointPx)
    }

    private fun projective() = GeometryRecipe.ManualCurve(SourceBinding("b".repeat(64), PixelSize(160, 120), "literal-srgb-v1"), QuadPx(PointPx(20.0 / 159, 10.0 / 119), PointPx(127.27272727272727 / 159, 13.636363636363635 / 119), PointPx(115.38461538461539 / 159, 80.76923076923076 / 119), PointPx(25.0 / 159, 83.33333333333334 / 119)), targetRule = TargetRule.FixedRatio(1.0))

    @Test fun exactSerializedStraightTrapezoidDispatchesToThePublishedHomographyNotBilinear() {
        val r = projective(); val restored = RecipeCodec.decode(RecipeCodec.encode(Recipe(geometry = r))).geometry as GeometryRecipe.ManualCurve
        val curve = prepare(restored, PixelSize(11, 11))
        assertEquals("PERSPECTIVE_STRAIGHT_CANDIDATE", call(curve, "getDispatch").toString())
        point(73.91304347826087, 50.0, map(curve, .5, .5), 1e-6)
        point(73.91304347826087, 50.0, call(curve, "map", 5.0, 5.0) as PointPx, 1e-6)
        // From published G at x=y=5: z=1.15,nx=85,ny=57.5; differentiate each quotient and multiply by ten for unit-domain derivatives.
        val d = derivative(curve, .5, .5)
        point(97.92060491493384, 0.0, call(d, "getDu") as PointPx, 1e-6)
        point(-4.158790170132325, 69.56521739130434, call(d, "getDv") as PointPx, 1e-6)
    }

    @Test fun nonzeroTinyCurvatureOrDepthNeverUsesAnImplicitStraightnessTolerance() {
        val r = projective()
        for (curved in listOf(r.copy(topMidpoint = PointPx(r.topMidpoint.x, Math.nextUp(r.topMidpoint.y))), r.copy(depth = Double.MIN_VALUE))) {
            val curve = prepare(curved, PixelSize(11, 11))
            assertEquals("DIRECT_RULED_CANDIDATE", call(curve, "getDispatch").toString())
            assertNotNull(boundary(curve))
        }
    }

    @Test fun finalUnitAndDestinationCentersComposeQuarterTurnsWithoutChangingSourceControls() {
        val r = recipe(.4); val before = RecipeCodec.encode(Recipe(geometry = r))
        // Nontrivial samples: q1(.25,.75)=(.75,.75), q2(0,0)=(1,1), q3(0,0)=(1,0).
        val base = prepare(r)
        for (q in 0..3) {
            val rotated = prepare(r.copy(outputQuarterTurns = q), PixelSize(11, 11))
            val actual = call(rotated, "mapUnit", .25, .75) as PointPx
            val uv = listOf(.25 to .75, .75 to .75, .75 to .25, .25 to .25)[q]
            assertEquals(map(base, uv.first, uv.second), actual)
            assertEquals(actual, call(rotated, "map", 2.5, 7.5))
        }
        assertArrayEquals(before, RecipeCodec.encode(Recipe(geometry = r)))
        val small = GeometryRecipe.ManualCurve(source.copy(sourceSize = PixelSize(3, 2)), full, outputQuarterTurns = 1)
        val rotated = prepare(small, PixelSize(2, 3))
        point(0.0, 1.0, call(rotated, "map", 0.0, 0.0) as PointPx, 0.0)
        point(2.0, 0.0, call(rotated, "map", 1.0, 2.0) as PointPx, 0.0)
        point(1.5, .75, call(rotated, "mapUnit", .25, .75) as PointPx, 0.0)
    }

    @Test fun sourceBindingIsRetainedAndMismatchedIdentityDimensionsOrConversionReject() {
        val curve = prepare(); assertEquals(source, call(curve, "getSource")); call(curve, "requireSource", source)
        for (other in listOf(source.copy(sourcePixelHash = "c".repeat(64)), source.copy(sourceSize = PixelSize(201, 101)), source.copy(orientationColorConversionVersion = "different-v1"))) {
            assertThrows(IllegalArgumentException::class.java) { call(curve, "requireSource", other) }
        }
    }

    @Test fun typedInputRejectsBadHandlesDepthTurnsArcCountAndSingletonSourcesWithoutClamping() {
        prepare()
        for (p in listOf(PointPx(-.001, .5), PointPx(.5, 1.001))) assertThrows(IllegalArgumentException::class.java) { recipe().copy(topMidpoint = p) }
        for (d in listOf(1.0, -.9500000001, Double.NaN, Double.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) { recipe(d) }
        for (q in listOf(-1, 4)) assertThrows(IllegalArgumentException::class.java) { recipe().copy(outputQuarterTurns = q) }
        assertThrows(IllegalArgumentException::class.java) { recipe().copy(arcSegments = 127) }
        assertThrows(IllegalArgumentException::class.java) { recipe().copy(source = source.copy(sourceSize = PixelSize(1, 101))) }
        assertThrows(IllegalArgumentException::class.java) { PointPx(Double.NaN, .5) }
        prepare(recipe(-.95))
    }

    @Test fun preflightRejectsInvalidQuadTargetSingletonUpscalingAndInconsistentOutputAspect() {
        prepare()
        val invalid = recipe().copy(quad = full.copy(tr = full.br, br = full.tr))
        assertThrows(IllegalArgumentException::class.java) { prepare(invalid) }
        assertThrows(IllegalArgumentException::class.java) { prepare(recipe().copy(targetRule = TargetRule.FixedRatio(1e-10))) }
        for (size in listOf(PixelSize(1, 11), PixelSize(11, 1), PixelSize(102, 102), PixelSize(51, 40))) assertThrows(IllegalArgumentException::class.java) { prepare(output = size) }
        prepare(output = PixelSize(51, 51))
    }

    @Test fun mappingAndBoundaryQueriesRefuseNonfiniteOrOutOfDomainSamples() {
        val curve = prepare(); val top = boundary(curve)
        for (u in listOf(-1e-12, 1.0000000001, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { map(curve, u, .5) }
            assertThrows(IllegalArgumentException::class.java) { call(curve, "mapUnit", .5, u) }
            assertThrows(IllegalArgumentException::class.java) { call(curve, "map", u * 100, 0.0) }
            assertThrows(IllegalArgumentException::class.java) { derivative(curve, .5, u) }
            for (method in listOf("pointAtParameter", "pointAtArc", "parameterAtArc", "derivativeAtParameter")) assertThrows(IllegalArgumentException::class.java) { call(top, method, u) }
            assertThrows(IllegalArgumentException::class.java) { call(top, "derivativeAtArc", u, side(false)) }
        }
    }

    @Test fun totalLengthAtMostOneAndExactZeroOrUncertainChordSegmentsAreNumericalFailures() {
        prepare()
        numericalRefusal { prepare(GeometryRecipe.ManualCurve(source.copy(sourceSize = PixelSize(2, 3)), full, depth = .1), PixelSize(2, 3)) }
        // Qx(t)=-126t+256t² has equal samples at t=31/128 and32/128. Endpoints(0,130), on-curve M=1, derived C=-63 are all independently chosen; no arbitrary raw LUT construction is exposed.
        val narrow = GeometryRecipe.ManualCurve(source.copy(sourceSize = PixelSize(131, 101)), full, PointPx(1.0 / 130, 0.0))
        numericalRefusal { prepare(narrow, PixelSize(131, 101)) }
        numericalRefusal { prepare(narrow.copy(topMidpoint = PointPx(1.000000001 / 130, 0.0)), PixelSize(131, 101)) }
    }

    @Test fun foldIsOnlyAnUnvalidatedCandidateAndItsNegativeJacobianRemainsVisible() {
        val curve = prepare(recipe().copy(topMidpoint = PointPx(.5, .9), bottomMidpoint = PointPx(.5, .1)))
        assertEquals("DIRECT_RULED_CANDIDATE", call(curve, "getDispatch").toString())
        val d = derivative(curve, .5, .5); val du = call(d, "getDu") as PointPx; val dv = call(d, "getDv") as PointPx
        assertTrue(du.x * dv.y - du.y * dv.x < 0.0)
        assertFalse(curve.javaClass.methods.any { it.name in listOf("render", "isValid", "getValidated") })
    }

    @Test fun cancellationAtEntryAndWithinBothLutLoopsPropagatesTheOriginalException() {
        prepare()
        for (at in listOf(1, 8, 150, 300, 450)) {
            var count = 0; val canceled = CancellationException("manual-cancel-$at")
            val caught = assertThrows(CancellationException::class.java) { prepare(checkpoint = { count++; if (count == at) throw canceled }) }
            assertSame(canceled, caught); assertEquals(at, count)
        }
    }

    @Test fun workspaceEstimateAndCheckpointWorkAreConstantWithoutRasterSizedAllocations() {
        val estimate = CoreApi.function("geometry.ManualCurve", "workspaceBytes") as Long
        assertTrue(estimate in 8192L..524288L)
        val r = recipe().copy(source = source.copy(sourceSize = PixelSize(12000, 4000)))
        var small = 0; var large = 0
        prepare(r, PixelSize(120, 40)) { small++ }
        prepare(r, PixelSize(12000, 4000)) { large++ }
        assertEquals(small, large); assertTrue(small in 512..600)
    }
}
