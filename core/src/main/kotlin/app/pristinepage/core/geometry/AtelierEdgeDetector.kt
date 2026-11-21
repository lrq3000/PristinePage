package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Conservative preview-scale detector for a page against a contrasting surface. It proposes a quad, not an accepted inverse map or a calibrated confidence estimate. */
object AtelierEdgeDetector {
    private const val MAX_EDGE = 384
    private const val MIN_AXIS = 24
    private const val MIN_CONTRAST = 30
    private const val REGION_CONTRAST = 13

    @JvmStatic @JvmOverloads fun detect(width: Int, height: Int, checkpoint: () -> Unit = {}, luminance: (Int, Int) -> Int): QuadPx? {
        if (width !in MIN_AXIS..MAX_EDGE || height !in MIN_AXIS..MAX_EDGE) return null
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) pixels[y * width + x] = luminance(x, y)
        }
        require(pixels.all { it in 0..255 })
        val rows = { u: Int, v: Int -> pixels[u * width + v] }
        val columns = { u: Int, v: Int -> pixels[v * width + u] }
        // Keep the quick high-contrast fit only when all four sides also separate the same inside/outside polarity.
        val quick = quad(oppositeEdges(height, width, rows), oppositeEdges(width, height, columns), width, height)
        val detected = if (quick != null && separatesPage(quick, width, height, pixels)) quick else {
            val vertical = regionalEdges(height, width, checkpoint, rows)
            val horizontal = regionalEdges(width, height, checkpoint, columns)
            // A bright sheet and a dark sheet are both possible. Do not silently pick a polarity if both fit.
            listOf(-1, 1).mapNotNull { sign ->
                quad(vertical[sign], horizontal[sign], width, height)?.takeIf { separatesPage(it, width, height, pixels, sign) }
            }.singleOrNull()
        } ?: return null
        fun normalized(point: PointPx) = PointPx(point.x / (width - 1), point.y / (height - 1))
        return QuadPx(normalized(detected.tl), normalized(detected.tr), normalized(detected.br), normalized(detected.bl))
    }

    private fun quad(vertical: Pair<Line, Line>?, horizontal: Pair<Line, Line>?, width: Int, height: Int): QuadPx? {
        val (left, right) = vertical ?: return null
        val (top, bottom) = horizontal ?: return null
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
        return quad
    }

    private fun separatesPage(quad: QuadPx, width: Int, height: Int, pixels: IntArray, requiredSign: Int? = null): Boolean {
        val points = quad.points()
        fun sample(x: Double, y: Double): Int? {
            val ix = x.roundToInt()
            val iy = y.roundToInt()
            return if (ix in 0 until width && iy in 0 until height) pixels[iy * width + ix] else null
        }
        var polarity = requiredSign
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            // The cycle is clockwise in image coordinates, so the inward normal is (-dy, dx).
            val nx = -dy / length
            val ny = dx / length
            var bright = 0
            var dark = 0
            for (k in 1..19) {
                val t = .10 + .04 * k
                val x = a.x + dx * t
                val y = a.y + dy * t
                fun band(first: Int, last: Int, sign: Int, strict: Boolean): Boolean {
                    var available = 0
                    var supported = 0
                    for (r in first..last) {
                        val inside = sample(x + nx * r, y + ny * r) ?: continue
                        val outside = sample(x - nx * r, y - ny * r) ?: continue
                        available++
                        if (sign * (inside - outside) >= REGION_CONTRAST) supported++
                    }
                    return available >= 2 && if (strict) supported == available else supported >= 2
                }
                // For tight framing two real outer-band samples suffice; no samples are invented outside the source.
                if (band(1, 3, 1, false) && band(2, 7, 1, true)) bright++
                if (band(1, 3, -1, false) && band(2, 7, -1, true)) dark++
            }
            val edgeSign = when {
                bright >= 14 -> 1
                dark >= 14 -> -1
                else -> return false
            }
            if (polarity != null && polarity != edgeSign) return false
            polarity = edgeSign
        }
        return true
    }

    private fun regionalEdges(scanlines: Int, axis: Int, checkpoint: () -> Unit, get: (Int, Int) -> Int): Map<Int, Pair<Line, Line>> {
        val lo = scanlines / 4
        val hi = scanlines * 3 / 4
        val leftBright = Array(hi - lo + 1) { IntArray(0) }
        val leftDark = Array(hi - lo + 1) { IntArray(0) }
        val rightBright = Array(hi - lo + 1) { IntArray(0) }
        val rightDark = Array(hi - lo + 1) { IntArray(0) }
        for (u in lo..hi) {
            checkpoint()
            val sum = IntArray(axis + 1)
            for (v in 0 until axis) sum[v + 1] = sum[v] + get(u, v)
            fun candidates(first: Int, last: Int, sign: Int): IntArray {
                val peaks = ArrayList<Int>()
                var v = first
                while (v <= last) {
                    fun score(at: Int): Int {
                        val near = sum[at + 4] - 2 * sum[at] + sum[at - 4]
                        val far = sum[at + 8] - sum[at + 4] - sum[at - 4] + sum[at - 8]
                        // A stripe can straddle the join between averaging bands and make both means look valid.
                        // Demand sustained far support, not one dark sample in a thin line.
                        if (sign * far < 4 * REGION_CONTRAST || sign * near < 4 * REGION_CONTRAST) return 0
                        for (r in 2..7) if (sign * (get(u, at + r) - get(u, at - 1 - r)) < REGION_CONTRAST) return 0
                        return sign * near
                    }
                    var best = 4 * REGION_CONTRAST - 1
                    var left = v
                    var right = v
                    while (v <= last) {
                        val strength = score(v)
                        if (strength < 4 * REGION_CONTRAST) break
                        if (strength > best) { best = strength; left = v; right = v }
                        else if (strength == best) right = v
                        v++
                    }
                    if (best >= 4 * REGION_CONTRAST) peaks += (left + right) / 2
                    v++
                }
                return peaks.toIntArray()
            }
            leftBright[u - lo] = candidates(8, axis / 2 - 1, 1)
            leftDark[u - lo] = candidates(8, axis / 2 - 1, -1)
            rightBright[u - lo] = candidates(axis / 2 + 1, axis - 8, -1)
            rightDark[u - lo] = candidates(axis / 2 + 1, axis - 8, 1)
        }
        val minSupport = maxOf(8, scanlines * 3 / 10)
        fun pair(left: Array<IntArray>, right: Array<IntArray>): Pair<Line, Line>? {
            val first = votingLine(left, lo, axis, minSupport, checkpoint) ?: return null
            val last = votingLine(right, lo, axis, minSupport, checkpoint) ?: return null
            return first to last
        }
        return buildMap {
            pair(leftBright, rightBright)?.let { put(1, it) }
            pair(leftDark, rightDark)?.let { put(-1, it) }
        }
    }

    private fun votingLine(peaks: Array<IntArray>, start: Int, axis: Int, minSupport: Int, checkpoint: () -> Unit): Line? {
        val center = peaks.size / 2
        // Intercept bins are evaluated at the center scanline. Stamp each bin once per row, even when several text strokes vote for it.
        val padding = MAX_EDGE
        val votes = IntArray(axis + 2 * padding)
        val lastRow = IntArray(votes.size)
        var bestCount = 0
        var bestSlope = 0.0
        var bestCenter = 0
        for (k in 0..44) {
            checkpoint()
            val slope = (if (k % 2 == 0) -1 else 1) * ((k + 1) / 2) / 25.0
            votes.fill(0)
            lastRow.fill(-1)
            for (row in peaks.indices) for (x in peaks[row]) {
                val bin = (x - slope * (row - center)).roundToInt() + padding
                for (j in bin - 1..bin + 1) if (lastRow[j] != row) { lastRow[j] = row; votes[j]++ }
            }
            for (bin in votes.indices) if (votes[bin] > bestCount) {
                bestCount = votes[bin]
                bestSlope = slope
                bestCenter = bin - padding
            }
        }
        if (bestCount < minSupport) return null
        val inliers = ArrayList<PointPx>(peaks.size)
        for (row in peaks.indices) {
            val expected = bestCenter + bestSlope * (row - center)
            val best = peaks[row].minByOrNull { abs(it - expected) } ?: continue
            if (abs(best - expected) <= 2.0) inliers += PointPx((start + row).toDouble(), best - .5)
        }
        return refit(inliers, minSupport)
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
        return refit(best, minSupport)
    }

    private fun refit(best: List<PointPx>, minSupport: Int): Line? {
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
