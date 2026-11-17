package app.pristinepage.core.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** One operation's cumulative allowance, shared with later boundary/overlap stages. Single-owner (not thread safe). Schedule BEFORE allocating/enqueuing work; processed counts consumed boxes, including subsequently inconclusive ones. No refund/reset API exists. */
class GeometryProofBudget(val maximumBoxes: Int = 100000) {
    init { require(maximumBoxes in 1..100000) }
    private var scheduled = 0
    private var completed = 0
    class Counts internal constructor(val scheduledBoxes: Int, val processedBoxes: Int)
    fun snapshot(): Counts = Counts(scheduled, completed)
    fun schedule(boxes: Int) {
        require(boxes > 0)
        if (boxes > maximumBoxes - scheduled) throw GeometryWorkLimitException("Geometry proof scheduled-box allowance exhausted")
        scheduled += boxes
    }
    fun processed() {
        check(completed < scheduled) { "Cannot process unscheduled geometry work" }
        completed++
    }
}

/** LOCAL source-normalized derivative/extent certificate and actual fixed-lattice support diagnostics, NOT an admitted map. Construction is private; the only factory consumes the exact typed immutable prepared candidate. */
class ManualStretchValidation private constructor(
    private val candidate: ManualCurve,
    val referenceJacobian: Double,
    val minimumJacobianBound: Double,
    val maximumFrobeniusBound: Double,
    val minimumSupport: Double,
    val meanSupport: Double,
    val minimumXBound: Double,
    val minimumYBound: Double,
    val maximumXBound: Double,
    val maximumYBound: Double,
    val jointIntervals: Int,
    val scheduledBoxes: Int,
    val processedBoxes: Int,
    val maximumDepth: Int,
) {
    val globalBoundarySimplicityProven: Boolean get() = false
    val injectivityProven: Boolean get() = false
    val sampledGridOverlapChecked: Boolean get() = false
    fun requireCandidate(actual: ManualCurve) { require(actual === candidate) { "Local certificate belongs to a different prepared candidate" } }

    companion object {
        internal fun analyze(candidate: ManualCurve, budget: GeometryProofBudget, checkpoint: () -> Unit): ManualStretchValidation = Proof(candidate, budget, checkpoint).run()

        private class Proof(val candidate: ManualCurve, val budget: GeometryProofBudget, val checkpoint: () -> Unit) {
            private val sx = (candidate.source.sourceSize.width - 1).toDouble()
            private val sy = (candidate.source.sourceSize.height - 1).toDouble()
            fun run(): ManualStretchValidation {
                checkpoint()
                require(candidate.dispatch == ManualCurve.Dispatch.DIRECT_RULED_CANDIDATE) { "Straight manual candidate requires the separate existing perspective proof dispatcher" }
                val top = candidate.topBoundary!!; val bottom = candidate.bottomBoundary!!
                // Never consume Boundary.bounds: those independently rounded scalar extrema are not a certificate. Stored controls are exact binary64 inputs here, including an outside derived control.
                val points = arrayOf(top.start, top.control, top.end, bottom.start, bottom.control, bottom.end)
                // The final ruling blends BOTH edges: use their common maximum scale for its rounding allowance, even when the minimum comes from a small-magnitude coordinate on just one edge.
                val x = extent(top.start.x, top.control.x, top.end.x).hull(extent(bottom.start.x, bottom.control.x, bottom.end.x)).expand(Math.nextUp(128 * Math.ulp(max(1.0, points.maxOf { abs(it.x) }))))
                val y = extent(top.start.y, top.control.y, top.end.y).hull(extent(bottom.start.y, bottom.control.y, bottom.end.y)).expand(Math.nextUp(128 * Math.ulp(max(1.0, points.maxOf { abs(it.y) }))))
                if (x.lo < -.25 || x.hi > sx + .25 || y.lo < -.25 || y.hi > sy + .25) invalid("Manual true boundary extent exceeds quarter-pixel visibility limit")
                val jacobians = DoubleArray(129 * 129)
                var minimumSupport = 1.0; var supportSum = 0.0
                for (row in 0..128) {
                    checkpoint()
                    val v = row / 128.0
                    for (column in 0..128) {
                        val u = column / 128.0
                        val d = candidate.derivativesBaseUnit(u, v, ManualCurve.KnotSide.RIGHT)
                        val ux = d.du.x / sx; val uy = d.du.y / sy; val vx = d.dv.x / sx; val vy = d.dv.y / sy
                        val j = ux * vy - uy * vx
                        if (!j.isFinite() || j <= 0.0) invalid("Nonpositive manual lattice Jacobian")
                        jacobians[row * 129 + column] = j
                        val norm = sqrt(ux * ux + uy * uy + vx * vx + vy * vy)
                        // A demonstrated point failure can reject early; only outward full-interval bounds below may certify success.
                        if (!norm.isFinite() || norm > 100.0 || j / norm < 1e-4 || norm * norm / j > 1000.0) invalid("Manual sampled stretch fails policy")
                        val actual = candidate.mapBaseUnit(u, v)
                        val support = BilinearSampler.support(candidate.source.sourceSize, actual.x, actual.y)
                        minimumSupport = minOf(minimumSupport, support); supportSum += support
                    }
                }
                val meanSupport = supportSum / jacobians.size
                if (minimumSupport < .75 || meanSupport < .995) invalid("Manual actual source support fails visibility policy")
                jacobians.sort()
                val reference = jacobians[jacobians.size / 2]
                val floor = max(1e-10, Math.nextUp(1e-4 * reference))
                if (jacobians[0] <= floor) invalid("Manual lattice Jacobian collapses")
                val breaks = DoubleArray(258)
                for (k in 0..128) { breaks[k] = top.arcKnot(k); breaks[k + 129] = bottom.arcKnot(k) }
                breaks.sort()
                var size = 1
                for (k in 1 until breaks.size) if (breaks[k] != breaks[size - 1]) breaks[size++] = breaks[k]
                val intervals = size - 1
                // All joint roots are charged together BEFORE constructing any root/stack. Later stages receive this same, already consumed budget, never a fresh per-stage allowance.
                budget.schedule(intervals)
                val stack = arrayOfNulls<Box>(21)
                var minimumJ = Double.POSITIVE_INFINITY; var maximumM = 0.0; var maximumDepth = 0
                for (k in 0 until intervals) {
                    var pending = 1
                    stack[0] = Box(breaks[k], breaks[k + 1], 0)
                    while (pending > 0) {
                        checkpoint()
                        val box = stack[--pending]!!; stack[pending] = null
                        budget.processed(); maximumDepth = max(maximumDepth, box.depth)
                        val t = Segment(top, box.a, box.b, sx, sy)
                        val b = Segment(bottom, box.a, box.b, sx, sy)
                        val delta = Array(3) { b.position[it] - t.position[it] }
                        val ca = crossPolynomial(t.derivative, delta)
                        val cb = crossPolynomial(b.derivative, delta)
                        val cubic = minOf(cubicMinimum(ca), cubicMinimum(cb))
                        // A second, operation-by-operation enclosure protects the ACTUAL stored-LUT binary64 evaluator, not just an independently rounded polynomial surrogate. Its conservative lower bound can only tighten/refuse the ideal cubic certificate.
                        val actual = actualBounds(t, b, candidate.depth)
                        val idealJ = if (cubic.isFinite()) ((R.p(1.0) - R.p(abs(candidate.depth))) * R.p(cubic)).lo else Double.NEGATIVE_INFINITY
                        val j = minOf(idealJ, actual.first)
                        val m = actual.second
                        if (safe(j, m, floor)) {
                            minimumJ = minOf(minimumJ, j); maximumM = max(maximumM, m)
                        } else {
                            if (box.depth == 20) invalid("Manual stretch bounds unresolved at depth20")
                            val mid = box.a + (box.b - box.a) * .5
                            if (mid <= box.a || mid >= box.b) invalid("Manual stretch subdivision has uncertain binary64 width")
                            budget.schedule(2)
                            stack[pending++] = Box(mid, box.b, box.depth + 1)
                            stack[pending++] = Box(box.a, mid, box.depth + 1)
                        }
                    }
                }
                checkpoint()
                val counts = budget.snapshot()
                return ManualStretchValidation(candidate, reference, minimumJ, maximumM, minimumSupport, meanSupport, x.lo, y.lo, x.hi, y.hi, intervals, counts.scheduledBoxes, counts.processedBoxes, maximumDepth)
            }
        }
    }
}

