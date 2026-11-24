package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Last resort for a dominant print page whose edges cross surfaces with different colors or polarities. */
internal object AtelierBoundaryVotes {
    private const val MIN_STEP = 12
    private const val MAX_LINES = 3
    private const val MIN_SAMPLES = 19

    private data class Line(val slope: Double, val offset: Double, val votes: Int) {
        fun at(scan: Double) = slope * scan + offset
    }

    private data class Candidate(val quad: QuadPx, val score: Int, val area: Double)

    fun detect(w: Int, h: Int, rgb: IntArray, checkpoint: () -> Unit): QuadPx? {
        val vertical = Axis(w, h, rgb, transpose = false)
        val horizontal = Axis(h, w, rgb, transpose = true)
        val (left, right) = vertical.lines(checkpoint)
        val (top, bottom) = horizontal.lines(checkpoint)
        val accepted = ArrayList<Candidate>(MAX_LINES * MAX_LINES * MAX_LINES * MAX_LINES)
        for (l in left) for (r in right) for (t in top) for (b in bottom) {
            checkpoint()
            val initialTl = cross(l, t)
            val initialTr = cross(r, t)
            val initialBr = cross(r, b)
            val initialBl = cross(l, b)
            val lv = vertical.refine(l, initialTl.y, initialBl.y, near = true)
            val rv = vertical.refine(r, initialTr.y, initialBr.y, near = false)
            val th = horizontal.refine(t, initialTl.x, initialTr.x, near = true)
            val bh = horizontal.refine(b, initialBl.x, initialBr.x, near = false)
            val candidate = QuadPx(cross(lv, th), cross(rv, th), cross(rv, bh), cross(lv, bh))
            val (tl, tr, br, bl) = candidate.points()
            // This fallback is for a dominant, roughly axis-aligned page, not an invented cap on a receipt or an extreme warp.
            if (minOf(tr.x - tl.x, br.x - bl.x) < w * .34 || minOf(bl.y - tl.y, br.y - tr.y) < h * .34) continue
            if (candidate.points().any { it.x !in 3.0..(w - 4.0) || it.y !in 3.0..(h - 4.0) }) continue
            try { QuadValidation.validate(candidate, PixelSize(w, h)) } catch (_: IllegalArgumentException) { continue }
            val scores = listOf(vertical.support(lv, tl.y, bl.y), vertical.support(rv, tr.y, br.y),
                horizontal.support(th, tl.x, tr.x), horizontal.support(bh, bl.x, br.x))
            if (scores.any { it == 0 }) continue
            if (vertical.continues(lv, tl.y, rv, tr.y, -1) || vertical.continues(lv, bl.y, rv, br.y, 1) ||
                horizontal.continues(th, tl.x, bh, bl.x, -1) || horizontal.continues(th, tr.x, bh, br.x, 1)) continue
            if (vertical.reachesFrame(lv, tl.y, -1) || vertical.reachesFrame(lv, bl.y, 1) ||
                vertical.reachesFrame(rv, tr.y, -1) || vertical.reachesFrame(rv, br.y, 1) ||
                horizontal.reachesFrame(th, tl.x, -1) || horizontal.reachesFrame(th, tr.x, 1) ||
                horizontal.reachesFrame(bh, bl.x, -1) || horizontal.reachesFrame(bh, br.x, 1)) continue
            val points = candidate.points()
            val area = points.indices.sumOf { i ->
                val p = points[i]
                val q = points[(i + 1) % points.size]
                p.x * q.y - p.y * q.x
            } * .5
            accepted += Candidate(candidate, scores.sum(), area)
        }
        val best = accepted.maxOfOrNull { it.score } ?: return null
        // Weak runner-up lines occur at ruling, trim or binding seams. Treat only nearly tied verified
        // candidates as peers; margin preservation must not let a weak hypothesis beat a supported edge.
        val plausible = accepted.filter { it.score * 100 >= best * 98 }
        // Prefer preserving the margin of a single outline. Distinct outlines remain ambiguous even if one is larger.
        val largest = plausible.maxBy { it.area }
        val widest = largest.quad
        // A lower contrast enclosing boundary can be the actual paper around a high-contrast printed block.
        // Require material area loss (not adjacent paper trim), but permit a shared edge within fit tolerance.
        if (accepted.any { it.area >= largest.area * 1.20 && encloses(it.quad, widest) }) return null
        if (plausible.any { other ->
                widest.points().zip(other.quad.points()).any { (a, b) -> hypot(a.x - b.x, a.y - b.y) > 12 }
            }) return null
        return widest
    }

