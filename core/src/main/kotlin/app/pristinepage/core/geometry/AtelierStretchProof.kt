package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import kotlin.math.abs
import kotlin.math.sqrt

/** Global outward-rounded certificate for the continuous map defined by the stored coefficients and edge weights. Rounded raster samples still need their own support validation. */
internal object AtelierStretchProof {
    private val zero = Range.point(0.0)
    private val one = Range.point(1.0)
    private val three = Range.point(3.0)

    fun certify(base: Homography, edges: List<ContourEdge>, weights: List<Pair<Double, Double>>) {
        val source = base.sourceSize
        val dx = Range.point((base.outputSize.width - 1).toDouble())
        val dy = Range.point((base.outputSize.height - 1).toDouble())
        val sx = Range.point((source.width - 1).toDouble())
        val sy = Range.point((source.height - 1).toDouble())
        val raw = base.backwardCoefficients().map(Range::point)
        val sign = if (raw[8].lo > 0) one else Range.point(-1.0)
        val a = raw[0] * dx / sx * sign; val b = raw[1] * dy / sx * sign; val c = raw[2] / sx * sign
        val d = raw[3] * dx / sy * sign; val e = raw[4] * dy / sy * sign; val f = raw[5] / sy * sign
        val g = raw[6] * dx * sign; val k = raw[7] * dy * sign; val z = raw[8] * sign
        val corners = listOf(z, z + g, z + g + k, z + k)
        val w = Range(corners.minOf { it.lo }, corners.maxOf { it.hi })
        if (w.lo <= 0.0) invalid("Uncertain cubic base denominator")
        val det = a * (e * z - f * k) - b * (d * z - f * g) + c * (d * k - e * g)
        val w2 = w * w
        val j = det / (w2 * w)
        if (j.lo <= 0.0) invalid("Uncertain cubic base determinant")
        val unit = Range(0.0, 1.0)
        val xu = ((a * k - b * g) * unit + (a * z - c * g)) / w2
        val yu = ((d * k - e * g) * unit + (d * z - f * g)) / w2
        val xv = ((b * g - a * k) * unit + (b * z - c * k)) / w2
        val yv = ((e * g - d * k) * unit + (e * z - f * k)) / w2
        val norm = upperNorm(xu.magnitude, yu.magnitude, xv.magnitude, yv.magnitude)
        val baseSigma = Range.point(j.lo) / Range.point(norm)
        // The global lower chord bound includes the ratio of endpoint denominators. A local Jacobian bound alone would not prove global injectivity.
        val chord = baseSigma * (Range.point(w.lo) / Range.point(w.hi))
        val (top, right, bottom, left) = edges.mapIndexed { index, edge -> bound(edge, weights[index], source) }
        val cu = Range.point(maxOf(top.speed, bottom.speed)) + Range.point(left.amplitude) + Range.point(right.amplitude)
        val cv = Range.point(maxOf(left.speed, right.speed)) + Range.point(top.amplitude) + Range.point(bottom.amplitude)
        val correction = Range.point(upperNorm(cu.hi, cv.hi))
        if (correction.hi >= (chord * Range.point(.5)).lo) invalid("Cubic deformation can approach a fold")
        val sigma = baseSigma - correction
        val maximumNorm = Range.point(norm) + correction
        val minimumJ = Range.point(sigma.lo) * Range.point(sigma.lo)
        // Every Jacobian, including the median, is at most M²/2. This stricter global check avoids treating a sample as a proof.
        val maximumJ = maximumNorm * maximumNorm * Range.point(.5)
        if (sigma.lo < 1e-4 || maximumNorm.hi > 100.0 ||
            minimumJ.lo <= maxOf(1e-10, (maximumJ * Range.point(1e-4)).hi) ||
            (maximumNorm / Range.point(sigma.lo)).hi > 1000.0) invalid("Cubic stretch exceeds the active-source limits")

        // Injectivity and continuity send interior points to interior points. Each coordinate reaches its extrema on the actual four boundaries.
        val cornerX = listOf(c, a + c, a + b + c, b + c).mapIndexed { i, x -> x / corners[i] * sx }
        val cornerY = listOf(f, d + f, d + e + f, e + f).mapIndexed { i, y -> y / corners[i] * sy }
        val endpoints = listOf(0 to 1, 1 to 2, 3 to 2, 0 to 3)
        edges.forEachIndexed { i, edge ->
            val (start, end) = endpoints[i]
            extent(edge, weights[i], corners[start], corners[end], cornerX[start], cornerX[end], sx.lo, PointPx::x)
            extent(edge, weights[i], corners[start], corners[end], cornerY[start], cornerY[end], sy.lo, PointPx::y)
        }
    }

