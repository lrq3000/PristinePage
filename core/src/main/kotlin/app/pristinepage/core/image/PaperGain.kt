package app.pristinepage.core.image

import app.pristinepage.core.recipe.AppearanceRecipe
import kotlin.math.floor

/** Independent implementation of PHOTOMETRIC_ALGORITHMS §3, not an upstream enhancement preset: no ink curve, sharpening, denoise or extra white lift. */
object PaperGain {
    @JvmStatic fun workspaceBytes(width: Int, height: Int): Long {
        val n = AppearanceBuffers.pixels(width, height)
        val side = cellSide(width, height)
        val grid = ceilDiv(width, side).toLong() * ceilDiv(height, side)
        // Conservative grid budget covers both RGB float grids, statistics, sorted lumas and frozen validity; row/header allowance exceeds the actual scalar streaming scratch.
        return Math.addExact(Math.addExact(6L * n, 80L * grid), Math.addExact(12L * width, 8192L))
    }

    @JvmStatic fun apply(input: RgbImage, recipe: AppearanceRecipe.PaperGainV1, protection: ProtectionMask? = null, checkpoint: () -> Unit = {}): RgbImage {
        AppearanceBuffers.preflight(input, protection)
        workspaceBytes(input.width, input.height)
        checkpoint()
        if (recipe.strength == 0.0) return AppearanceBuffers.retain(input, checkpoint)

        val side = cellSide(input.width, input.height)
        val gw = ceilDiv(input.width, side)
        val gh = ceilDiv(input.height, side)
        val paper = estimatePaper(input, side, gw, gh, checkpoint).samples
        for (y in 0 until gh) {
            checkpoint()
            for (x in 0 until gw) for (channel in 0..2) {
                val index = (y * gw + x) * 3 + channel
                paper[index] = (recipe.target.toFloat() / maxOf(1f, paper[index])).coerceIn(1f, recipe.maxGain.toFloat())
            }
        }

        // The smoothed paper grid is now the gain grid; never materialize full-resolution gain or float RGB planes.
        val output = ByteArray((3L * input.width * input.height).toInt())
        for (y in 0 until input.height) {
            checkpoint()
            for (x in 0 until input.width) {
                val p = protection?.sample(x, y) ?: 0f
                for (channel in 0..2) {
                    val original = input.getUnsigned(x, y, channel)
                    val value = if (p == 1f) original else {
                        val multiplied = original.toFloat() * sampleGain(paper, gw, gh, side, x, y, channel)
                        val candidate = Numeric.roundByte(multiplied.toDouble())
                        StageBlend.channel(original, candidate.toDouble(), recipe.strength, p)
                    }
                    output[(y * input.width + x) * 3 + channel] = value.toByte()
                }
            }
        }
        checkpoint()
        return AppearanceBuffers.output(input, output, checkpoint)
    }

    internal class PaperField(val samples: FloatArray, val typical: Int, private val width: Int, private val height: Int, private val side: Int) {
        fun sample(x: Int, y: Int, channel: Int): Float = sampleGain(samples, width, height, side, x, y, channel)
    }

    internal fun estimatePaper(input: RgbImage, checkpoint: () -> Unit): PaperField {
        val side = cellSide(input.width, input.height)
        return estimatePaper(input, side, ceilDiv(input.width, side), ceilDiv(input.height, side), checkpoint)
    }

    private fun estimatePaper(input: RgbImage, side: Int, gw: Int, gh: Int, checkpoint: () -> Unit): PaperField {
        val grid = gw * gh
        val paper = FloatArray(3 * grid)
        val luma = IntArray(grid)
        val flat = BooleanArray(grid)
        collect(input, side, gw, gh, paper, luma, flat, checkpoint)
        val typical = typical(luma)
        val valid = BooleanArray(grid) { accepted(luma[it], flat[it], typical) }
        fill(paper, valid, gw, gh, typical, checkpoint)
        val scratch = FloatArray(paper.size)
        smooth(paper, scratch, gw, gh, checkpoint)
        smooth(scratch, paper, gw, gh, checkpoint)
        return PaperField(paper, typical, gw, gh, side)
    }

    private fun cellSide(width: Int, height: Int): Int = maxOf(16, ((maxOf(width, height).toLong() + 39) / 40).toInt())
    private fun ceilDiv(value: Int, side: Int): Int = ((value.toLong() + side - 1) / side).toInt()

    private fun lowerMedian(histogram: IntArray, count: Int): Int {
        val threshold = (count + 1) / 2
        var accumulated = 0
        for (value in 0..255) { accumulated += histogram[value]; if (accumulated >= threshold) return value }
        error("Empty paper cell")
    }

    private fun brightCutoff(histogram: IntArray, count: Int): Int {
        var accumulated = 0L
        for (value in 255 downTo 0) { accumulated += histogram[value]; if (4L * accumulated >= count) return value }
        error("Empty paper cell")
    }