/** No source pixels, output raster, dense inverse map, clamping, re-fit or re-preparation. The consumer remains responsible for actual decoded-source binding and every missing global gate. */
object ManualStretchValidator {
    /** Conservative simultaneous live scratch:16641 Doubles(133128 bytes),258 breaks, two129-knot-equivalent read/coefficient scratch,21-entry DFS, interval/root/object temporaries and sorting headroom. Excludes caller/source/candidate/raster/global proof and is not a measured heap/PSS claim. */
    @JvmStatic fun workspaceBytes(): Long = 1024L * 1024
    @JvmStatic fun analyze(candidate: ManualCurve, budget: GeometryProofBudget, checkpoint: () -> Unit): ManualStretchValidation = ManualStretchValidation.analyze(candidate, budget, checkpoint)
}

private class Box(val a: Double, val b: Double, val depth: Int)
private class Vec(val x: R, val y: R) {
    operator fun minus(other: Vec) = Vec(x - other.x, y - other.y)
}

/** Local z=(u-a)/(b-a) power basis, retaining coefficient intervals from the stored controls/LUT. Derivatives remain per UNIT u, never per z or output pixel. */
private class Segment(boundary: ManualCurve.Boundary, a: Double, b: Double, sx: Double, sy: Double) {
    val position: Array<Vec>
    val derivative: Array<Vec>
    val pointX: R; val pointY: R; val duX: R; val duY: R
    val sourceX: Double; val sourceY: Double
    init {
        var k = 0
        while (k < 127 && boundary.arcKnot(k + 1) <= a) k++
        if (b > boundary.arcKnot(k + 1)) invalid("Joint LUT interval crosses an unpartitioned knot")
        val length = R.p(boundary.arcKnot(k + 1)) - R.p(boundary.arcKnot(k))
        val rate = R.p(1.0) / (R.p(128.0) * length)
        val t0 = (R.p(k.toDouble()) + (R.p(a) - R.p(boundary.arcKnot(k))) / length) / R.p(128.0)
        val step = (R.p(b) - R.p(a)) / length / R.p(128.0)
        fun polynomial(p: Double, c: Double, e: Double): Array<R> {
            val q0 = R.p(p); val q1 = R.p(2.0) * (R.p(c) - q0); val q2 = q0 - R.p(2.0) * R.p(c) + R.p(e)
            return arrayOf(q0 + q1 * t0 + q2 * t0 * t0, (q1 + R.p(2.0) * q2 * t0) * step, q2 * step * step, (q1 + R.p(2.0) * q2 * t0) * rate, R.p(2.0) * q2 * step * rate)
        }
        val px = polynomial(boundary.start.x, boundary.control.x, boundary.end.x)
        val py = polynomial(boundary.start.y, boundary.control.y, boundary.end.y)
        position = Array(3) { Vec(px[it] / R.p(sx), py[it] / R.p(sy)) }
        derivative = Array(2) { Vec(px[it + 3] / R.p(sx), py[it + 3] / R.p(sy)) }
        // Reproduce the actual parameterAtArc -> Q / Q' -> dt/du operation order over this entire closed interval. The adjoining joint interval supplies the other derivative at each knot; the common position is included on both sides.
        val t = ((R.p(k.toDouble()) + (R(a, b) - R.p(boundary.arcKnot(k))) / length) / R.p(128.0)).hull(t0).hull(t0 + step)
        fun evaluate(p: Double, c: Double, e: Double): Pair<R, R> {
            val s = R.p(1.0) - t
            val point = s * s * R.p(p) + R.p(2.0) * t * s * R.p(c) + t * t * R.p(e)
            val d = R.p(2.0) * (s * (R.p(c) - R.p(p)) + t * (R.p(e) - R.p(c))) * rate
            // Intersect two independently valid enclosures. Power-basis cancellation restores constant/near-linear coordinates; an explicit128ulp control-scale envelope keeps the ACTUAL Bernstein evaluation rather than silently replacing it by the rounded polynomial. For |t|<=1+O(ulp), every term has magnitude<=4*scale and fewer than16 rounded operations contribute;128ulp(scale) includes subtraction/multiplication/addition and subnormal headroom.
            val q0 = R.p(p); val q1 = R.p(2.0) * (R.p(c) - q0); val q2 = q0 - R.p(2.0) * R.p(c) + R.p(e)
            val scale = max(1.0, max(abs(p), max(abs(c), abs(e))))
            val pad = Math.nextUp(128 * Math.ulp(scale))
            val tightPoint = ((q2 * t + q1) * t + q0).expand(pad)
            val tightDerivative = (R.p(2.0) * ((R.p(c) - q0) + t * (R.p(e) - R.p(2.0) * R.p(c) + q0))).expand(pad) * rate
            return point.intersect(tightPoint) to d.intersect(tightDerivative)
        }
        val actualX = evaluate(boundary.start.x, boundary.control.x, boundary.end.x)
        val actualY = evaluate(boundary.start.y, boundary.control.y, boundary.end.y)
        pointX = actualX.first; pointY = actualY.first
        duX = actualX.second / R.p(sx); duY = actualY.second / R.p(sy)
        sourceX = sx; sourceY = sy
    }
}