    private fun encloses(outer: QuadPx, inner: QuadPx): Boolean {
        val points = outer.points()
        return inner.points().all { p ->
            points.indices.all { i ->
                val a = points[i]
                val b = points[(i + 1) % points.size]
                val dx = b.x - a.x
                val dy = b.y - a.y
                dx * (p.y - a.y) - dy * (p.x - a.x) >= -2 * hypot(dx, dy)
            }
        }
    }

    private fun cross(vertical: Line, horizontal: Line): PointPx {
        // Every admitted slope is <= .24, so the cross term cannot make the intersection singular.
        val x = (vertical.offset + vertical.slope * horizontal.offset) / (1 - vertical.slope * horizontal.slope)
        return PointPx(x, horizontal.at(x))
    }

    /** Compare signed pixel differences within each channel; different sides need not share a polarity. */
    private class Axis(private val extent: Int, private val scans: Int, private val rgb: IntArray, private val transpose: Boolean) {
        private fun pixel(scan: Int, u: Int): Int = rgb[if (transpose) u * scans + scan else scan * extent + u]

        private fun band(a: Int, b: Int): Int {
            val r = (a ushr 16 and 255) - (b ushr 16 and 255)
            val g = (a ushr 8 and 255) - (b ushr 8 and 255)
            val blue = (a and 255) - (b and 255)
            val luma = (77 * r + 150 * g + 29 * blue) / 256
            return if (abs(luma) >= MIN_STEP || abs(blue - r) >= 2 * MIN_STEP || abs(r - g) >= 2 * MIN_STEP) {
                maxOf(abs(luma), abs(blue - r) / 2, abs(r - g) / 2)
            } else 0
        }

        private fun response(scan: Int, at: Int): Int {
            if (scan !in 0 until scans || at !in 3 until extent - 2) return 0
            // A true step must survive on both sides of the raster boundary. Do not vote for a lone stroke.
            val near = band(pixel(scan, at + 1), pixel(scan, at - 2))
            val far = band(pixel(scan, at + 2), pixel(scan, at - 3))
            val center = band(pixel(scan, at), pixel(scan, at - 1))
            return if (near > 0 && far > 0 && center > 0) 2 * center + minOf(near, far, 40) else 0
        }

        fun lines(checkpoint: () -> Unit): Pair<List<Line>, List<Line>> {
            val start = scans / 4
            val stop = scans * 3 / 4
            val peaks = Array(stop - start + 1) { index ->
                checkpoint()
                val scores = IntArray(extent)
                for (x in 3 until extent - 2) scores[x] = response(start + index, x)
                val line = IntArray(extent / 2)
                var n = 0
                for (x in 3 until extent - 2) if (scores[x] > scores[x - 1] && scores[x] >= scores[x + 1]) line[n++] = x
                line.copyOf(n)
            }
            return vote(3, extent / 2 - 1, peaks, start, stop, checkpoint) to
                vote(extent / 2, extent - 3, peaks, start, stop, checkpoint)
        }

