package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import kotlin.math.abs
import kotlin.math.hypot

/** Conservative preview-scale detector for a page against a contrasting surface. It proposes a quad, not an accepted inverse map or a calibrated confidence estimate. */
object AtelierEdgeDetector {
    private const val MAX_EDGE = 384
    private const val MIN_AXIS = 24
    private const val MIN_CONTRAST = 30

    @JvmStatic fun detect(width: Int, height: Int, luminance: (Int, Int) -> Int): QuadPx? {
        if (width !in MIN_AXIS..MAX_EDGE || height !in MIN_AXIS..MAX_EDGE) return null
        val pixels = IntArray(width * height) { luminance(it % width, it / width) }
        require(pixels.all { it in 0..255 })
        // Interior text strokes are not a silhouette. Use the outermost supported transition on opposite sides of center and fit separate lines, not an axis-aligned bounding box.
        val (left, right) = oppositeEdges(height, width) { u, v -> pixels[u * width + v] } ?: return null
        val (top, bottom) = oppositeEdges(width, height) { u, v -> pixels[v * width + u] } ?: return null
        val quad = QuadPx(
            crossing(left, top) ?: return null, crossing(right, top) ?: return null,
            crossing(right, bottom) ?: return null, crossing(left, bottom) ?: return null,
        )
        try { QuadValidation.validate(quad, PixelSize(width, height)) }
        catch (_: IllegalArgumentException) { return null }
        val w = (width - 1).toDouble()
        val h = (height - 1).toDouble()
        if (minOf(quad.tr.x - quad.tl.x, quad.br.x - quad.bl.x) < w * .24 ||
            minOf(quad.bl.y - quad.tl.y, quad.br.y - quad.tr.y) < h * .24) return null
        fun normalized(point: PointPx) = PointPx(point.x / w, point.y / h)
        return QuadPx(normalized(quad.tl), normalized(quad.tr), normalized(quad.br), normalized(quad.bl))
    }

    private fun oppositeEdges(scanlines: Int, axis: Int, get: (Int, Int) -> Int): Pair<Line, Line>? {
        val first = ArrayList<PointPx>(scanlines)
        val last = ArrayList<PointPx>(scanlines)
        // Avoid fitting the end caps as side edges; a heavily occluded or oblique contour returns no proposal.
        for (u in (scanlines / 4)..(scanlines * 3 / 4)) {
            var near = -1
            var far = -1
            for (v in 2 until axis - 1) if (abs(get(u, v) - get(u, v - 1)) >= MIN_CONTRAST) {
                if (near == -1) near = v
                far = v
            }
            if (near < 0 || far - near < axis / 4 || near >= axis / 2 || far <= axis / 2) continue
            if ((get(u, near) - get(u, near - 1)) * (get(u, far) - get(u, far - 1)) >= 0) continue
            first += PointPx(u.toDouble(), near - .5)
            last += PointPx(u.toDouble(), far - .5)
        }
        val minSupport = maxOf(8, scanlines * 3 / 10)
        val near = supportingLine(first, minSupport) ?: return null
        val far = supportingLine(last, minSupport) ?: return null
        return near to far
    }

    private data class Line(val slope: Double, val intercept: Double) {
        fun residual(point: PointPx): Double = abs(slope * point.x + intercept - point.y) / hypot(1.0, slope)
    }

    private fun supportingLine(points: List<PointPx>, minSupport: Int): Line? {
        if (points.size < minSupport) return null
        // Pairs of fixed quantile witnesses bound work regardless of image content. Select the greatest support rather than allowing one prominent stray line to drag the whole fit.
        val witnesses = (0..8).map { points[it * points.lastIndex / 8] }
        var best: List<PointPx> = emptyList()
        var error = Double.POSITIVE_INFINITY
        for (i in witnesses.indices) for (j in i + 2 until witnesses.size) {
            val a = witnesses[i]
            val b = witnesses[j]
            val slope = (b.y - a.y) / (b.x - a.x)
            if (abs(slope) > .9) continue
            val line = Line(slope, a.y - slope * a.x)
            val inliers = points.filter { line.residual(it) <= 1.75 }
            val sum = inliers.sumOf(line::residual)
            if (inliers.size > best.size || (inliers.size == best.size && sum < error)) {
                best = inliers
                error = sum
            }
        }
        if (best.size < minSupport || best.last().x - best.first().x < minSupport - 1) return null
        val meanX = best.sumOf { it.x } / best.size
        val meanY = best.sumOf { it.y } / best.size
        val slope = best.sumOf { (it.x - meanX) * (it.y - meanY) } / best.sumOf { (it.x - meanX) * (it.x - meanX) }
        return Line(slope, meanY - slope * meanX)
    }

    private fun crossing(side: Line, cap: Line): PointPx? {
        val denominator = 1 - side.slope * cap.slope
        if (abs(denominator) < .2) return null
        val x = (side.intercept + side.slope * cap.intercept) / denominator
        return PointPx(x, cap.slope * x + cap.intercept)
    }
}
