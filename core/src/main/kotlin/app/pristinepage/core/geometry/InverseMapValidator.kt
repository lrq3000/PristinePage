package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Exhausted validation work is a resource refusal, not proof of an invalid map and never permission to skip the remaining checks. */
class GeometryWorkLimitException(message: String) : RuntimeException(message)
class InvalidInverseMapException(message: String) : IllegalArgumentException(message)

/** Proof bounds and operational support diagnostics for geometry_visibility_v1. They describe only the admitted perspective map, not native pixels, real-page fidelity or a physical-device measurement. */
class PerspectiveValidation internal constructor(val referenceJacobian: Double, val minimumJacobianBound: Double, val maximumFrobeniusBound: Double, val minimumSupport: Double, val meanSupport: Double, val boxes: Int)

/** Pole-free projective injectivity is analytic; a bilinear-cell approximation is neither necessary nor sufficient for the actual rational map. All derivatives are with respect to unit destination and normalized active-source axes. */
object InverseMapValidator {
    private const val SIDE = 129
    private const val MAX_BOXES = 100_000
    private const val MAX_DEPTH = 20

    /** Includes the16641-entry Double Jacobian lattice, bounded DFS stack and conservative scalar/interval/object scratch. Caller must reserve this in addition to live source/output owners before invoking validation; this is not measured PSS. */
    @JvmStatic fun perspectiveWorkspaceBytes(): Long = 512L * 1024

    @JvmStatic fun perspective(map: Homography, checkpoint: () -> Unit): PerspectiveValidation {
        checkpoint()
        val raw = map.backwardCoefficients()
        val dx = (map.outputSize.width - 1).toDouble(); val dy = (map.outputSize.height - 1).toDouble()
        val sx = (map.sourceSize.width - 1).toDouble(); val sy = (map.sourceSize.height - 1).toDouble()
        val unit = doubleArrayOf(raw[0] * dx / sx, raw[1] * dy / sx, raw[2] / sx, raw[3] * dx / sy, raw[4] * dy / sy, raw[5] / sy, raw[6] * dx, raw[7] * dy, raw[8])
        val r = Array(9) { Range.point(raw[it]) }
        val bound = arrayOf(r[0] * Range.point(dx) / Range.point(sx), r[1] * Range.point(dy) / Range.point(sx), r[2] / Range.point(sx), r[3] * Range.point(dx) / Range.point(sy), r[4] * Range.point(dy) / Range.point(sy), r[5] / Range.point(sy), r[6] * Range.point(dx), r[7] * Range.point(dy), r[8])
        // Visibility samples the original pixel-center evaluator. Proof arithmetic encloses the exact raw→unit transformation above, rather than accepting normalization rounding as a different map.
        return prove(unit, bound, map.sourceSize, MAX_BOXES, checkpoint) { u, v -> map.map(u * dx, v * dy) }
    }

    private fun validateUnit(coefficients: DoubleArray, source: PixelSize, maxBoxes: Int, checkpoint: () -> Unit): PerspectiveValidation = prove(coefficients, Array(coefficients.size) { Range.point(coefficients[it]) }, source, maxBoxes, checkpoint, null)

