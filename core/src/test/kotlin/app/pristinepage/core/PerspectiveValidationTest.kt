package app.pristinepage.core

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Analytical normalized maps are independent fixtures; no image allocation, raster screenshot or sampled-grid topology is used as the homography proof. */
class PerspectiveValidationTest {
    private fun validate(matrix: DoubleArray, size: PixelSize = PixelSize(101, 101), boxes: Int = 100_000, checkpoint: () -> Unit = {}): Any {
        val type = CoreApi.type("geometry.InverseMapValidator")
        val method = type.declaredMethods.single { it.name == "validateUnit" }
        method.isAccessible = true
        return try { checkNotNull(method.invoke(type.getField("INSTANCE").get(null), matrix, size, boxes, checkpoint)) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun number(value: Any, property: String) = value.javaClass.getMethod("get$property").invoke(value) as Double
    private fun count(value: Any) = value.javaClass.getMethod("getBoxes").invoke(value) as Int
    private fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private fun reject(matrix: DoubleArray) {
        CoreApi.type("geometry.InverseMapValidator")
        assertThrows(IllegalArgumentException::class.java) { validate(matrix) }
    }

    @Test fun identityHasUnitSupportAndJacobianWithoutFullResolutionPlanes() {
        val report = validate(identity())
        assertEquals(1.0, number(report, "ReferenceJacobian"), 0.0)
        assertEquals(1.0, number(report, "MinimumSupport"), 0.0)
        assertEquals(1.0, number(report, "MeanSupport"), 0.0)
        assertTrue(number(report, "MinimumJacobianBound") > .999999999)
        assertTrue(number(report, "MaximumFrobeniusBound") >= kotlin.math.sqrt(2.0))
        assertEquals(1, count(report))
    }

    @Test fun convexPoleFreePerspectiveIsValidatedAnalyticallyNotAsBilinearCells() {
        // Unit form of the published G over11x11/source160x120. Its center is(73.91304347826087,50), not the four-corner bilinear center.
        val matrix = doubleArrayOf(120.0 / 159, 10.0 / 159, 20.0 / 159, 5.0 / 119, 90.0 / 119, 10.0 / 119, .1, .2, 1.0)
        val report = validate(matrix, PixelSize(160, 120))
        // The independent four-weight binary64 sum is0.9999999999999999 at lattice(71,0), despite every neighbor being in bounds. This is not a missing-source pixel or permission to change sampler arithmetic.
        assertEquals(1.0, number(report, "MinimumSupport"), 2e-15)
        assertTrue(number(report, "ReferenceJacobian") in .3..1.0)
        assertTrue(number(report, "MinimumJacobianBound") > 0.0)
        assertTrue(count(report) >= 1)
    }

    @Test fun actualSolvedMapUsesItsBaseDestinationAndActiveSourceUnits() {
        val q = QuadPx(PointPx(10.0, 20.0), PointPx(110.0, 20.0), PointPx(110.0, 70.0), PointPx(10.0, 70.0))
        val map = CoreApi.function("geometry.Homography", "solve", q, PixelSize(201, 101), PixelSize(51, 26))!!
        val report = CoreApi.function("geometry.InverseMapValidator", "perspective", map, {})!!
        // d(xs/200)/du=.5; d(ys/100)/dv=.5, hence J=.25. Pixel-output aspect/translation must not change normalized conditioning/stretch.
        assertEquals(.25, number(report, "ReferenceJacobian"), 1e-12)
        assertEquals(1.0, number(report, "MeanSupport"), 0.0)
    }

    @Test fun denominatorPoleAndRelativeCornerMarginRejectBeforeSampling() {
        reject(identity().also { it[6] = -2.0 })
        reject(identity().also { it[6] = -1.0 })
        reject(identity().also { it[6] = -1.0 + 5e-11 })
    }

    @Test fun reflectionCollapseAndNonfiniteFieldsAreNotRepaired() {
        reject(identity().also { it[0] = -1.0; it[2] = 1.0 })
        reject(identity().also { it[0] = 0.0 })
        reject(identity().also { it[0] = Double.NaN })
        reject(identity().also { it[8] = Double.POSITIVE_INFINITY })
    }

    @Test fun absoluteJacobianFloorRejectsThinMapsDespitePositiveOrientation() {
        reject(doubleArrayOf(5e-11, 0.0, .5, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    }

    @Test fun affineStretchFloorAndAnisotropyLimitsAreAppliedInNormalizedSourceUnits() {
        validate(doubleArrayOf(.0021, 0.0, .1, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        reject(doubleArrayOf(.0009, 0.0, .1, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        reject(doubleArrayOf(.00005, 0.0, .1, 0.0, .00005, .1, 0.0, 0.0, 1.0))
    }

    @Test fun quarterPixelExtentAndActualBilinearSupportHaveSeparateGates() {
        val allowed = identity().also { it[0] = .5; it[4] = .5; it[2] = -.0024; it[5] = .25 }
        val report = validate(allowed)
        assertEquals(.76, number(report, "MinimumSupport"), 1e-12)
        assertTrue(number(report, "MeanSupport") >= .995)
        reject(allowed.copyOf().also { it[2] = -.00251 })
        // Both axes outside by.24px meet extent individually, but corner support=.76²=.5776 fails minV>=.75.
        reject(allowed.copyOf().also { it[5] = -.0024 })
    }

    @Test fun meanSupportRejectsALongNearlyOutsideStripEvenWhenMinimumPasses() {
        CoreApi.type("geometry.InverseMapValidator")
        val failure = assertThrows(IllegalArgumentException::class.java) { validate(doubleArrayOf(0.00001, 0.0, -.0024, 0.0, .5, .25, 0.0, 0.0, 1.0)) }
        assertTrue(failure.message.orEmpty().contains("support"))
    }

    @Test fun adaptiveProofCanSubdivideConservativeRootBoundsInsteadOfPassingTheirCenters() {
        // x=u/(1+4u), y=v/(1+4u). Root interval's mJ/M is deliberately loose, while every actual derivative meets the policy. Subdivision must establish safe bounds.
        val report = validate(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 4.0, 0.0, 1.0))
        assertTrue(count(report) > 1)
        assertTrue(number(report, "MinimumJacobianBound") > 0.0)
    }

    @Test fun pendingSubdivisionWorkIsLimitedBeforeEnqueueAndIsNotAnInvalidMap() {
        CoreApi.type("geometry.InverseMapValidator")
        val failure = assertThrows(RuntimeException::class.java) { validate(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 4.0, 0.0, 1.0), boxes = 1) }
        assertEquals("app.pristinepage.core.geometry.GeometryWorkLimitException", failure.javaClass.name)
    }

    @Test fun cancellationDuringLatticeAndSubdivisionPropagatesWithoutAReport() {
        CoreApi.type("geometry.InverseMapValidator")
        var calls = 0
        assertThrows(CancellationException::class.java) { validate(identity(), checkpoint = { if (++calls == 4) throw CancellationException("lattice") }) }
        calls = 0
        assertThrows(CancellationException::class.java) { validate(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 4.0, 0.0, 1.0), checkpoint = { if (++calls == 132) throw CancellationException("subdivision") }) }
    }
}
