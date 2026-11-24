package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A filled-region proposal, independent of the position and direction of the scanner's line sweeps. */
internal object AtelierRegionOutline {
    class Workspace(private val width: Int, private val height: Int) {
        private val queue = IntArray(width * height)
        private val visited = BooleanArray(queue.size)
        private val stamp = IntArray(height)
        private val low = IntArray(height)
        private val high = IntArray(height)

        fun proposals(pixels: IntArray, checkpoint: () -> Unit, separatesPage: (QuadPx, Int) -> Boolean): List<QuadPx> =
            trace(pixels, width, height, queue, visited, stamp, low, high, checkpoint, separatesPage)
    }

    private fun trace(pixels: IntArray, width: Int, height: Int, queue: IntArray, visited: BooleanArray,
        stamp: IntArray, low: IntArray, high: IntArray, checkpoint: () -> Unit,
        separatesPage: (QuadPx, Int) -> Boolean): List<QuadPx> {
        val threshold = otsu(pixels, checkpoint) ?: return emptyList()
        val candidates = ArrayList<QuadPx>(2)
        for (cut in listOf(threshold, threshold - 12, threshold + 12).map { it.coerceIn(0, 254) }.distinct()) {
            for (sign in intArrayOf(1, -1)) {
                checkpoint()
                visited.fill(false)
                stamp.fill(0)
                val minArea = (pixels.size * 7 + 99) / 100
                for (seed in pixels.indices) {
                    if (seed % width == 0) checkpoint()
                    fun admit(i: Int) = !visited[i] && if (sign == 1) pixels[i] > cut else pixels[i] <= cut
                    if (!admit(seed)) continue
                    queue[0] = seed
                    visited[seed] = true
                    var tail = 1
                    var head = 0
                    var touchesFrame = false
                    var firstRow = height
                    var lastRow = -1
                    val component = seed + 1
                    while (head < tail) {
                        if (head and 255 == 0) checkpoint()
                        val i = queue[head++]
                        val x = i % width
                        val y = i / width
                        if (x < 2 || x >= width - 2 || y < 2 || y >= height - 2) touchesFrame = true
                        if (!touchesFrame) {
                            if (stamp[y] != component) {
                                stamp[y] = component
                                low[y] = x
                                high[y] = x
                            }
                            if (x < low[y]) low[y] = x
                            if (x > high[y]) high[y] = x
                            if (y < firstRow) firstRow = y
                            if (y > lastRow) lastRow = y
                        }
                        fun visit(next: Int) {
                            if (!admit(next)) return
                            visited[next] = true
                            queue[tail++] = next
                        }
                        if (x > 0) visit(i - 1)
                        if (x + 1 < width) visit(i + 1)
                        if (y > 0) visit(i - width)
                        if (y + 1 < height) visit(i + width)
                    }
                    if (touchesFrame || tail < minArea || tail > .86 * pixels.size) continue
                    // All other pixels on a row lie in the segment between these two points; they cannot change its convex hull.
                    val extremes = ArrayList<PointPx>(2 * (lastRow - firstRow + 1))
                    for (y in firstRow..lastRow) if (stamp[y] == component) {
                        extremes += PointPx(low[y].toDouble(), y.toDouble())
                        if (high[y] != low[y]) extremes += PointPx(high[y].toDouble(), y.toDouble())
                    }
                    val hull = hull(extremes)
                    val area = area(hull)
                    if (area < minArea || tail < .90 * area) continue
                    val quad = quadrilateral(hull, width, height, checkpoint) ?: continue
                    if (candidates.any { agrees(it, quad, 4.0) } || !separatesPage(quad, sign) ||
                        hasContinuingSides(quad, width, height, pixels)) continue
                    candidates += quad
                    // A second visible and independently supported page is ambiguity, not permission to choose one.
                    if (candidates.size == 2) return candidates
                }
            }
        }
        return candidates
    }

    private fun hasContinuingSides(quad: QuadPx, width: Int, height: Int, pixels: IntArray): Boolean {
        val corners = quad.points()
        val directions = List(4) { i ->
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val d = distance(a, b)
            PointPx((b.x - a.x) / d, (b.y - a.y) / d)
        }
        fun sample(x: Double, y: Double): Int? {
            val ix = x.roundToInt()
            val iy = y.roundToInt()
            return if (ix in 0 until width && iy in 0 until height) pixels[iy * width + ix] else null
        }
        fun contrast(origin: PointPx, axis: PointPx, along: Int): Int {
            val x = origin.x + axis.x * along
            val y = origin.y + axis.y * along
            val inside = sample(x - 2.5 * axis.y, y + 2.5 * axis.x) ?: return 0
            val outside = sample(x + 2.5 * axis.y, y - 2.5 * axis.x) ?: return 0
            return inside - outside
        }
        // A threshold can isolate the lit part of a clipped page. Do not call a shadow its cap when both sides continue.
        for (i in corners.indices) {
            val next = (i + 1) % 4
            var consecutive = 0
            var previousLeft = 0
            var previousRight = 0
            for (d in 3..14) {
                val left = contrast(corners[i], directions[(i + 3) % 4], d)
                val right = contrast(corners[next], directions[next], -d)
                // Backgrounds can change across the two sides. Each side must keep its own contrast sign,
                // not the sign of the other side or of the thresholded portion of the sheet.
                consecutive = when {
                    abs(left) < 13 || abs(right) < 13 -> 0
                    left * previousLeft > 0 && right * previousRight > 0 -> consecutive + 1
                    else -> 1
                }
                previousLeft = left
                previousRight = right
                if (consecutive >= 4) return true
            }
        }
        return false
    }