        private fun vote(first: Int, last: Int, peaks: Array<IntArray>, start: Int, stop: Int, checkpoint: () -> Unit): List<Line> {
            val center = (start + stop) / 2
            val candidates = ArrayList<Line>()
            val counts = IntArray(extent)
            val stamps = IntArray(extent)
            for (step in -12..12) {
                checkpoint()
                val slope = step / 50.0
                counts.fill(0)
                stamps.fill(-1)
                for (row in peaks.indices) for (peak in peaks[row]) {
                    if (peak !in first..last) continue
                    val bin = (peak - slope * (start + row - center)).roundToInt()
                    for (j in maxOf(first, bin - 1)..minOf(last, bin + 1)) if (stamps[j] != row) {
                        counts[j]++
                        stamps[j] = row
                    }
                }
                for (j in first..last) if (2 * counts[j] >= peaks.size &&
                    (j == first || counts[j] > counts[j - 1]) && (j == last || counts[j] >= counts[j + 1])) {
                    candidates += Line(slope, j - .5 - slope * center, counts[j])
                }
            }
            val selected = ArrayList<Line>(MAX_LINES)
            for (line in candidates.sortedByDescending { it.votes }) {
                if (selected.any { abs(it.at(start.toDouble()) - line.at(start.toDouble())) <= 2 &&
                        abs(it.at(stop.toDouble()) - line.at(stop.toDouble())) <= 2 }) continue
                selected += line
                if (selected.size == MAX_LINES) break
            }
            return selected
        }

        fun support(line: Line, start: Double, stop: Double): Int {
            var strong = 0
            var sum = 0
            var gap = 0
            for (i in 1..MIN_SAMPLES) {
                val scan = start + (stop - start) * i / (MIN_SAMPLES + 1)
                val at = (line.at(scan) + .5).roundToInt()
                val signal = maxOf(response(scan.roundToInt(), at), response(scan.roundToInt(), at - 1), response(scan.roundToInt(), at + 1))
                gap = if (signal > 0) 0 else gap + 1
                if (gap >= 4) return 0
                if (signal > 0) strong++
                sum += minOf(signal, 100)
            }
            return if (strong >= 15) sum else 0
        }

        fun refine(line: Line, start: Double, stop: Double, near: Boolean): Line {
            var count = 0
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for (i in 1..MIN_SAMPLES) {
                val scan = (start + (stop - start) * i / (MIN_SAMPLES + 1)).roundToInt().toDouble()
                val expected = (line.at(scan) + .5).roundToInt()
                // Prefer the outside of a short staircase, binding or stack to the equally strong printed seam.
                val range = if (near) -3..2 else 3 downTo -2
                var peak = 0
                var where = 0
                for (d in range) {
                    val strength = response(scan.toInt(), expected + d)
                    if (strength <= peak) continue
                    peak = strength
                    where = expected + d
                }
                if (peak == 0) continue
                count++
                sx += scan
                sy += where - .5
                sxx += scan * scan
                sxy += scan * (where - .5)
            }
            val denom = count * sxx - sx * sx
            if (count < 12 || denom <= 0) return line
            val slope = (count * sxy - sx * sy) / denom
            return if (abs(slope) <= .24) Line(slope, (sy - slope * sx) / count, line.votes) else line
        }

        private fun supported(line: Line, scan: Double): Boolean {
            val at = (line.at(scan) + .5).roundToInt()
            return (-1..1).any { response(scan.roundToInt(), at + it) != 0 }
        }

        fun continues(first: Line, firstEnd: Double, second: Line, secondEnd: Double, dir: Int): Boolean {
            var run = 0
            for (d in 3..12) {
                run = if (supported(first, firstEnd + d * dir) && supported(second, secondEnd + d * dir)) run + 1 else 0
                if (run >= 4) return true
            }
            return false
        }

        fun reachesFrame(line: Line, from: Double, dir: Int): Boolean {
            val limit = if (dir < 0) 1 else scans - 2
            val first = from.roundToInt() + 3 * dir
            val count = (limit - first) * dir + 1
            // Only a sustained edge reaching the image boundary qualifies. A nearby texture crossing or
            // the narrow extension at the folded corner of a fully visible page does not.
            if (count < 8) return false
            val firstEvidence = supported(line, first.toDouble()) || supported(line, (first + dir).toDouble())
            val lastEvidence = supported(line, limit.toDouble()) || supported(line, (limit - dir).toDouble())
            if (!firstEvidence || !lastEvidence) return false
            var hits = 0
            var gap = 0
            for (i in 0 until count) {
                if (supported(line, (first + i * dir).toDouble())) {
                    hits++
                    gap = 0
                } else if (++gap >= 3) return false
            }
            return hits * 4 >= count * 3
        }
    }
}
