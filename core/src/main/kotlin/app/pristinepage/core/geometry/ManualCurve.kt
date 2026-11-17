package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A numerical preparation failure maps to INVALID_MAP, unlike malformed typed inputs (IllegalArgumentException). Cancellation is never wrapped or converted into a fallback. */
class InvalidManualCurveException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Immutable source-bound numerical CANDIDATE for manual_ruled_v1, not an admitted geometry map. Topology, global boundary simplicity, stretch and source support are NOT proven; this class cannot authorize image output, even when its positions are finite. Folded candidates deliberately remain observable for the later validator. */
class ManualCurve private constructor(
    val source: SourceBinding,
    val outputSize: PixelSize,
    val baseOutputSize: PixelSize,
    val outputQuarterTurns: Int,
    val depth: Double,
    val topBoundary: Boundary?,
    val bottomBoundary: Boundary?,
    private val perspective: Homography?,
) {
    enum class Dispatch { DIRECT_RULED_CANDIDATE, PERSPECTIVE_STRAIGHT_CANDIDATE }
    enum class KnotSide { LEFT, RIGHT }
    val dispatch: Dispatch = if (perspective == null) Dispatch.DIRECT_RULED_CANDIDATE else Dispatch.PERSPECTIVE_STRAIGHT_CANDIDATE
    private val perspectiveCoefficients = perspective?.backwardCoefficients()

    /** Vectors are source PIXELS per UNIT UNROTATED BASE destination, not source-normalized vectors and not derivatives per destination pixel. At LUT knots the caller must inspect both sides. */
    data class Derivatives(val du: PointPx, val dv: PointPx)
    /** Exact quadratic-coordinate extrema evaluated in binary64, not outward-rounded admission bounds or a topology/visibility certificate. */
    data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    /** The consumer must compare the actual decoded source binding before using this candidate; preparation receives no pixels and therefore cannot independently verify a source hash. */
    fun requireSource(actual: SourceBinding) { require(actual == source) { "Manual candidate belongs to a different oriented source" } }

    /** Final post-rotation destination pixel centers -> oriented-source pixel centers, with neither half-pixel resize nor an inset. */
    fun map(x: Double, y: Double): PointPx {
        require(x.isFinite() && y.isFinite() && x in 0.0..(outputSize.width - 1).toDouble() && y in 0.0..(outputSize.height - 1).toDouble()) { "Manual input must be a destination center" }
        return mapUnit(x / (outputSize.width - 1), y / (outputSize.height - 1))
    }

    /** Final unit destination -> oriented-source PIXELS (not normalized source coordinates). Quarter-turns are composed before the base map, never through an intermediate raster. */
    fun mapUnit(u: Double, v: Double): PointPx {
        val base = Coordinates.finalToBase(u, v, outputQuarterTurns)
        return mapBaseUnit(base.x, base.y)
    }

    /** Unrotated unit base destination -> oriented-source pixels. This is already a direct inverse map, not something to invert again or post-compose with a homography. */
    fun mapBaseUnit(u: Double, v: Double): PointPx {
        unit(u); unit(v)
        perspective?.let { return it.map(u * (baseOutputSize.width - 1), v * (baseOutputSize.height - 1)) }
        val top = topBoundary!!.pointAtArc(u)
        val bottom = bottomBoundary!!.pointAtArc(u)
        val w = weight(v)
        return point((1.0 - w) * top.x + w * bottom.x, (1.0 - w) * top.y + w * bottom.y)
    }

    /** Analytic derivatives against the BASE unit domain; KnotSide chooses the common u-direction for both boundaries on their joint LUT partition. No finite-difference or sampled-grid validity claim is made. */
    fun derivativesBaseUnit(u: Double, v: Double, side: KnotSide): Derivatives {
        unit(u); unit(v)
        perspectiveCoefficients?.let { g ->
            val x = u * (baseOutputSize.width - 1); val y = v * (baseOutputSize.height - 1)
            val z = g[6] * x + g[7] * y + g[8]
            val nx = g[0] * x + g[1] * y + g[2]; val ny = g[3] * x + g[4] * y + g[5]
            val denominator = z * z
            val sx = (baseOutputSize.width - 1).toDouble(); val sy = (baseOutputSize.height - 1).toDouble()
            return Derivatives(point((g[0] * z - nx * g[6]) / denominator * sx, (g[3] * z - ny * g[6]) / denominator * sx), point((g[1] * z - nx * g[7]) / denominator * sy, (g[4] * z - ny * g[7]) / denominator * sy))
        }
        val top = topBoundary!!; val bottom = bottomBoundary!!
        val t = top.pointAtArc(u); val b = bottom.pointAtArc(u)
        val td = top.derivativeAtArc(u, side); val bd = bottom.derivativeAtArc(u, side)
        val w = weight(v); val dw = 1.0 + depth * (1.0 - 2.0 * v)
        return Derivatives(point((1.0 - w) * td.x + w * bd.x, (1.0 - w) * td.y + w * bd.y), point(dw * (b.x - t.x), dw * (b.y - t.y)))
    }

    /** Immutable solver read port for the parent's existing perspective validator; null means direct ruled candidate, not a failed solution. Neither branch is admitted here. */
    internal fun straightHomography(): Homography? = perspective
    private fun weight(v: Double): Double = v + depth * v * (1.0 - v)

    /** Read-only quadratic and its separately normalized 128-CHORD arc approximation. No public arbitrary-control/LUT constructor and no borrowed mutable arrays are exposed. */
    class Boundary private constructor(val start: PointPx, val control: PointPx, val end: PointPx, private val lengths: DoubleArray, val totalChordLength: Double) {
        val bounds: Bounds = extrema()
        fun arcKnots(): DoubleArray = lengths.copyOf()
        fun arcKnot(index: Int): Double { require(index in lengths.indices); return lengths[index] }

        fun pointAtParameter(t: Double): PointPx {
            unit(t)
            if (t == 0.0) return start
            if (t == 1.0) return end
            val s = 1.0 - t
            return point(s * s * start.x + 2.0 * t * s * control.x + t * t * end.x, s * s * start.y + 2.0 * t * s * control.y + t * t * end.y)
        }

        fun derivativeAtParameter(t: Double): PointPx {
            unit(t)
            return point(2.0 * ((1.0 - t) * (control.x - start.x) + t * (end.x - control.x)), 2.0 * ((1.0 - t) * (control.y - start.y) + t * (end.y - control.y)))
        }

        fun parameterAtArc(u: Double): Double {
            unit(u)
            if (u == 0.0 || u == 1.0) return u
            val k = interval(u, KnotSide.RIGHT)
            return (k + (u - lengths[k]) / (lengths[k + 1] - lengths[k])) / SEGMENTS
        }

        fun pointAtArc(u: Double): PointPx = pointAtParameter(parameterAtArc(u))

        /** Source pixels per unit normalized chord-arc coordinate. At an internal knot positions agree, but dt/du uses the selected adjacent interval; endpoints use their sole interior derivative. */
        fun derivativeAtArc(u: Double, side: KnotSide): PointPx {
            unit(u)
            val k = interval(u, side)
            val dt = 1.0 / (SEGMENTS * (lengths[k + 1] - lengths[k]))
            val d = derivativeAtParameter(parameterAtArc(u))
            return point(d.x * dt, d.y * dt)
        }

        private fun interval(u: Double, side: KnotSide): Int {
            if (u == 0.0) return 0
            if (u == 1.0) return SEGMENTS - 1
            // Upper bound selects l[k]<=u<l[k+1], including exact internal knots. Choosing LEFT affects derivatives only, never the common position at the knot.
            var low = 0; var high = lengths.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (lengths[middle] <= u) low = middle + 1 else high = middle
            }
            val right = low - 1
            return if (side == KnotSide.LEFT && right > 0 && u == lengths[right]) right - 1 else right
        }

        private fun extrema(): Bounds {
            var minX = min(start.x, end.x); var maxX = max(start.x, end.x)
            var minY = min(start.y, end.y); var maxY = max(start.y, end.y)
            fun extremum(p0: Double, c: Double, p2: Double): Double? {
                val denominator = p0 - 2.0 * c + p2
                if (denominator == 0.0) return null
                val t = (p0 - c) / denominator
                return if (t > 0.0 && t < 1.0) t else null
            }
            extremum(start.x, control.x, end.x)?.let { val x = pointAtParameter(it).x; minX = min(minX, x); maxX = max(maxX, x) }
            extremum(start.y, control.y, end.y)?.let { val y = pointAtParameter(it).y; minY = min(minY, y); maxY = max(maxY, y) }
            return Bounds(minX, minY, maxX, maxY)
        }

        companion object {
            internal fun prepare(start: PointPx, midpoint: PointPx, end: PointPx, checkpoint: () -> Unit): Boundary {
                checkpoint()
                // M is ON the curve at t=.5. Treating M as C would halve its sagitta; an outside derived C is not itself a reason to clamp/reject the actual curve.
                val control = point(2.0 * midpoint.x - .5 * (start.x + end.x), 2.0 * midpoint.y - .5 * (start.y + end.y))
                val lengths = DoubleArray(SEGMENTS + 1)
                // A temporary boundary owns the one LUT, rather than allocating a raster/grid or keeping all sampled points. Its extrema calculation is also constant-sized.
                val curve = Boundary(start, control, end, lengths, 0.0)
                var previous = start; var total = 0.0; var smallest = Double.POSITIVE_INFINITY
                for (k in 1..SEGMENTS) {
                    checkpoint()
                    val current = curve.pointAtParameter(k.toDouble() / SEGMENTS)
                    val increment = hypot(current.x - previous.x, current.y - previous.y)
                    if (!increment.isFinite() || increment <= 0.0) invalid("Nonpositive or nonfinite manual arc segment")
                    smallest = min(smallest, increment)
                    total += increment
                    if (!total.isFinite()) invalid("Nonfinite manual chord length")
                    lengths[k] = total
                    previous = current
                }
                if (total <= 1.0) invalid("Manual chord length must exceed one source pixel")
                if (smallest <= max(1e-12, 1e-12 * total)) invalid("Uncertain manual arc segment")
                for (k in 1..SEGMENTS) {
                    checkpoint()
                    lengths[k] /= total
                    if (!lengths[k].isFinite() || lengths[k] <= lengths[k - 1]) invalid("Manual normalized arc knots must be strictly increasing")
                }
                return Boundary(start, control, end, lengths, total)
            }
        }
    }

    companion object {
        private const val SEGMENTS = 128

        /** Conservative simultaneous numerical setup/retained-storage estimate (including the straight SVD branch), independent of source/output pixel count. Not a JVM/PSS bound; caller-owned source, raster, proof scratch and copied read-port results are separate reservations. */
        @JvmStatic fun workspaceBytes(): Long = 65536L

        @JvmStatic fun prepare(recipe: GeometryRecipe.ManualCurve, output: PixelSize, checkpoint: () -> Unit): ManualCurve {
            checkpoint()
            val source = recipe.source.sourceSize
            require(source.width >= 2 && source.height >= 2 && output.width >= 2 && output.height >= 2) { "Manual geometry needs two-dimensional source and output centers" }
            // Typed recipes already reject unknown versions, turns, handles, depth and non-128 arc counts. Reuse canonical quad/target sizing, including its strict convexity and ratio rules, rather than repairing controls here.
            val target = GeometrySizing.target(recipe)
            require(admissibleOutput(target, output)) { "Manual output must be a common floored downscale of the declared target" }
            fun denormalize(p: PointPx): PointPx = point(p.x * (source.width - 1), p.y * (source.height - 1))
            val q = recipe.quad
            val quad = QuadPx(denormalize(q.tl), denormalize(q.tr), denormalize(q.br), denormalize(q.bl))
            QuadValidation.validate(quad, source)
            val base = Coordinates.rotatedSize(output, recipe.outputQuarterTurns)
            checkpoint()
            val straight = recipe.depth == 0.0 && recipe.topMidpoint == midpoint(q.tl, q.tr) && recipe.bottomMidpoint == midpoint(q.bl, q.br)
            if (straight) {
                val solved = try { Homography.solve(quad, source, base) } catch (failure: InvalidHomographyException) { throw InvalidManualCurveException("Straight manual homography preparation failed", failure) }
                checkpoint()
                return ManualCurve(recipe.source, output, base, recipe.outputQuarterTurns, recipe.depth, null, null, solved)
            }
            val top = Boundary.prepare(quad.tl, denormalize(recipe.topMidpoint), quad.tr, checkpoint)
            val bottom = Boundary.prepare(quad.bl, denormalize(recipe.bottomMidpoint), quad.br, checkpoint)
            checkpoint()
            return ManualCurve(recipe.source, output, base, recipe.outputQuarterTurns, recipe.depth, top, bottom, null)
        }

        private fun midpoint(a: PointPx, b: PointPx): PointPx = PointPx((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
        private fun unit(value: Double) { require(value.isFinite() && value in 0.0..1.0) { "Manual parameter must be finite in the unit domain" } }
        private fun point(x: Double, y: Double): PointPx {
            if (!x.isFinite() || !y.isFinite()) invalid("Nonfinite manual numerical result")
            return PointPx(x, y)
        }
        private fun invalid(message: String): Nothing = throw InvalidManualCurveException(message)

        /** Same strict common-floor-scale admission as GeometryEngine; no output-purpose inference, approximate-aspect stretching or hidden upscaling. Target calculation itself remains GeometrySizing's contract. */
        private fun admissibleOutput(target: PixelSize, output: PixelSize): Boolean {
            if (output.width > target.width || output.height > target.height) return false
            if (output == target) return true
            val low = max(output.width.toDouble() / target.width, output.height.toDouble() / target.height)
            val high = min(1.0, min((output.width.toDouble() + 1.0) / target.width, (output.height.toDouble() + 1.0) / target.height))
            return low < high
        }
    }
}
