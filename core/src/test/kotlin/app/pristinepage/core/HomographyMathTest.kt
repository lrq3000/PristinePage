package app.pristinepage.core

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.geometry.QuadValidation
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** Source-center/destination-center oracles independent of the numerical backend. These tests do not establish stretch, visibility or image/mask renderer acceptance. */
class HomographyMathTest {
    private fun quad(vararg p: Double) = QuadPx(PointPx(p[0], p[1]), PointPx(p[2], p[3]), PointPx(p[4], p[5]), PointPx(p[6], p[7]))
    private fun solve(q: QuadPx, source: PixelSize, output: PixelSize): Any = checkNotNull(CoreApi.function("geometry.Homography", "solve", q, source, output))
    private fun map(solution: Any, x: Double, y: Double): PointPx = try {
        solution.javaClass.getMethod("map", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType).invoke(solution, x, y) as PointPx
    } catch (error: InvocationTargetException) { throw error.targetException }
    private fun point(expectedX: Double, expectedY: Double, actual: PointPx) {
        assertEquals(expectedX, actual.x, 1e-6)
        assertEquals(expectedY, actual.y, 1e-6)
    }
    private fun numericalGate(name: String, vararg args: Any?) {
        val companion = CoreApi.type("geometry.Homography").getField("Companion").get(null)
        val method = companion.javaClass.declaredMethods.singleOrNull { it.name == name && it.parameterCount == args.size }
        assertNotNull("Targeted homography inverse-safety seam is absent", method)
        method!!.isAccessible = true
        try { method.invoke(companion, *args) } catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    @Test fun selectedBackendSuppliesTheNinthRightNullVectorOfAWideEightByNineMatrix() {
        val matrixType = try { Class.forName("org.ejml.data.DMatrixRMaj") }
        catch (_: ClassNotFoundException) { fail("Approved full-null-vector SVD backend is absent"); return }
        val a = matrixType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(8, 9)
        val set = matrixType.getMethod("set", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        for (i in 0..7) {
            set.invoke(a, i, i, 1.0)
            set.invoke(a, i, 8, -(i + 1).toDouble())
        }
        val factory = Class.forName("org.ejml.dense.row.factory.DecompositionFactory_DDRM")
        val svd = factory.getMethod("svd", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(null, 8, 9, false, true, false)
        assertEquals(true, svd.javaClass.getMethod("decompose", matrixType).invoke(svd, a))
        val v = svd.javaClass.getMethod("getV", matrixType, Boolean::class.javaPrimitiveType).invoke(svd, null, false)
        assertEquals(9, matrixType.getField("numRows").getInt(v))
        assertEquals(9, matrixType.getField("numCols").getInt(v))
        val get = matrixType.getMethod("get", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val last = DoubleArray(9) { i -> get.invoke(v, i, 8) as Double }
        val sign = if (last[8] >= 0.0) 1.0 else -1.0
        // A=[I8 | -(1,...,8)], so its unit right null vector is ±(1,...,8,1)/sqrt(205).
        for (i in 0..7) {
            assertEquals(sign * (i + 1) / kotlin.math.sqrt(205.0), last[i], 1e-12)
            assertEquals(0.0, last[i] - (i + 1) * last[8], 1e-12)
        }
        assertEquals(sign / kotlin.math.sqrt(205.0), last[8], 1e-12)
    }

    @Test fun projectiveOracleRetainsTheDenominatorRatherThanBilinearCornerInterpolation() {
        val q = quad(20.0, 10.0, 127.27272727272727, 13.636363636363635, 115.38461538461539, 80.76923076923076, 25.0, 83.33333333333334)
        val h = solve(q, PixelSize(160, 120), PixelSize(11, 11))
        point(20.0, 10.0, map(h, 0.0, 0.0))
        point(127.27272727272727, 13.636363636363635, map(h, 10.0, 0.0))
        point(115.38461538461539, 80.76923076923076, map(h, 10.0, 10.0))
        point(25.0, 83.33333333333334, map(h, 0.0, 10.0))
        val center = map(h, 5.0, 5.0)
        point(73.91304347826087, 50.0, center)
        assertTrue(kotlin.math.abs(center.x - 71.91433566433567) > 1.0)
        assertTrue(kotlin.math.abs(center.y - 46.934731934731936) > 1.0)
    }

    @Test fun exactSourceSizedRectangleMapsEveryCenterIncludingTheLastOne() {
        val h = solve(quad(0.0, 0.0, 2.0, 0.0, 2.0, 1.0, 0.0, 1.0), PixelSize(3, 2), PixelSize(3, 2))
        for (y in 0..1) for (x in 0..2) assertEquals(PointPx(x.toDouble(), y.toDouble()), map(h, x.toDouble(), y.toDouble()))
    }

    @Test fun translatedAffineCropDoesNotLoseItsSourceOrigin() {
        val h = solve(quad(10.0, 20.0, 14.0, 20.0, 14.0, 22.0, 10.0, 22.0), PixelSize(30, 40), PixelSize(5, 3))
        point(10.0, 20.0, map(h, 0.0, 0.0))
        point(12.0, 21.0, map(h, 2.0, 1.0))
        point(14.0, 22.0, map(h, 4.0, 2.0))
    }

    @Test fun normalizationMakesHarmlessLargeTranslationAndUnitsAdmissible() {
        val h = solve(quad(10000.0, 9000.0, 11000.0, 9000.0, 11000.0, 10000.0, 10000.0, 10000.0), PixelSize(12000, 12000), PixelSize(101, 101))
        point(10500.0, 9500.0, map(h, 50.0, 50.0))
        point(11000.0, 10000.0, map(h, 100.0, 100.0))
    }

    @Test fun outputDimensionsChangeDestinationCentersNotSourceCorners() {
        val q = quad(0.0, 0.0, 400.0, 0.0, 400.0, 300.0, 0.0, 300.0)
        val h = solve(q, PixelSize(401, 301), PixelSize(5, 4))
        point(0.0, 0.0, map(h, 0.0, 0.0))
        point(200.0, 100.0, map(h, 2.0, 1.0))
        point(400.0, 300.0, map(h, 4.0, 3.0))
    }

    @Test fun invalidLabeledQuadsFailBeforeAnyPlausibleSolve() {
        val invalid = listOf(
            quad(0.0, 0.0, 4.0, 4.0, 4.0, 0.0, 0.0, 4.0),
            quad(0.0, 0.0, 0.0, 4.0, 4.0, 4.0, 4.0, 0.0),
            quad(0.0, 0.0, 4.0, 0.0, 4.0, 0.0, 0.0, 4.0),
            quad(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0),
            quad(-0.1, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0),
        )
        for (q in invalid) assertThrows(IllegalArgumentException::class.java) { solve(q, PixelSize(5, 5), PixelSize(5, 5)) }
    }

    @Test fun singletonGeometryRejectsInsteadOfDividingByZero() {
        val q = quad(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0)
        for (size in listOf(PixelSize(1, 5), PixelSize(5, 1), PixelSize(1, 1))) {
            assertThrows(IllegalArgumentException::class.java) { solve(q, PixelSize(5, 5), size) }
            assertThrows(IllegalArgumentException::class.java) { solve(q, size, PixelSize(5, 5)) }
        }
    }

    @Test fun mappingRejectsNonfiniteAndOutsideDestinationCenters() {
        val h = solve(quad(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0), PixelSize(5, 5), PixelSize(5, 5))
        for ((x, y) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, -0.1 to 0.0, 0.0 to 4.1)) {
            assertThrows(IllegalArgumentException::class.java) { map(h, x, y) }
        }
    }

    @Test fun validConvexIllConditionedCorrespondencesReachNumericalRejectionAfterQuadAdmission() {
        // Independent parallelogram: long direction(1e9,1e9), short direction(1,1.01). Every turn is about1e7 (>8e6 predicate uncertainty), edges>=1 and area about1e7, yet its normalized aspect is about2e11. No image of these metadata dimensions is allocated.
        val source = PixelSize(2_000_000_001, 2_000_000_001)
        val q = quad(100.0, 100.0, 1_000_000_100.0, 1_000_000_100.0, 1_000_000_101.0, 1_000_000_101.01, 101.0, 101.01)
        QuadValidation.validate(q, source)
        val failure = assertThrows(IllegalArgumentException::class.java) { solve(q, source, PixelSize(11, 11)) }
        assertEquals("app.pristinepage.core.geometry.InvalidHomographyException", failure.javaClass.name)
        assertTrue(failure.message.orEmpty().contains("condition"))
    }

    @Test fun rankAndConditionGateUsesFinitePositiveSingularValuesAndInclusiveLimit() {
        numericalGate("condition", DoubleArray(8) { if (it == 7) 1e-10 else 1.0 }, 8, 8)
        // Independent binary64 division: one nextDown still rounds1/min to exactly1e10; two nextDowns give10000000000.000002 and reach the strict-greater rejection branch.
        numericalGate("condition", DoubleArray(8) { if (it == 7) Math.nextDown(1e-10) else 1.0 }, 8, 8)
        for (minimum in listOf(Math.nextDown(Math.nextDown(1e-10)), 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val failure = assertThrows(IllegalArgumentException::class.java) { numericalGate("condition", DoubleArray(8) { if (it == 7) minimum else 1.0 }, 8, 8) }
            assertEquals("app.pristinepage.core.geometry.InvalidHomographyException", failure.javaClass.name)
        }
    }

    @Test fun inversePoleMarginGateRejectsCrossingAndNearZeroButAcceptsCommonScaleSigns() {
        // Check availability outside the refusal assertion: an absent seam is not a successful rejection witness.
        numericalGate("validateInverse", doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0), PixelSize(11, 11))
        for (denominatorX in listOf(-.2, -.1, (5e-11 - 1.0) / 10)) {
            val failure = assertThrows(IllegalArgumentException::class.java) { numericalGate("validateInverse", doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,denominatorX,0.0,1.0), PixelSize(11, 11)) }
            assertEquals("app.pristinepage.core.geometry.InvalidHomographyException", failure.javaClass.name)
            assertTrue(failure.message.orEmpty().contains("pole"))
        }
        numericalGate("validateInverse", doubleArrayOf(-1.0,0.0,0.0,0.0,-1.0,0.0,0.0,0.0,-1.0), PixelSize(11, 11))
    }

    @Test fun inverseReflectionIsRejectedRatherThanRepairingItsDeterminantSign() {
        numericalGate("validateInverse", doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0), PixelSize(11, 11))
        val failure = assertThrows(IllegalArgumentException::class.java) { numericalGate("validateInverse", doubleArrayOf(-1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0), PixelSize(11, 11)) }
        assertEquals("app.pristinepage.core.geometry.InvalidHomographyException", failure.javaClass.name)
        assertTrue(failure.message.orEmpty().contains("orientation"))
    }
}
