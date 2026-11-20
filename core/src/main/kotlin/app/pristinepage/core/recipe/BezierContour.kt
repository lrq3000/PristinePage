package app.pristinepage.core.recipe

import app.pristinepage.core.geometry.AtelierCubic
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ContourSide { TOP, RIGHT, BOTTOM, LEFT }

@Serializable data class BezierSegment(val from: Double, val to: Double, val start: PointPx, val outgoing: PointPx, val incoming: PointPx, val end: PointPx) {
    init {
        require(from in 0.0..1.0 && to in 0.0..1.0 && to - from >= 1e-5)
        require(listOf(start, outgoing, incoming, end).all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
    }
    internal fun cubic() = AtelierCubic(start, outgoing, incoming, end)
}

/** Immutable chain. Chord display controls are canonical consequences of the endpoints and knots, never independent geometry. */
@Serializable class BezierEdge private constructor(@SerialName("segments") private var entries: List<BezierSegment>, val chordOnly: Boolean) {
    val segments: List<BezierSegment> get() = entries
    init {
        require(entries.size in 1..8)
        entries = Collections.unmodifiableList(ArrayList(entries))
        require(entries.first().from == 0.0 && entries.last().to == 1.0)
        require(entries.zipWithNext().all { (a, b) -> a.to == b.from && a.end == b.start })
        if (chordOnly) {
            require(entries == lineSegments(entries.first().start, entries.last().end, entries.map { it.from } + 1.0))
        }
    }
    override fun equals(other: Any?) = other is BezierEdge && chordOnly == other.chordOnly && entries == other.entries
    override fun hashCode() = 31 * entries.hashCode() + chordOnly.hashCode()

    internal fun insert(index: Int, t: Double): BezierEdge {
        require(entries.size < 8 && t > 0.0 && t < 1.0)
        val s = entries[index]
        val knot = s.from + (s.to - s.from) * t
        if (chordOnly) return line(entries.first().start, entries.last().end, (entries.map { it.from } + knot + 1.0).sorted())
        val (a, b) = s.cubic().split(t)
        val replacement = listOf(BezierSegment(s.from, knot, a.start, a.outgoing, a.incoming, a.end),
            BezierSegment(knot, s.to, b.start, b.outgoing, b.incoming, b.end))
        return curved(entries.take(index) + replacement + entries.drop(index + 1))
    }

    internal fun moveHandle(index: Int, outgoing: Boolean, point: PointPx): BezierEdge {
        val segment = entries[index]
        val next = if (outgoing) segment.copy(outgoing = point) else segment.copy(incoming = point)
        return if (next == segment) this else curved(entries.toMutableList().also { it[index] = next })
    }

    internal fun moveNode(index: Int, point: PointPx): BezierEdge {
        require(index in 1 until entries.size)
        val before = entries[index - 1]
        val after = entries[index]
        if (point == after.start) return this
        val dx = point.x - after.start.x
        val dy = point.y - after.start.y
        fun translate(p: PointPx) = PointPx(p.x + dx, p.y + dy)
        return curved(entries.toMutableList().also {
            it[index - 1] = before.copy(end = point, incoming = translate(before.incoming))
            it[index] = after.copy(start = point, outgoing = translate(after.outgoing))
        })
    }

    internal fun moveEndpoints(start: PointPx, end: PointPx): BezierEdge {
        val first = entries.first()
        val last = entries.last()
        if (start == first.start && end == last.end) return this
        if (chordOnly) return line(start, end, entries.map { it.from } + 1.0)
        fun move(p: PointPx, from: PointPx, to: PointPx) = PointPx(
            (p.x + (to.x - from.x)).coerceIn(0.0, 1.0),
            (p.y + (to.y - from.y)).coerceIn(0.0, 1.0),
        )
        val updated = entries.toMutableList()
        updated[0] = first.copy(start = start, outgoing = move(first.outgoing, first.start, start))
        updated[updated.lastIndex] = updated.last().copy(end = end, incoming = move(last.incoming, last.end, end))
        return curved(updated)
    }

