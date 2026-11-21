package app.pristinepage.core.geometry

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.DetectionProposal
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import kotlin.math.exp

/** Portable float-letterbox input and minimal DocQuad head decoder. This geometry-only adapter does not execute a model or edit a recipe. */
object DocQuadGeometry {
    private const val INPUT_SIDE = 256
    private const val HEAD_SIDE = 64
    private const val INPUT_PLANE = INPUT_SIDE * INPUT_SIDE
    private const val CELLS = HEAD_SIDE * HEAD_SIDE

    fun preprocess(source: RgbImage, checkpoint: () -> Unit = {}): FloatArray {
        require(source.width >= 2 && source.height >= 2)
        val frame = Frame(PixelSize(source.width, source.height))
        checkpoint()
        val tensor = FloatArray(3 * INPUT_PLANE)
        for (y in 0 until INPUT_SIDE) {
            checkpoint()
            val dy = y + .5 - frame.y
            if (dy < 0 || dy >= frame.height) continue
            for (x in 0 until INPUT_SIDE) {
                val dx = x + .5 - frame.x
                if (dx < 0 || dx >= frame.width) continue
                val index = y * INPUT_SIDE + x
                for (channel in 0..2) {
                    tensor[channel * INPUT_PLANE + index] = (BilinearSampler.resizedRgbSample(
                        source, dx / frame.scale - .5, dy / frame.scale - .5, channel,
                    ) / 255.0).toFloat()
                }
            }
        }
        checkpoint()
        return tensor
    }

    /** Borrows validated-lifetime planes: mask [1,1,64,64] and labeled TL/TR/BR/BL heatmaps [1,4,64,64]. */
    fun propose(
        source: PixelSize,
        maskLogits: FloatArray,
        cornerHeatmaps: FloatArray,
        checkpoint: () -> Unit = {},
    ): DetectionProposal {
        if (source.width < 2 || source.height < 2) return refusal("INVALID_GEOMETRY")
        if (maskLogits.size != CELLS || cornerHeatmaps.size != 4 * CELLS) return refusal("INVALID_TENSOR")
        if (!finite(maskLogits, checkpoint) || !finite(cornerHeatmaps, checkpoint)) return refusal("INVALID_TENSOR")
        val frame = Frame(source)
        val peaks = List(4) { channel -> locatePeak(cornerHeatmaps, channel * CELLS, checkpoint) }
        val head = peaks.map { it.center }
        // Heatmap labels use nominal model coordinates, not the destination-center convention of the input sampler.
        val points = head.map { PointPx((it.x * 4 - frame.x) / frame.scale, (it.y * 4 - frame.y) / frame.scale) }
        val quad = QuadPx(points[0], points[1], points[2], points[3])
        try { QuadValidation.validate(quad, source) }
        catch (_: IllegalArgumentException) { return refusal("INVALID_GEOMETRY") }

        val minimumPeak = peaks.minOf { sigmoid(it.logit) }
        val overlap = intersectionOverUnion(head, maskLogits, checkpoint)
        val score = minimumPeak * overlap
        checkpoint()
        if (minimumPeak < .5 || overlap < .5 || score < .35) return refusal("LOW_CONFIDENCE", score)
        return DetectionProposal(quad, score, true, null)
    }

    private fun locatePeak(logits: FloatArray, base: Int, checkpoint: () -> Unit): Peak {
        var index = 0
        for (i in 1 until CELLS) {
            if (i % HEAD_SIDE == 0) checkpoint()
            if (logits[base + i] > logits[base + index]) index = i
        }
        val x = index % HEAD_SIDE
        val y = index / HEAD_SIDE
        val center = logits[base + index].toDouble()
        fun sample(px: Int, py: Int) = logits[base + py * HEAD_SIDE + px].toDouble()
        val dx = if (x in 1 until HEAD_SIDE - 1) parabola(sample(x - 1, y), center, sample(x + 1, y)) else null
        val dy = if (y in 1 until HEAD_SIDE - 1) parabola(sample(x, y - 1), center, sample(x, y + 1)) else null
        if (dx != null || dy != null) return Peak(PointPx(x + .5 + (dx ?: 0.0), y + .5 + (dy ?: 0.0)), center)

        var total = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        for (j in maxOf(0, y - 2)..minOf(HEAD_SIDE - 1, y + 2)) {
            for (i in maxOf(0, x - 2)..minOf(HEAD_SIDE - 1, x + 2)) {
                val weight = exp(sample(i, j) - center)
                total += weight
                weightedX += (i + .5) * weight
                weightedY += (j + .5) * weight
            }
        }
        val point = if (total > 0 && total.isFinite()) PointPx(weightedX / total, weightedY / total) else PointPx(x + .5, y + .5)
        return Peak(point, center)
    }

    private fun intersectionOverUnion(head: List<PointPx>, mask: FloatArray, checkpoint: () -> Unit): Double {
        var intersection = 0
        var union = 0
        for (y in 0 until HEAD_SIDE) {
            checkpoint()
            for (x in 0 until HEAD_SIDE) {
                val covered = head.indices.all { i ->
                    val a = head[i]
                    val b = head[(i + 1) % 4]
                    (b.x - a.x) * (y + .5 - a.y) - (b.y - a.y) * (x + .5 - a.x) >= 0.0
                }
                val foreground = mask[y * HEAD_SIDE + x] > 0
                if (covered || foreground) union++
                if (covered && foreground) intersection++
            }
        }
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun finite(values: FloatArray, checkpoint: () -> Unit): Boolean {
        for (i in values.indices) {
            if (i % CELLS == 0) checkpoint()
            if (!values[i].isFinite()) return false
        }
        return true
    }

    private fun parabola(left: Double, center: Double, right: Double): Double? {
        val denominator = left - 2 * center + right
        return if (denominator < -1e-12) (.5 * (left - right) / denominator).coerceIn(-.5, .5) else null
    }

    private fun sigmoid(x: Double): Double = if (x >= 0) 1 / (1 + exp(-x)) else exp(x).let { it / (1 + it) }

    private fun refusal(reason: String, score: Double? = null) = DetectionProposal(null, score, false, reason)

    private data class Peak(val center: PointPx, val logit: Double)

    private class Frame(source: PixelSize) {
        val scale = INPUT_SIDE.toDouble() / maxOf(source.width, source.height)
        val width = source.width * scale
        val height = source.height * scale
        val x = (INPUT_SIDE - width) / 2
        val y = (INPUT_SIDE - height) / 2
    }
}