    private fun typical(luma: IntArray): Int = luma.sortedArray()[luma.size / 2]
    private fun accepted(luma: Int, flat: Boolean, typical: Int): Boolean = luma >= .7 * typical || (flat && luma >= .3 * typical)

    private fun collect(input: RgbImage, side: Int, gw: Int, gh: Int, paper: FloatArray, luma: IntArray, flat: BooleanArray, checkpoint: () -> Unit) {
        val histogram = IntArray(256)
        for (by in 0 until gh) for (bx in 0 until gw) {
            histogram.fill(0)
            val x0 = bx * side
            val y0 = by * side
            val x1 = minOf(input.width.toLong(), (bx.toLong() + 1) * side).toInt()
            val y1 = minOf(input.height.toLong(), (by.toLong() + 1) * side).toInt()
            val count = (x1 - x0) * (y1 - y0)
            for (y in y0 until y1) {
                checkpoint()
                for (x in x0 until x1) histogram[Numeric.luma(input.getUnsigned(x, y, 0), input.getUnsigned(x, y, 1), input.getUnsigned(x, y, 2))]++
            }
            val median = lowerMedian(histogram, count)
            val cutoff = brightCutoff(histogram, count)
            var red = 0L; var green = 0L; var blue = 0L; var selected = 0
            for (y in y0 until y1) {
                checkpoint()
                for (x in x0 until x1) {
                    val r = input.getUnsigned(x, y, 0); val g = input.getUnsigned(x, y, 1); val b = input.getUnsigned(x, y, 2)
                    if (Numeric.luma(r, g, b) >= cutoff) { red += r; green += g; blue += b; selected++ }
                }
            }
            val j = by * gw + bx
            // Sum-to-float rounding precedes float division, rather than a Double mean rounded just once.
            paper[j * 3] = red.toFloat() / selected.toFloat()
            paper[j * 3 + 1] = green.toFloat() / selected.toFloat()
            paper[j * 3 + 2] = blue.toFloat() / selected.toFloat()
            val level = Numeric.luma(Numeric.roundByte(paper[j * 3].toDouble()), Numeric.roundByte(paper[j * 3 + 1].toDouble()), Numeric.roundByte(paper[j * 3 + 2].toDouble()))
            luma[j] = level
            flat[j] = level - median <= 6
        }
    }

    private fun fill(paper: FloatArray, valid: BooleanArray, gw: Int, gh: Int, typical: Int, checkpoint: () -> Unit) {
        if (valid.none { it }) {
            for (y in 0 until gh) { checkpoint(); java.util.Arrays.fill(paper, y * gw * 3, (y + 1) * gw * 3, typical.toFloat()) }
            return
        }
        val frozen = BooleanArray(valid.size)
        while (valid.any { !it }) {
            checkpoint()
            valid.copyInto(frozen)
            for (y in 0 until gh) {
                checkpoint()
                for (x in 0 until gw) {
                    val j = y * gw + x
                    if (frozen[j]) continue
                    var count = 0; var red = 0f; var green = 0f; var blue = 0f
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until gw && ny in 0 until gh && frozen[ny * gw + nx]) {
                            val neighbor = (ny * gw + nx) * 3
                            red += paper[neighbor]; green += paper[neighbor + 1]; blue += paper[neighbor + 2]; count++
                        }
                    }
                    if (count != 0) {
                        paper[j * 3] = red / count; paper[j * 3 + 1] = green / count; paper[j * 3 + 2] = blue / count
                        valid[j] = true
                    }
                }
            }
        }
    }

    private fun smooth(input: FloatArray, output: FloatArray, gw: Int, gh: Int, checkpoint: () -> Unit) {
        for (y in 0 until gh) {
            checkpoint()
            for (x in 0 until gw) for (channel in 0..2) {
                var sum = 0f; var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until gw && ny in 0 until gh) { sum += input[(ny * gw + nx) * 3 + channel]; count++ }
                }
                output[(y * gw + x) * 3 + channel] = sum / count
            }
        }
    }

    private fun sampleGain(gains: FloatArray, gw: Int, gh: Int, side: Int, x: Int, y: Int, channel: Int): Float {
        val u = ((x.toFloat() + .5f) / side.toFloat() - .5f).coerceIn(0f, (gw - 1).toFloat())
        val v = ((y.toFloat() + .5f) / side.toFloat() - .5f).coerceIn(0f, (gh - 1).toFloat())
        val x0 = floor(u).toInt(); val x1 = minOf(x0 + 1, gw - 1)
        val y0 = floor(v).toInt(); val y1 = minOf(y0 + 1, gh - 1)
        val tx = u - x0; val ty = v - y0
        val tl = gains[(y0 * gw + x0) * 3 + channel]; val bl = gains[(y1 * gw + x0) * 3 + channel]
        val tr = gains[(y0 * gw + x1) * 3 + channel]; val br = gains[(y1 * gw + x1) * 3 + channel]
        val left = tl + (bl - tl) * ty
        val right = tr + (br - tr) * ty
        return left + (right - left) * tx
    }
}