private fun cross(a: Vec, b: Vec): R = a.x * b.y - a.y * b.x
private fun crossPolynomial(derivative: Array<Vec>, delta: Array<Vec>): Array<R> = arrayOf(cross(derivative[0], delta[0]), cross(derivative[0], delta[1]) + cross(derivative[1], delta[0]), cross(derivative[0], delta[2]) + cross(derivative[1], delta[1]), cross(derivative[1], delta[2]))

/** Minimum of a central cubic plus an outward uniform coefficient-error enclosure. Central degree reduction never drops the interval remainder. Roots use the cancellation-resistant q/a,c/q formula; uncertain discriminants cannot authorize success. */
private fun cubicMinimum(coefficients: Array<R>): Double {
    val p = coefficients.map { if (it.lo <= 0.0 && it.hi >= 0.0) 0.0 else it.lo + (it.hi - it.lo) * .5 }
    var error = R.p(0.0)
    for (i in 0..3) error += (coefficients[i] - R.p(p[i])) * R(0.0, 1.0)
    fun value(x: R): R = ((R.p(p[3]) * x + R.p(p[2])) * x + R.p(p[1])) * x + R.p(p[0])
    var lower = minOf(value(R.p(0.0)).lo, value(R.p(1.0)).lo)
    fun visit(root: R) {
        if (root.hi >= 0.0 && root.lo <= 1.0) lower = minOf(lower, value(R(max(0.0, root.lo), minOf(1.0, root.hi))).lo)
    }
    if (p[3] == 0.0) {
        if (p[2] != 0.0) visit(-R.p(p[1]) / (R.p(2.0) * R.p(p[2])))
    } else {
        val a = R.p(3.0) * R.p(p[3]); val b = R.p(2.0) * R.p(p[2]); val c = R.p(p[1])
        val disc = b * b - R.p(4.0) * a * c
        if (disc.hi >= 0.0) {
            if (disc.lo <= 0.0) return Double.NEGATIVE_INFINITY
            val root = R(Math.nextDown(sqrt(disc.lo)), Math.nextUp(sqrt(disc.hi)))
            val q = -R.p(.5) * (b + if (p[2] < 0.0) -root else root)
            if (q.lo <= 0.0 && q.hi >= 0.0) return Double.NEGATIVE_INFINITY
            visit(q / a); visit(c / q)
        }
    }
    return Math.nextDown(lower + error.lo)
}