    private fun prove(coefficients: DoubleArray, coefficientBounds: Array<Range>, source: PixelSize, maxBoxes: Int, checkpoint: () -> Unit, sample: ((Double, Double) -> PointPx)?): PerspectiveValidation {
        checkpoint()
        require(source.width >= 2 && source.height >= 2 && maxBoxes in 1..MAX_BOXES)
        require(coefficients.size == 9 && coefficients.all { it.isFinite() }) { "Finite3x3 perspective coefficients required" }
        val scale = coefficients.maxOf { abs(it) }
        if (scale == 0.0) invalid("Zero perspective scale")
        val m = DoubleArray(9) { coefficients[it] / scale }
        val intervals = Array(9) { coefficientBounds[it] / Range.point(scale) }
        val cornerZ = doubleArrayOf(m[8], m[6] + m[8], m[6] + m[7] + m[8], m[7] + m[8])
        val largest = cornerZ.maxOf { abs(it) }
        if (largest == 0.0 || !(cornerZ.all { it > 1e-10 * largest } || cornerZ.all { it < -1e-10 * largest })) invalid("Perspective denominator touches or crosses a pole")
        if (cornerZ[0] < 0.0) for (i in m.indices) { m[i] = -m[i]; intervals[i] = -intervals[i] }
        val determinant = determinant(m)
        if (!determinant.isFinite() || determinant <= 0.0) invalid("Perspective must preserve positive orientation")
        val determinantBound = determinant(intervals)
        if (determinantBound.lo <= 0.0) invalid("Uncertain perspective determinant")

        // The true coordinate extrema are at corners of a pole-free projective rectangle. Outward arithmetic prevents an uncertain extent from being accepted by rounding inward.
        for (v in doubleArrayOf(0.0, 1.0)) for (u in doubleArrayOf(0.0, 1.0)) {
            val z = affine(intervals[6], intervals[7], intervals[8], Range.point(u), Range.point(v))
            if (z.lo <= Math.nextUp(1e-10 * largest)) invalid("Uncertain perspective denominator margin")
            val x = affine(intervals[0], intervals[1], intervals[2], Range.point(u), Range.point(v)) / z * Range.point((source.width - 1).toDouble())
            val y = affine(intervals[3], intervals[4], intervals[5], Range.point(u), Range.point(v)) / z * Range.point((source.height - 1).toDouble())
            if (x.lo < -.25 || x.hi > source.width - 1.0 + .25 || y.lo < -.25 || y.hi > source.height - 1.0 + .25) invalid("Perspective extent exceeds quarter-pixel visibility limit")
        }

        val jacobians = DoubleArray(SIDE * SIDE)
        var minimumSupport = 1.0
        var supportSum = 0.0
        for (y in 0 until SIDE) {
            checkpoint()
            val v = y.toDouble() / (SIDE - 1)
            for (x in 0 until SIDE) {
                val u = x.toDouble() / (SIDE - 1)
                val z = m[6] * u + m[7] * v + m[8]
                val jacobian = determinant / (z * z * z)
                if (!jacobian.isFinite() || jacobian <= 0.0) invalid("Nonpositive perspective lattice Jacobian")
                jacobians[y * SIDE + x] = jacobian
                val actual = sample?.invoke(u, v)
                val sx = actual?.x ?: ((m[0] * u + m[1] * v + m[2]) / z * (source.width - 1))
                val sy = actual?.y ?: ((m[3] * u + m[4] * v + m[5]) / z * (source.height - 1))
                val support = BilinearSampler.support(source, sx, sy)
                minimumSupport = minOf(minimumSupport, support)
                supportSum += support
            }
        }
        val meanSupport = supportSum / jacobians.size
        if (minimumSupport < .75 || meanSupport < .995) invalid("Perspective source support is below visibility policy")
        jacobians.sort()
        val reference = jacobians[jacobians.size / 2]
        val floor = max(1e-10, 1e-4 * reference)
        if (jacobians[0] <= floor) invalid("Perspective lattice Jacobian collapses")

        val stack = ArrayDeque<Box>()
        stack.addLast(Box(0.0, 1.0, 0.0, 1.0, 0))
        var requested = 1
        var processed = 0
        var minimumBound = Double.POSITIVE_INFINITY
        var maximumNorm = 0.0
        while (stack.isNotEmpty()) {
            checkpoint()
            val box = stack.removeLast()
            processed++
            val bound = bounds(intervals, determinantBound, box)
            val j = bound.first
            val norm = bound.second
            val safe = j > floor && norm <= 100.0 && Math.nextDown(j / norm) >= 1e-4 && Math.nextUp(Math.nextUp(norm * norm) / j) <= 1000.0
            if (safe) {
                minimumBound = minOf(minimumBound, j)
                maximumNorm = maxOf(maximumNorm, norm)
            } else {
                if (box.depth == MAX_DEPTH) invalid("Perspective bounds remain unresolved at depth20")
                // Count pending children BEFORE allocating/enqueuing them. No full subdivision tree or silent work-cap bypass is allowed.
                if (requested > maxBoxes - 4) throw GeometryWorkLimitException("Perspective subdivision exceeds its work budget")
                requested += 4
                val midX = (box.x0 + box.x1) / 2
                val midY = (box.y0 + box.y1) / 2
                val depth = box.depth + 1
                stack.addLast(Box(box.x0, midX, box.y0, midY, depth))
                stack.addLast(Box(midX, box.x1, box.y0, midY, depth))
                stack.addLast(Box(box.x0, midX, midY, box.y1, depth))
                stack.addLast(Box(midX, box.x1, midY, box.y1, depth))
            }
        }
        checkpoint()
        return PerspectiveValidation(reference, minimumBound, maximumNorm, minimumSupport, meanSupport, processed)
    }