    private fun otsu(pixels: IntArray, checkpoint: () -> Unit): Int? {
        val count = IntArray(256)
        for (index in pixels.indices) {
            if (index and 255 == 0) checkpoint()
            count[pixels[index]]++
        }
        if (count.indexOfLast { it > 0 } - count.indexOfFirst { it > 0 } < 13) return null
        val total = count.indices.sumOf { it * count[it] }
        var n = 0
        var s = 0
        var best = 0.0
        var first = 0
        var last = 0
        for (level in 0 until 255) {
            n += count[level]
            s += count[level] * level
            val other = pixels.size - n
            if (n == 0 || other == 0) continue
            val delta = s.toDouble() / n - (total - s).toDouble() / other
            val merit = n.toDouble() * other * delta * delta
            if (merit > best) {
                best = merit
                first = level
                last = level
            } else if (merit == best && level == last + 1) last = level
        }
        return (first + last) / 2
    }

    private fun hull(points: List<PointPx>): List<PointPx> {
        val sorted = points.sortedWith(compareBy<PointPx> { it.x }.thenBy { it.y })
        fun half(indices: IntProgression): MutableList<PointPx> {
            val chain = ArrayList<PointPx>()
            for (index in indices) {
                val p = sorted[index]
                while (chain.size >= 2 && cross(chain[chain.lastIndex - 1], chain.last(), p) <= 0) chain.removeAt(chain.lastIndex)
                chain += p
            }
            if (chain.isNotEmpty()) chain.removeAt(chain.lastIndex)
            return chain
        }
        return half(sorted.indices) + half(sorted.indices.reversed())
    }

    private fun quadrilateral(hull: List<PointPx>, width: Int, height: Int, checkpoint: () -> Unit): QuadPx? {
        if (hull.size < 4) return null
        val simplified = hull.toMutableList()
        while (simplified.size > 4) {
            checkpoint()
            val n = simplified.size
            // Smallest removed corner triangle first; then check the original hull against all four retained half-planes.
            val corner = simplified.indices.minBy { i -> cross(simplified[(i + n - 1) % n], simplified[i], simplified[(i + 1) % n]) }
            simplified.removeAt(corner)
        }
        for (i in simplified.indices) {
            val a = simplified[i]
            val b = simplified[(i + 1) % 4]
            val length = distance(a, b)
            if (length < 12.0 || hull.any { point -> cross(a, b, point) < -2.0 * length }) return null
        }
        // Label the cyclically ordered points by best agreement with screen right/down/left/up.
        // Do not assign independently selected extrema or invent a preferred quarter-turn for a symmetric diagonal.
        val alignments = List(4) { start ->
            (0..3).sumOf { edge ->
                val a = simplified[(start + edge) % 4]
                val b = simplified[(start + edge + 1) % 4]
                (when (edge) { 0 -> b.x - a.x; 1 -> b.y - a.y; 2 -> a.x - b.x; else -> a.y - b.y }) / distance(a, b)
            }
        }
        val order = alignments.indices.sortedByDescending(alignments::get)
        val first = order[0]
        if (alignments[first] - alignments[order[1]] < .12) return null
        val q = QuadPx(simplified[first], simplified[(first + 1) % 4], simplified[(first + 2) % 4], simplified[(first + 3) % 4])
        return try {
            QuadValidation.validate(q, PixelSize(width, height))
            q
        } catch (_: IllegalArgumentException) { null }
    }

    internal fun agrees(a: QuadPx, b: QuadPx, tolerance: Double): Boolean =
        a.points().zip(b.points()).all { (p, q) -> distance(p, q) <= tolerance }

    private fun area(points: List<PointPx>): Double = abs(points.indices.sumOf { i ->
        val p = points[i]
        val q = points[(i + 1) % points.size]
        p.x * q.y - p.y * q.x
    }) / 2.0

    private fun cross(a: PointPx, b: PointPx, c: PointPx) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    private fun distance(a: PointPx, b: PointPx) = hypot(b.x - a.x, b.y - a.y)
}