private fun actualBounds(t: Segment, b: Segment, depth: Double): Pair<Double, Double> {
    fun blend(a: R, b: R): R {
        // Exact w(v) is in[0,1]. Five binary64 weight operations with |d|<=.95 have absolute error <16ulp(1); four subsequent blend operations add <16ulp(max(1,|a|,|b|)). This also covers gradual underflow, without assuming correlated rounded products cancel.
        val scale = max(1.0, max(a.magnitude(), b.magnitude()))
        val margin = Math.nextUp(16 * Math.ulp(1.0) * Math.nextUp(a.magnitude() + b.magnitude()) + 16 * Math.ulp(scale))
        return R(Math.nextDown(minOf(a.lo, b.lo) - margin), Math.nextUp(max(a.hi, b.hi) + margin))
    }
    // duX/Y already enclose source-normalization division. Blending in these units commutes in real arithmetic; extra rounding headroom covers the candidate's blend-before-division order as well.
    val ux = blend(t.duX, b.duX); val uy = blend(t.duY, b.duY)
    val dw = R.p(1.0) + R.p(depth) * (R.p(1.0) - R.p(2.0) * R(0.0, 1.0))
    val vx = dw * (b.pointX - t.pointX) / R.p(t.sourceX)
    val vy = dw * (b.pointY - t.pointY) / R.p(t.sourceY)
    val determinant = ux * vy - uy * vx
    var sum = 0.0
    for (v in arrayOf(ux, uy, vx, vy)) { val m = v.magnitude(); sum = Math.nextUp(sum + Math.nextUp(m * m)) }
    return determinant.lo to Math.nextUp(sqrt(sum))
}

