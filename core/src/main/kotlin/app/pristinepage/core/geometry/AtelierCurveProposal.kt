package app.pristinepage.core.geometry

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.recipe.GeometryRecipe
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/** A bounded proposal for visible pages with a dominant vertical bow, not an estimate for arbitrary folds or content. */
object AtelierCurveProposal {
    private val evidenceSize = PixelSize(97, 97)
    fun workspaceBytes(): Long = CubicSamplingAxes.workspaceBytes(evidenceSize) + 64L * 1024

    fun detect(
        frame: GeometryRecipe.Perspective, width: Int, height: Int,
        checkpoint: () -> Unit = {}, luminance: (Int, Int) -> Int,
    ): GeometryRecipe.ManualCurveFourSides? {
        if (width !in 48..384 || height !in 48..384) return null
        val raster = Preview(width, height, luminance)
        fun previewPoint(p: PointPx) = PointPx(p.x * (width - 1), p.y * (height - 1))
        val q = frame.quad
        val tl = previewPoint(q.tl)
        val tr = previewPoint(q.tr)
        val br = previewPoint(q.br)
        val bl = previewPoint(q.bl)
        val pageHeight = min(bl.y - tl.y, br.y - tr.y)
        if (pageHeight < 45) return null
        val limit = min(24.0, .13 * pageHeight)
        fun plausibleChord(a: PointPx, b: PointPx) = b.x - a.x >= 44 && abs(b.y - a.y) <= .45 * (b.x - a.x)
        if (!plausibleChord(tl, tr) || !plausibleChord(bl, br)) return null
        if (!visibleCorner(raster, tl, tr, bl) || !visibleCorner(raster, tr, tl, br) ||
            !visibleCorner(raster, bl, br, tl) || !visibleCorner(raster, br, bl, tr)) return null
        if (!straightSide(raster, tl, bl, 1, checkpoint) || !straightSide(raster, tr, br, -1, checkpoint)) return null
        val top = fitBow(raster, tl, tr, 1, limit, checkpoint) ?: return null
        val bottom = fitBow(raster, bl, br, -1, limit, checkpoint) ?: return null
        // Opposed or substantially different bends can be printed decoration, a fold, or multiple surfaces.
        if (abs(top) < 2.5 || abs(bottom) < 2.5 || top * bottom <= 0 || abs(top - bottom) > 3.0) return null
        if (!clearDiscardedMargin(raster, tl, tr, top, 1, checkpoint) ||
            !clearDiscardedMargin(raster, bl, br, bottom, -1, checkpoint)) return null
        fun midpoint(a: PointPx, b: PointPx, sag: Double) = PointPx((a.x + b.x) / 2, (a.y + b.y) / 2 + sag / (height - 1))
        val topM = midpoint(q.tl, q.tr, top)
        val bottomM = midpoint(q.bl, q.br, bottom)
        if (topM.y !in 0.0..1.0 || bottomM.y !in 0.0..1.0) return null
        val candidate = GeometryRecipe.ManualCurveFourSides(frame.source, q,
            topMidpoint = topM, bottomMidpoint = bottomM, targetRule = frame.targetRule, outputQuarterTurns = frame.outputQuarterTurns)
        val source = frame.source.sourceSize
        fun full(p: PointPx) = PointPx(p.x * (source.width - 1), p.y * (source.height - 1))
        fun toEvidenceSample(p: PointPx): Double? = raster.sample(
            p.x * (width - 1) / (source.width - 1), p.y * (height - 1) / (source.height - 1))
        return try {
            checkpoint()
            val perspective = Homography.solve(QuadPx(full(q.tl), full(q.tr), full(q.br), full(q.bl)), source, evidenceSize)
            val curved = CubicSamplingAxes.prepare(candidate, evidenceSize, checkpoint)
            val before = lineEvidence(checkpoint) { x, y -> toEvidenceSample(perspective.map(x.toDouble(), y.toDouble())) } ?: return null
            val after = lineEvidence(checkpoint) { x, y -> toEvidenceSample(curved.map(x, y)) } ?: return null
            // The paper outline proposes a map; only improvement on independent interior line structure permits suggesting it.
            candidate.takeIf { after.peaks >= 3 && after.strength >= before.strength * 1.35 + 36 }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun visibleCorner(raster: Preview, corner: PointPx, horizontal: PointPx, vertical: PointPx): Boolean {
        fun direction(p: PointPx): PointPx {
            val dx = p.x - corner.x
            val dy = p.y - corner.y
            val length = hypot(dx, dy)
            return PointPx(dx / length, dy / length)
        }
        val h = direction(horizontal)
        val v = direction(vertical)
        val dx = 2.0 * (h.x + v.x)
        val dy = 2.0 * (h.y + v.y)
        val inside = raster.sample(corner.x + dx, corner.y + dy) ?: return false
        val outside = raster.sample(corner.x - dx, corner.y - dy) ?: return false
        return inside - outside >= 22
    }

    private fun clearDiscardedMargin(raster: Preview, a: PointPx, b: PointPx, sag: Double, inward: Int, checkpoint: () -> Unit): Boolean {
        if (sag * inward <= 0.0) return true
        for (x in ceil(a.x).toInt()..floor(b.x).toInt()) {
            checkpoint()
            val t = (x - a.x) / (b.x - a.x)
            val y = a.y + t * (b.y - a.y)
            val displacement = sag * inward * (4 * t * (1 - t))
            // The one-pixel transition is not reliable evidence. Penalize a sustained departure from the adjacent background in the discarded strip.
            if (displacement < 3.0) continue
            val outside = raster.sample(x.toDouble(), y - inward * 2) ?: return false
            var different = 0
            for (i in 0..floor(displacement - 2).toInt()) {
                val sample = raster.sample(x.toDouble(), y + i * inward) ?: return false
                different = if (abs(sample - outside) >= 22) different + 1 else 0
                if (different >= 2) return false
            }
        }
        return true
    }

    private fun straightSide(raster: Preview, a: PointPx, b: PointPx, inward: Int, checkpoint: () -> Unit): Boolean {
        var supported = 0
        val length = hypot(b.x - a.x, b.y - a.y)
        val nx = inward * (b.y - a.y) / length
        val ny = inward * (a.x - b.x) / length
        for (k in 1..9) {
            checkpoint()
            val t = k / 10.0
            val x = a.x + t * (b.x - a.x)
            val y = a.y + t * (b.y - a.y)
            val inside = raster.sample(x + 3 * nx, y + 3 * ny) ?: continue
            val outside = raster.sample(x - 3 * nx, y - 3 * ny) ?: continue
            if (inside - outside >= 22) supported++
        }
        return supported >= 8
    }

    private data class Trace(val shape: Double, val offset: Double)

    private fun fitBow(raster: Preview, a: PointPx, b: PointPx, inward: Int, limit: Double, checkpoint: () -> Unit): Double? {
        val trace = ArrayList<Trace>(19)
        for (k in 1..19) {
            checkpoint()
            val t = k / 20.0
            val x = a.x + t * (b.x - a.x)
            val chordY = a.y + t * (b.y - a.y)
            var best = 22.0
            var first = 0
            var last = 0
            var found = false
            val radius = floor(limit * 2).toInt()
            fun contrast(y: Double, tap: Double): Double {
                val inside = raster.sample(x, y + inward * tap) ?: return 0.0
                val outside = raster.sample(x, y - inward * tap) ?: return 0.0
                return inside - outside
            }
            for (i in -radius..radius) {
                val y = chordY + i * .5
                if (contrast(y, 3.0) < 22 || contrast(y, 5.0) < 22) continue
                val edge = contrast(y, 1.0)
                if (edge <= 22) continue
                if (edge > best) {
                    first = i
                    last = i
                    best = edge
                    found = true
                } else if (edge == best) last = i
            }
            // Equal remote peaks are ambiguous, not a single broad edge; missing near-corner traces are not extrapolated.
            if (!found || last - first > 4) {
                if (k <= 2 || k >= 18) return null
                continue
            }
            trace += Trace(4 * t * (1 - t), (first + last) * .25)
        }
        if (trace.size < 17) return null
        val estimates = trace.map { it.offset / it.shape }.sorted()
        val median = estimates[estimates.size / 2]
        val inliers = trace.filter { abs(it.offset - it.shape * median) <= 1.25 }
        if (inliers.size < 17 || abs(trace.first().offset - trace.first().shape * median) > 1.25 ||
            abs(trace.last().offset - trace.last().shape * median) > 1.25) return null
        return inliers.sumOf { it.shape * it.offset } / inliers.sumOf { it.shape * it.shape }
    }

    private data class Lines(val strength: Double, val peaks: Int)

    private fun lineEvidence(checkpoint: () -> Unit, sample: (Int, Int) -> Double?): Lines? {
        val bands = arrayOf(12..31, 39..57, 65..84)
        val rows = Array(evidenceSize.height) { DoubleArray(bands.size) }
        for (y in 10..86) {
            checkpoint()
            bands.forEachIndexed { i, band ->
                var sum = 0.0
                for (x in band) sum += sample(x, y) ?: return null
                rows[y][i] = sum / band.count()
            }
        }
        var strength = 0.0
        var peaks = 0
        var peak = 0.0
        for (y in 13..84) {
            // Score dark lines bounded by lighter material on both sides, in all three disjoint horizontal bands.
            val common = if (y == 84) 0.0 else bands.indices.minOf { i ->
                min(rows[y - 2][i], rows[y + 2][i]) - rows[y][i]
            }
            if (common >= 12) peak = maxOf(peak, common)
            else if (peak > 0.0) {
                strength += peak
                peaks++
                peak = 0.0
            }
        }
        return Lines(strength, peaks)
    }

    private class Preview(private val width: Int, private val height: Int, private val at: (Int, Int) -> Int) {
        fun sample(x: Double, y: Double): Double? {
            if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return null
            val ix = x.toInt()
            val iy = y.toInt()
            val jx = min(ix + 1, width - 1)
            val jy = min(iy + 1, height - 1)
            val tx = x - ix
            val ty = y - iy
            val a = at(ix, iy) * (1 - tx) + at(jx, iy) * tx
            val b = at(ix, jy) * (1 - tx) + at(jx, jy) * tx
            return a * (1 - ty) + b * ty
        }
    }
}
