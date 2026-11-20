package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.recipe.ContourSide
import app.pristinepage.core.recipe.GeometryRecipe

internal data class ContourSegment(val start: Double, val end: Double, val cubic: AtelierCubic)

internal class ContourEdge private constructor(pieces: List<ContourSegment>, val chordOnly: Boolean) {
    val segments = pieces.toList()
    init {
        require(segments.size in 1..8 && segments.first().start == 0.0 && segments.last().end == 1.0)
        segments.forEachIndexed { i, piece ->
            require(piece.end - piece.start >= 1e-5)
            if (i != 0) {
                val previous = segments[i - 1]
                require(previous.end == piece.start && previous.cubic.end == piece.cubic.start)
            }
        }
    }
    fun at(t: Double): PointPx {
        if (chordOnly) return blend(segments.first().cubic.start, segments.last().cubic.end, t)
        val piece = segments.first { t <= it.end }
        return piece.cubic.pointAt(((t - piece.start) / (piece.end - piece.start)).coerceIn(0.0, 1.0))
    }
    fun insert(index: Int, t: Double): ContourEdge {
        val pieces = segments.toMutableList()
        val segment = pieces.removeAt(index)
        val knot = segment.start + (segment.end - segment.start) * t
        val (before, after) = segment.cubic.split(t)
        pieces.add(index, ContourSegment(knot, segment.end, after))
        pieces.add(index, ContourSegment(segment.start, knot, before))
        // An untouched chord remains exactly linear in global parameter. Its rounded display controls are not independent curve data.
        return ContourEdge(pieces, chordOnly)
    }
    fun moveHandle(index: Int, outgoing: Boolean, position: PointPx): ContourEdge {
        val pieces = segments.toMutableList()
        val segment = pieces[index]
        val curve = if (outgoing) segment.cubic.copy(outgoing = position) else segment.cubic.copy(incoming = position)
        if (curve == segment.cubic) return this
        pieces[index] = segment.copy(cubic = curve)
        return ContourEdge(pieces, false)
    }
    companion object {
        fun authored(pieces: List<ContourSegment>, chordOnly: Boolean) = ContourEdge(pieces, chordOnly)
        fun line(a: PointPx, b: PointPx) = ContourEdge(listOf(ContourSegment(0.0, 1.0,
            AtelierCubic(a, blend(a, b, 1.0 / 3), blend(a, b, 2.0 / 3), b))), true)
    }
}

internal data class AtelierContour(val quad: QuadPx, val top: ContourEdge, val right: ContourEdge, val bottom: ContourEdge, val left: ContourEdge) {
    init {
        require(top.at(0.0) == quad.tl && top.at(1.0) == quad.tr)
        require(right.at(0.0) == quad.tr && right.at(1.0) == quad.br)
        require(bottom.at(0.0) == quad.bl && bottom.at(1.0) == quad.br)
        require(left.at(0.0) == quad.tl && left.at(1.0) == quad.bl)
    }
    fun edge(side: ContourSide): ContourEdge = when (side) {
        ContourSide.TOP -> top; ContourSide.RIGHT -> right; ContourSide.BOTTOM -> bottom; ContourSide.LEFT -> left
    }
    private fun replace(side: ContourSide, edge: ContourEdge): AtelierContour {
        return when (side) {
            ContourSide.TOP -> copy(top = edge); ContourSide.RIGHT -> copy(right = edge)
            ContourSide.BOTTOM -> copy(bottom = edge); ContourSide.LEFT -> copy(left = edge)
        }
    }
    fun insert(side: ContourSide, index: Int, t: Double) = replace(side, edge(side).insert(index, t))
    fun moveHandle(side: ContourSide, index: Int, outgoing: Boolean, position: PointPx) =
        replace(side, edge(side).moveHandle(index, outgoing, position))
    companion object {
        fun straight(quad: QuadPx): AtelierContour {
            val line = ContourEdge::line
            return AtelierContour(quad, line(quad.tl, quad.tr), line(quad.tr, quad.br), line(quad.bl, quad.br), line(quad.tl, quad.bl))
        }
    }
}