    private fun bounds(m: Array<Range>, det: Range, box: Box): Pair<Double, Double> {
        val u = Range(box.x0, box.x1); val v = Range(box.y0, box.y1)
        val z = affine(m[6], m[7], m[8], u, v)
        if (z.lo <= 0.0) return 0.0 to Double.POSITIVE_INFINITY
        val squared = z * z
        val jacobian = det / (squared * z)
        // Algebraically cancel the shared u or v terms before bounding derivative numerators. This is the analytic rational derivative, not a numerical finite difference or four-corner interpolation.
        val xu = ((m[0] * m[7] - m[1] * m[6]) * v + (m[0] * m[8] - m[2] * m[6])) / squared
        val yu = ((m[3] * m[7] - m[4] * m[6]) * v + (m[3] * m[8] - m[5] * m[6])) / squared
        val xv = ((m[1] * m[6] - m[0] * m[7]) * u + (m[1] * m[8] - m[2] * m[7])) / squared
        val yv = ((m[4] * m[6] - m[3] * m[7]) * u + (m[4] * m[8] - m[5] * m[7])) / squared
        var squaredNorm = 0.0
        for (component in arrayOf(xu, yu, xv, yv)) {
            val magnitude = max(abs(component.lo), abs(component.hi))
            squaredNorm = Math.nextUp(squaredNorm + Math.nextUp(magnitude * magnitude))
        }
        return jacobian.lo to Math.nextUp(sqrt(squaredNorm))
    }

    private fun affine(a: Range, b: Range, c: Range, x: Range, y: Range): Range = a * x + b * y + c
    private fun determinant(m: DoubleArray): Double = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
    private fun determinant(m: Array<Range>): Range = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
    private fun invalid(message: String): Nothing = throw InvalidInverseMapException(message)
    private class Box(val x0: Double, val x1: Double, val y0: Double, val y1: Double, val depth: Int)

    /** Directed one-ulp expansion after every elementary binary64 operation; no FMA/reassociation or accepting a center value in place of a bound. Bounds may conservatively refuse an uncertain near-threshold map. */
    private class Range(val lo: Double, val hi: Double) {
        operator fun unaryMinus() = Range(-hi, -lo)
        operator fun plus(other: Range) = Range(Math.nextDown(lo + other.lo), Math.nextUp(hi + other.hi))
        operator fun minus(other: Range) = Range(Math.nextDown(lo - other.hi), Math.nextUp(hi - other.lo))
        operator fun times(other: Range): Range {
            val a = lo * other.lo; val b = lo * other.hi; val c = hi * other.lo; val d = hi * other.hi
            return Range(Math.nextDown(minOf(a, b, c, d)), Math.nextUp(maxOf(a, b, c, d)))
        }
        operator fun div(other: Range): Range {
            check(other.lo > 0.0)
            return this * Range(Math.nextDown(1.0 / other.hi), Math.nextUp(1.0 / other.lo))
        }
        companion object { fun point(value: Double) = Range(value, value) }
    }
}
