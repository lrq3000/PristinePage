package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Validates labeled TL,TR,BR,BL in oriented-source pixel centers, never normalized controls. Invalid or numerically uncertain input is rejected, not repaired. */
object QuadValidation {
    @JvmStatic fun validate(quad: QuadPx, source: PixelSize) {
        require(source.width >= 2 && source.height >= 2) { "Quad geometry requires source axes of at least two" }
        val points = quad.points()
        val maxX = source.width.toDouble() - 1.0
        val maxY = source.height.toDouble() - 1.0
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..maxX && it.y in 0.0..maxY }) { "Quad corners must be finite source centers within bounds" }
        val diagonal = max(1.0, hypot(maxX, maxY))
        val lengthUncertainty = 1e-10 * diagonal
        val crossUncertainty = 1e-12 * diagonal * diagonal
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % 4]
            val c = points[(i + 2) % 4]
            val length = hypot(b.x - a.x, b.y - a.y)
            // The product's one-pixel minimum is inclusive (the valid 3x2 full frame has one-pixel vertical edges); uncertainty separately rejects geometric degeneracy.
            require(length >= 1.0 && length > lengthUncertainty) { "Quad edges must span at least one source pixel" }
            require(orientation(a, b, c) > crossUncertainty) { "Quad cycle must have unambiguously positive turns" }
        }
        // Translate the shoelace sum to TL before reducing: a small quad at a large source offset must not lose its area by subtracting nearly equal absolute-coordinate products.
        val twiceArea = orientation(points[0], points[1], points[2]) + orientation(points[0], points[2], points[3])
        require(twiceArea > 2.0) { "Quad area must exceed one square source pixel" }
        require(!intersects(points[0], points[1], points[2], points[3], lengthUncertainty, crossUncertainty) && !intersects(points[1], points[2], points[3], points[0], lengthUncertainty, crossUncertainty)) { "Nonadjacent quad edges must not cross or touch" }
    }

    private fun orientation(a: PointPx, b: PointPx, c: PointPx): Double = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun onSegment(a: PointPx, b: PointPx, p: PointPx, uncertainty: Double): Boolean =
        p.x >= min(a.x, b.x) - uncertainty && p.x <= max(a.x, b.x) + uncertainty && p.y >= min(a.y, b.y) - uncertainty && p.y <= max(a.y, b.y) + uncertainty

    private fun intersects(a: PointPx, b: PointPx, c: PointPx, d: PointPx, lengthUncertainty: Double, crossUncertainty: Double): Boolean {
        val ac = orientation(a, b, c)
        val ad = orientation(a, b, d)
        val ca = orientation(c, d, a)
        val cb = orientation(c, d, b)
        if ((abs(ac) <= crossUncertainty && onSegment(a, b, c, lengthUncertainty)) || (abs(ad) <= crossUncertainty && onSegment(a, b, d, lengthUncertainty)) || (abs(ca) <= crossUncertainty && onSegment(c, d, a, lengthUncertainty)) || (abs(cb) <= crossUncertainty && onSegment(c, d, b, lengthUncertainty))) return true
        require(abs(ac) > crossUncertainty && abs(ad) > crossUncertainty && abs(ca) > crossUncertainty && abs(cb) > crossUncertainty) { "Uncertain nonadjacent edge relation" }
        return (ac > 0.0) != (ad > 0.0) && (ca > 0.0) != (cb > 0.0)
    }
}