    internal fun removeNode(index: Int): BezierEdge {
        require(index in 1 until entries.size)
        if (chordOnly) return line(entries.first().start, entries.last().end,
            (entries.map { it.from } + 1.0).filterIndexed { i, _ -> i != index })
        val a = entries[index - 1]
        val b = entries[index]
        return curved(entries.take(index - 1) + BezierSegment(a.from, b.to, a.start, a.outgoing, b.incoming, b.end) + entries.drop(index + 1))
    }

    companion object {
        internal fun curved(pieces: List<BezierSegment>): BezierEdge {
            val canonical = lineSegments(pieces.first().start, pieces.last().end, pieces.map { it.from } + 1.0)
            return BezierEdge(pieces, pieces == canonical)
        }
        internal fun line(a: PointPx, b: PointPx, knots: List<Double> = listOf(0.0, 1.0)) = BezierEdge(lineSegments(a, b, knots), true)
        private fun lineSegments(a: PointPx, b: PointPx, knots: List<Double>) = knots.zipWithNext { lo, hi ->
            val width = hi - lo
            fun at(t: Double) = when (t) {
                0.0 -> a
                1.0 -> b
                else -> PointPx(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
            BezierSegment(lo, hi, at(lo), at(lo + width / 3), at(hi - width / 3), at(hi))
        }
    }
}

@Serializable data class BezierContour(val quad: QuadPx, val top: BezierEdge, val right: BezierEdge, val bottom: BezierEdge, val left: BezierEdge) {
    init {
        for ((side, endpoints) in listOf(ContourSide.TOP to (quad.tl to quad.tr), ContourSide.RIGHT to (quad.tr to quad.br),
            ContourSide.BOTTOM to (quad.bl to quad.br), ContourSide.LEFT to (quad.tl to quad.bl))) {
            val segments = edge(side).segments
            require(segments.first().start == endpoints.first && segments.last().end == endpoints.second)
        }
    }
    val straight: Boolean get() = top.chordOnly && right.chordOnly && bottom.chordOnly && left.chordOnly
    fun edge(side: ContourSide): BezierEdge = when (side) {
        ContourSide.TOP -> top
        ContourSide.RIGHT -> right
        ContourSide.BOTTOM -> bottom
        ContourSide.LEFT -> left
    }
    private fun replace(side: ContourSide, edge: BezierEdge) = when (side) {
        ContourSide.TOP -> copy(top = edge)
        ContourSide.RIGHT -> copy(right = edge)
        ContourSide.BOTTOM -> copy(bottom = edge)
        ContourSide.LEFT -> copy(left = edge)
    }
    fun insert(side: ContourSide, segment: Int, t: Double) = replace(side, edge(side).insert(segment, t))
    fun moveHandle(side: ContourSide, segment: Int, outgoing: Boolean, point: PointPx) =
        replace(side, edge(side).moveHandle(segment, outgoing, point))
    fun moveNode(side: ContourSide, index: Int, point: PointPx) = replace(side, edge(side).moveNode(index, point))
    fun removeNode(side: ContourSide, index: Int) = replace(side, edge(side).removeNode(index))

    /** A corner is shared by two chains. Update both before constructing the immutable contour. */
    fun moveCorners(corners: QuadPx) = BezierContour(corners, top.moveEndpoints(corners.tl, corners.tr),
        right.moveEndpoints(corners.tr, corners.br), bottom.moveEndpoints(corners.bl, corners.br),
        left.moveEndpoints(corners.tl, corners.bl))

    companion object {
        fun straight(quad: QuadPx) = BezierContour(quad, BezierEdge.line(quad.tl, quad.tr), BezierEdge.line(quad.tr, quad.br),
            BezierEdge.line(quad.bl, quad.br), BezierEdge.line(quad.tl, quad.bl))
    }
}