/** True quadratic-coordinate stationary point, plus endpoints (which also bound both straight sides). Uniform rounding headroom encloses Q and the final ruling blend at every t/v, including exact endpoint shortcuts. */
private fun extent(p: Double, c: Double, e: Double): R {
    var range = R.p(p).hull(R.p(e))
    val denominator = R.p(p) - R.p(2.0) * R.p(c) + R.p(e)
    if (denominator.lo <= 0.0 && denominator.hi >= 0.0) {
        range = range.hull(R.p(c))
    } else {
        val t = (R.p(p) - R.p(c)) / denominator
        if (t.hi >= 0.0 && t.lo <= 1.0) {
            val z = R(max(0.0, t.lo), minOf(1.0, t.hi)); val s = R.p(1.0) - z
            range = range.hull(s * s * R.p(p) + R.p(2.0) * z * s * R.p(c) + z * z * R.p(e))
        }
    }
    val scale = max(1.0, max(abs(p), max(abs(c), abs(e))))
    val pad = Math.nextUp(128 * Math.ulp(scale))
    return R(Math.nextDown(range.lo - pad), Math.nextUp(range.hi + pad))
}

private fun safe(j: Double, m: Double, floor: Double): Boolean = j.isFinite() && m.isFinite() && j > floor && m <= 100.0 && Math.nextDown(j / m) >= 1e-4 && Math.nextUp(Math.nextUp(m * m) / j) <= 1000.0
private fun invalid(message: String): Nothing = throw InvalidInverseMapException(message)

/** One-ulp outward expansion after EVERY elementary operation encloses both real stored-input arithmetic and the corresponding binary64 operation. Only finite intervals are constructed; sign-uncertain division refuses rather than guessing. */
private class R(val lo: Double, val hi: Double) {
    init { if (!lo.isFinite() || !hi.isFinite() || lo > hi) invalid("Nonfinite or uncertain manual interval") }
    operator fun unaryMinus() = R(-hi, -lo)
    operator fun plus(b: R) = R(Math.nextDown(lo + b.lo), Math.nextUp(hi + b.hi))
    operator fun minus(b: R) = R(Math.nextDown(lo - b.hi), Math.nextUp(hi - b.lo))
    operator fun times(b: R): R {
        val a = lo * b.lo; val c = lo * b.hi; val d = hi * b.lo; val e = hi * b.hi
        return R(Math.nextDown(minOf(a, c, d, e)), Math.nextUp(maxOf(a, c, d, e)))
    }
    operator fun div(b: R): R {
        if (b.lo <= 0.0 && b.hi >= 0.0) invalid("Uncertain manual interval divisor")
        return this * R(Math.nextDown(1.0 / b.hi), Math.nextUp(1.0 / b.lo))
    }
    fun hull(b: R) = R(minOf(lo, b.lo), maxOf(hi, b.hi))
    fun intersect(b: R) = R(max(lo, b.lo), minOf(hi, b.hi))
    fun expand(pad: Double) = R(Math.nextDown(lo - pad), Math.nextUp(hi + pad))
    fun magnitude(): Double = max(abs(lo), abs(hi))
    companion object { fun p(value: Double) = R(value, value) }
}