/** Homography plus cubic edge departures, reparameterized by projective edge weights. This is a candidate for independent numerical review, not a production renderer admission certificate. */
internal class AtelierProjectiveContour private constructor(
    private val base: Homography,
    private val edges: List<ContourEdge>,
    private val weights: List<Pair<Double, Double>>,
    private val straight: Boolean,
) {
    fun map(u: Double, v: Double): PointPx {
        require(u in 0.0..1.0 && v in 0.0..1.0)
        val original = base.map(u * (base.outputSize.width - 1), v * (base.outputSize.height - 1))
        if (straight) return original
        val t = departure(0, u); val r = departure(1, v); val b = departure(2, u); val l = departure(3, v)
        return PointPx(original.x + (1 - v) * t.x + v * b.x + (1 - u) * l.x + u * r.x,
            original.y + (1 - v) * t.y + v * b.y + (1 - u) * l.y + u * r.y)
    }

    internal fun axes(checkpoint: () -> Unit): CubicSamplingAxes {
        val width = base.outputSize.width
        val height = base.outputSize.height
        fun axis(side: Int, size: Int): DoubleArray {
            val result = DoubleArray(2 * size)
            for (i in 0 until size) {
                if (i % 128 == 0) checkpoint()
                val p = departure(side, i.toDouble() / (size - 1))
                result[2 * i] = p.x
                result[2 * i + 1] = p.y
            }
            return result
        }
        return CubicSamplingAxes(base, axis(0, width), axis(1, height), axis(2, width), axis(3, height))
    }
    private fun departure(side: Int, s: Double): PointPx {
        val edge = edges[side]
        if (edge.chordOnly || s == 0.0 || s == 1.0) return PointPx(0.0, 0.0)
        val (a, b) = weights[side]
        val t = (s * b / ((1 - s) * a + s * b)).coerceIn(0.0, 1.0)
        return subtract(edge.at(t), blend(edge.at(0.0), edge.at(1.0), t))
    }

    companion object {
        fun prepare(contour: AtelierContour, source: PixelSize, output: PixelSize, checkpoint: () -> Unit = {}): AtelierProjectiveContour {
            checkpoint()
            val h = Homography.solve(contour.quad, source, output)
            val edges = ContourSide.entries.map(contour::edge)
            val m = h.backwardCoefficients()
            val width = (output.width - 1).toDouble(); val height = (output.height - 1).toDouble()
            val g = m[6] * width; val k = m[7] * height; val z = m[8]
            val corners = listOf(z, g + z, g + k + z, k + z)
            require(corners.all { it != 0.0 && it.isFinite() && (it > 0) == (z > 0) })
            val weights = listOf(corners[0] to corners[1], corners[1] to corners[2], corners[3] to corners[2], corners[0] to corners[3])
            val straight = edges.all(ContourEdge::chordOnly)
            val map = AtelierProjectiveContour(h, edges, weights, straight)
            if (!straight) {
                AtelierStretchProof.certify(h, edges, weights)
                var total = 0.0
                for (y in 0..128) {
                    checkpoint()
                    for (x in 0..128) {
                        val point = map.map(x / 128.0, y / 128.0)
                        val support = BilinearSampler.support(source, point.x, point.y)
                        if (support < .75) throw InvalidInverseMapException("Cubic support is below the minimum")
                        total += support
                    }
                }
                if (total / (129 * 129) < .995) throw InvalidInverseMapException("Cubic mean support is below the minimum")
            }
            checkpoint()
            return map
        }
    }
}

/** Source-bound curved (not straight) raster preparation. Only the admitted map can construct this sampler. */
class CubicSamplingAxes internal constructor(private val base: Homography, private val top: DoubleArray,
    private val right: DoubleArray, private val bottom: DoubleArray, private val left: DoubleArray) {
    fun map(x: Int, y: Int): PointPx {
        val original = base.map(x.toDouble(), y.toDouble())
        val u = x.toDouble() / (base.outputSize.width - 1)
        val v = y.toDouble() / (base.outputSize.height - 1)
        val i = 2 * x; val j = 2 * y
        val sx = original.x + (1 - v) * top[i] + v * bottom[i] + (1 - u) * left[j] + u * right[j]
        val sy = original.y + (1 - v) * top[i + 1] + v * bottom[i + 1] + (1 - u) * left[j + 1] + u * right[j + 1]
        val size = base.sourceSize
        if (sx !in -.25..(size.width - .75) || sy !in -.25..(size.height - .75) ||
            ((sx < 0 || sx > size.width - 1 || sy < 0 || sy > size.height - 1) && BilinearSampler.support(size, sx, sy) < .75)) {
            throw InvalidInverseMapException("Rounded cubic sample exceeds source support")
        }
        return PointPx(sx, sy)
    }

    companion object {
        fun workspaceBytes(base: PixelSize): Long = 512L * 1024 + 32L * (base.width.toLong() + base.height)

        fun prepare(recipe: GeometryRecipe.Bezier, base: PixelSize, checkpoint: () -> Unit): CubicSamplingAxes {
            require(!recipe.contour.straight)
            val size = recipe.source.sourceSize
            fun pixel(p: PointPx) = PointPx(p.x * (size.width - 1), p.y * (size.height - 1))
            fun edge(side: ContourSide): ContourEdge {
                val edge = recipe.contour.edge(side)
                return ContourEdge.authored(edge.segments.map {
                    ContourSegment(it.from, it.to, AtelierCubic(pixel(it.start), pixel(it.outgoing), pixel(it.incoming), pixel(it.end)))
                }, edge.chordOnly)
            }
            val q = recipe.contour.quad
            val contour = AtelierContour(QuadPx(pixel(q.tl), pixel(q.tr), pixel(q.br), pixel(q.bl)),
                edge(ContourSide.TOP), edge(ContourSide.RIGHT), edge(ContourSide.BOTTOM), edge(ContourSide.LEFT))
            return AtelierProjectiveContour.prepare(contour, size, base, checkpoint).axes(checkpoint)
        }
    }
}

private fun blend(a: PointPx, b: PointPx, t: Double) = when (t) {
    0.0 -> a
    1.0 -> b
    else -> PointPx(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
}
private fun subtract(a: PointPx, b: PointPx) = PointPx(a.x - b.x, a.y - b.y)
