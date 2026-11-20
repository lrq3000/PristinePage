package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PointPx

/** One directed cubic. The controls are coordinates, not screen offsets, and are not automatically mirrored. */
internal data class AtelierCubic(
    val start: PointPx,
    val outgoing: PointPx,
    val incoming: PointPx,
    val end: PointPx,
) {
    init {
        require(listOf(start, outgoing, incoming, end).all { it.x.isFinite() && it.y.isFinite() })
    }

    fun pointAt(t: Double): PointPx {
        require(t in 0.0..1.0)
        if (t == 0.0) return start
        if (t == 1.0) return end
        val a = mix(start, outgoing, t)
        val b = mix(outgoing, incoming, t)
        val c = mix(incoming, end, t)
        return mix(mix(a, b, t), mix(b, c, t), t)
    }

    /** Both sub-curves retain the original locus and the same exact shared knot. */
    fun split(t: Double): Pair<AtelierCubic, AtelierCubic> {
        require(t > 0.0 && t < 1.0)
        val a = mix(start, outgoing, t)
        val b = mix(outgoing, incoming, t)
        val c = mix(incoming, end, t)
        val d = mix(a, b, t)
        val e = mix(b, c, t)
        val knot = mix(d, e, t)
        return AtelierCubic(start, a, d, knot) to AtelierCubic(knot, e, c, end)
    }

    private fun mix(a: PointPx, b: PointPx, t: Double) =
        PointPx((1 - t) * a.x + t * b.x, (1 - t) * a.y + t * b.y)
}
