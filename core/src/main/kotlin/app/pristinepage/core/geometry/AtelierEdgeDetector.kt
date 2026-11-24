package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Conservative preview-scale detector for a page against a contrasting surface. It proposes a quad, not an accepted inverse map or a calibrated confidence estimate. */
object AtelierEdgeDetector {
    private const val MAX_EDGE = 384
    private const val MIN_AXIS = 24
    private const val MIN_CONTRAST = 30
    private const val REGION_CONTRAST = 13

    /** Conservative shared-ledger charge for a bounded preview analysis, not a total process heap estimate. */
    fun workspaceBytes(): Long = 4L * 1024 * 1024

    @JvmStatic @JvmOverloads fun detect(width: Int, height: Int, checkpoint: () -> Unit = {}, luminance: (Int, Int) -> Int): QuadPx? {
        if (width !in MIN_AXIS..MAX_EDGE || height !in MIN_AXIS..MAX_EDGE) return null
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) pixels[y * width + x] = luminance(x, y)
        }
        require(pixels.all { it in 0..255 })
        val lines = lineProposals(width, height, pixels, checkpoint)
        val choices = lines.ifEmpty { regions(AtelierRegionOutline.Workspace(width, height), width, height, pixels, checkpoint) }
        return choices.singleOrNull()?.let { normalize(it, width, height) }
    }

    /** Preserve the luminance-first policy. When no luminance contour is supported, compare two color difference planes. */
    @JvmStatic @JvmOverloads fun detectRgb(width: Int, height: Int, checkpoint: () -> Unit = {}, rgb: (Int, Int) -> Int): QuadPx? {
        if (width !in MIN_AXIS..MAX_EDGE || height !in MIN_AXIS..MAX_EDGE) return null
        val packed = IntArray(width * height)
        val samples = IntArray(packed.size)
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) {
                val index = y * width + x
                val v = rgb(x, y)
                packed[index] = v
                val r = (v ushr 16) and 255
                val g = (v ushr 8) and 255
                val b = v and 255
                samples[index] = (77 * r + 150 * g + 29 * b + 128) ushr 8
            }
        }
        val lines = lineProposals(width, height, samples, checkpoint)
        if (lines.isNotEmpty()) return lines.singleOrNull()?.let { normalize(it, width, height) }
        val workspace = AtelierRegionOutline.Workspace(width, height)
        val lumaRegions = regions(workspace, width, height, samples, checkpoint)
        if (lumaRegions.size > 1) return null
        var predicted = lumaRegions.singleOrNull()
        for (shift in intArrayOf(16, 0)) {
            var low = 255
            var high = 0
            for (i in packed.indices) {
                if (i % width == 0) checkpoint()
                val v = packed[i]
                // Scaling keeps both differences in the existing 0..255 detector domain without clipping.
                val value = (((v ushr shift) and 255) - ((v ushr 8) and 255) + 256) ushr 1
                samples[i] = value
                low = minOf(low, value)
                high = maxOf(high, value)
            }
            if (high - low < REGION_CONTRAST) continue
            val choices = regions(workspace, width, height, samples, checkpoint)
            if (choices.size > 1) return null
            val next = choices.singleOrNull() ?: continue
            if (predicted != null && !AtelierRegionOutline.agrees(predicted, next, 4.0)) return null
            if (predicted == null) predicted = next
        }
        return predicted?.let { normalize(it, width, height) }
    }

    private fun regions(workspace: AtelierRegionOutline.Workspace, width: Int, height: Int, pixels: IntArray,
        checkpoint: () -> Unit): List<QuadPx> =
        workspace.proposals(pixels, checkpoint) { quad, sign ->
            separatesPage(quad, width, height, pixels, sign, allowIsolatedInk = true)
        }

    private fun lineProposals(width: Int, height: Int, pixels: IntArray, checkpoint: () -> Unit): List<QuadPx> {
        val rows = { u: Int, v: Int -> pixels[u * width + v] }
        val columns = { u: Int, v: Int -> pixels[v * width + u] }
        // Keep the quick high-contrast fit only when all four sides also separate the same inside/outside polarity.
        val quick = quad(oppositeEdges(height, width, rows), oppositeEdges(width, height, columns), width, height)
        if (quick != null && separatesPage(quick, width, height, pixels)) return listOf(quick)
        val vertical = regionalEdges(height, width, checkpoint, rows)
        val horizontal = regionalEdges(width, height, checkpoint, columns)
        // A bright sheet and a dark sheet are both possible. Do not silently pick a polarity if both fit.
        val regional = listOf(-1, 1).mapNotNull { sign ->
            quad(vertical[sign], horizontal[sign], width, height)?.takeIf { separatesPage(it, width, height, pixels, sign) }
        }
        if (regional.isNotEmpty()) return regional
        val upright = narrowReceipt(width, height, rows, columns, checkpoint)
        val sideways = narrowReceipt(height, width, columns, rows, checkpoint)?.let {
            fun transpose(p: PointPx) = PointPx(p.y, p.x)
            QuadPx(transpose(it.tl), transpose(it.bl), transpose(it.br), transpose(it.tr))
        }
        // An ambiguous result is not a safe default. Never invent end caps or extrapolate them past the preview.
        return listOfNotNull(upright, sideways).filter { separatesPage(it, width, height, pixels) }
    }

    private fun normalize(detected: QuadPx, width: Int, height: Int): QuadPx {
        fun normalized(point: PointPx) = PointPx(point.x / (width - 1), point.y / (height - 1))
        return QuadPx(normalized(detected.tl), normalized(detected.tr), normalized(detected.br), normalized(detected.bl))
    }

    private fun quad(vertical: Pair<Line, Line>?, horizontal: Pair<Line, Line>?, width: Int, height: Int, minWidth: Double = .24, minHeight: Double = .24): QuadPx? {
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
        if (minOf(quad.tr.x - quad.tl.x, quad.br.x - quad.bl.x) < w * minWidth ||
            minOf(quad.bl.y - quad.tl.y, quad.br.y - quad.tr.y) < h * minHeight) return null
        return quad
    }

    private fun narrowReceipt(width: Int, height: Int, rows: (Int, Int) -> Int, columns: (Int, Int) -> Int, checkpoint: () -> Unit): QuadPx? {
        val middle = height / 2
        val minWidth = maxOf(16, width / 12)
        val widthRange = minWidth.toDouble()..(.30 * width)
        val sides = scanPair(height / 4..height * 3 / 4, width, height * 3 / 10, minWidth, false, rows, checkpoint) ?: return null
        val (left, right) = sides
        fun Line.at(y: Int) = slope * y + intercept
        val lo = height / 4
        val hi = height * 3 / 4
        if (listOf(lo, middle, hi).any { right.at(it) - left.at(it) !in widthRange }) return null
        // Only trace caps through the central interior common to the two fitted sides, not unrelated columns.
        val start = ceil(maxOf(left.at(lo), left.at(hi)) + 3).toInt().coerceAtLeast(0)
        val end = floor(minOf(right.at(lo), right.at(hi)) - 3).toInt().coerceAtMost(width - 1)
        val count = end - start + 1
        if (count < 8) return null
        val caps = scanPair(start..end, height, maxOf(8, (3 * count + 3) / 4), height * 2 / 5, false, columns, checkpoint) ?: return null
        val candidate = quad(sides, caps, width, height, minWidth = minWidth.toDouble() / width, minHeight = .40) ?: return null
        val widestEnd = maxOf(hypot(candidate.tr.x - candidate.tl.x, candidate.tr.y - candidate.tl.y),
            hypot(candidate.br.x - candidate.bl.x, candidate.br.y - candidate.bl.y))
        val shorterSide = minOf(hypot(candidate.tl.x - candidate.bl.x, candidate.tl.y - candidate.bl.y),
            hypot(candidate.tr.x - candidate.br.x, candidate.tr.y - candidate.br.y))
        if (widestEnd !in widthRange || shorterSide < 1.8 * widestEnd) return null
        fun capContinuous(line: Line): Boolean {
            var unsupported = 0
            for (x in start..end) {
                val y = line.at(x)
                val a = (y - 2).roundToInt()
                val b = (y + 2).roundToInt()
                val supported = a >= 0 && b < height && abs(columns(x, a) - columns(x, b)) >= REGION_CONTRAST
                unsupported = if (supported) 0 else unsupported + 1
                // Outermost transitions can enclose two separate strips. Both ends must be continuous across the fitted interior.
                if (unsupported >= 3) return false
            }
            return true
        }
        fun sidesContinue(outsideRows: IntRange): Boolean {
            var run = 0
            for (y in outsideRows) {
                checkpoint()
                val a = left.at(y)
                val b = right.at(y)
                fun contrast(x: Double, inward: Int): Int {
                    val inside = (x + 2 * inward).roundToInt()
                    val outside = (x - 2 * inward).roundToInt()
                    return if (inside in 0 until width && outside in 0 until width) rows(y, inside) - rows(y, outside) else 0
                }
                val l = contrast(a, 1)
                val r = contrast(b, -1)
                val supported = b - a >= minWidth && abs(l) >= REGION_CONTRAST && abs(r) >= REGION_CONTRAST && l * r > 0
                run = if (supported) run + 1 else 0
                // A shadow across the receipt can look like an end locally, but cannot terminate both visible side edges.
                if (run >= 4) return true
            }
            return false
        }
        val top = floor(minOf(candidate.tl.y, candidate.tr.y)).toInt()
        val bottom = ceil(maxOf(candidate.bl.y, candidate.br.y)).toInt()
        if (!capContinuous(caps.first) || !capContinuous(caps.second) ||
            sidesContinue(maxOf(0, top - 16)..top - 3) ||
            sidesContinue(bottom + 3..minOf(height - 1, bottom + 16))) return null
        return candidate
    }

    private fun separatesPage(quad: QuadPx, width: Int, height: Int, pixels: IntArray, requiredSign: Int? = null,
        allowIsolatedInk: Boolean = false): Boolean {
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
                    val gaps = if (allowIsolatedInk && available >= 5) 1 else 0
                    return available >= 2 && if (strict) supported >= available - gaps else supported >= 2
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

    private fun oppositeEdges(scanlines: Int, axis: Int, get: (Int, Int) -> Int): Pair<Line, Line>? =
        scanPair(scanlines / 4..scanlines * 3 / 4, axis, maxOf(8, scanlines * 3 / 10), axis / 4, true, get) {}

    private fun scanPair(scans: IntRange, axis: Int, minSupport: Int, minGap: Int, straddleCenter: Boolean,
        get: (Int, Int) -> Int, checkpoint: () -> Unit): Pair<Line, Line>? {
        val first = ArrayList<PointPx>()
        val last = ArrayList<PointPx>()
        // Avoid fitting the end caps as side edges; a heavily occluded or oblique contour returns no proposal.
        for (u in scans) {
            checkpoint()
            var near = -1
            var far = -1
            for (v in 2 until axis - 1) if (abs(get(u, v) - get(u, v - 1)) >= MIN_CONTRAST) {
                if (near == -1) near = v
                far = v
            }
            if (near < 0 || far - near < minGap || (straddleCenter && (near >= axis / 2 || far <= axis / 2))) continue
            if ((get(u, near) - get(u, near - 1)) * (get(u, far) - get(u, far - 1)) >= 0) continue
            first += PointPx(u.toDouble(), near - .5)
            last += PointPx(u.toDouble(), far - .5)
        }
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