    private fun extent(edge: ContourEdge, weights: Pair<Double, Double>, a: Range, b: Range,
        start: Range, end: Range, limit: Double, axis: (PointPx) -> Double) {
        fun checkExtent(lo: Double, hi: Double) {
            if (lo < -.25 || hi > limit + .25) invalid("Cubic extent exceeds the quarter-pixel visibility limit")
        }
        if (edge.chordOnly) {
            checkExtent(minOf(start.lo, end.lo), maxOf(start.hi, end.hi))
            return
        }
        val residualA = start - Range.point(axis(edge.at(0.0)))
        val residualB = end - Range.point(axis(edge.at(1.0)))
        val storedA = Range.point(abs(weights.first))
        val storedB = Range.point(abs(weights.second))
        // Difference of the two chord fractions: s(1−s)(b·storedA − storedB·a)/(denominator·storedDenominator).
        val minDenominator = Range.point(minOf(a.lo, b.lo)) * Range.point(minOf(storedA.lo, storedB.lo))
        val delta = (Range.point((b * storedA - storedB * a).magnitude) * Range.point(.25) / minDenominator).hi
        val pad = (Range.point(delta) * Range.point((end - start).magnitude)).hi
        val residual = Range(minOf(residualA.lo, residualB.lo), maxOf(residualA.hi, residualB.hi)) + Range(-pad, pad)
        for ((_, _, cubic) in edge.segments) {
            val controls = listOf(cubic.start, cubic.outgoing, cubic.incoming, cubic.end).map(axis)
            val bound = Range(controls.min(), controls.max()) + residual
            checkExtent(bound.lo, bound.hi)
        }
    }

    private data class EdgeBound(val amplitude: Double, val speed: Double)

    private fun bound(edge: ContourEdge, weights: Pair<Double, Double>, source: PixelSize): EdgeBound {
        if (edge.chordOnly) return EdgeBound(0.0, 0.0)
        val start = edge.at(0.0)
        val end = edge.at(1.0)
        val sx = Range.point((source.width - 1).toDouble())
        val sy = Range.point((source.height - 1).toDouble())
        var amp = 0.0
        var velocity = 0.0
        for ((t0, t1, cubic) in edge.segments) {
            val low = Range.point(t0)
            val high = Range.point(t1)
            val width = high - low
            val sites = listOf(low, low + width / three, high - width / three, high)
            val points = listOf(cubic.start, cubic.outgoing, cubic.incoming, cubic.end)
            val controls = points.zip(sites) { p, t ->
                component(p, start, end, t, sx, PointPx::x) to component(p, start, end, t, sy, PointPx::y)
            }
            amp = maxOf(amp, controls.maxOf { (x, y) -> upperNorm(x.magnitude, y.magnitude) })
            val derivative = (1..3).map { i ->
                (controls[i].first - controls[i - 1].first) * three / width to
                    (controls[i].second - controls[i - 1].second) * three / width
            }
            velocity = maxOf(velocity, derivativeBound(derivative[0], derivative[1], derivative[2], 3))
        }
        // The exact evaluator model uses the stored binary64 endpoint weights as constants.
        val a = abs(weights.first)
        val b = abs(weights.second)
        val ratio = Range.point(maxOf(a, b)) / Range.point(minOf(a, b))
        return EdgeBound(amp, (Range.point(velocity) * ratio).hi)
    }

    /** An outward-rounded De Casteljau split tightens the quadratic derivative hull without relaxing the fold limit. */
    private fun derivativeBound(a: Pair<Range, Range>, b: Pair<Range, Range>, c: Pair<Range, Range>, depth: Int): Double {
        if (depth == 0) return listOf(a, b, c).maxOf { (x, y) -> upperNorm(x.magnitude, y.magnitude) }
        val half = Range.point(.5)
        fun middle(p: Pair<Range, Range>, q: Pair<Range, Range>) =
            (p.first + q.first) * half to (p.second + q.second) * half
        val ab = middle(a, b)
        val bc = middle(b, c)
        val center = middle(ab, bc)
        return maxOf(derivativeBound(a, ab, center, depth - 1), derivativeBound(center, bc, c, depth - 1))
    }

    private fun component(p: PointPx, start: PointPx, end: PointPx, t: Range, size: Range, coordinate: (PointPx) -> Double): Range {
        val a = Range.point(coordinate(start))
        val b = Range.point(coordinate(end))
        return (Range.point(coordinate(p)) - (a + (b - a) * t)) / size
    }

    private fun upperNorm(vararg values: Double): Double {
        val squared = values.fold(zero) { sum, value ->
            val range = Range.point(value)
            sum + range * range
        }
        return Math.nextUp(sqrt(squared.hi))
    }

    private fun invalid(message: String): Nothing = throw InvalidInverseMapException(message)

    /** Each endpoint encloses one rounded binary64 operation. No FMA, and no assuming a positive divisor after rounding toward zero. */
    private data class Range(val lo: Double, val hi: Double) {
        init { if (!lo.isFinite() || !hi.isFinite() || lo > hi) invalid("Uncertain cubic interval") }
        val magnitude get() = maxOf(abs(lo), abs(hi))
        operator fun plus(r: Range) = Range(Math.nextDown(lo + r.lo), Math.nextUp(hi + r.hi))
        operator fun minus(r: Range) = Range(Math.nextDown(lo - r.hi), Math.nextUp(hi - r.lo))
        operator fun times(r: Range): Range {
            val a = lo * r.lo; val b = lo * r.hi; val c = hi * r.lo; val d = hi * r.hi
            return Range(Math.nextDown(minOf(a, b, c, d)), Math.nextUp(maxOf(a, b, c, d)))
        }
        operator fun div(r: Range): Range {
            if (r.lo <= 0.0) invalid("Uncertain positive cubic divisor")
            return this * Range(Math.nextDown(1.0 / r.hi), Math.nextUp(1.0 / r.lo))
        }
        companion object { fun point(x: Double) = Range(x, x) }
    }
}
