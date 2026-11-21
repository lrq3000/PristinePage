package app.pristinepage.core.image

import app.pristinepage.core.recipe.AppearanceRecipe
import kotlin.math.abs

/** Conservative edge-aligned two-region paper model. It never estimates an arbitrary image's intended white point. */
object PaperShadow {
    @JvmStatic fun workspaceBytes(width: Int, height: Int): Long =
        6L * AppearanceBuffers.pixels(width, height) + 4096L

    @JvmStatic fun apply(input: RgbImage, recipe: AppearanceRecipe.PaperShadowV1, protection: ProtectionMask? = null,
        checkpoint: () -> Unit = {}): RgbImage {
        AppearanceBuffers.preflight(input, protection)
        checkpoint()
        if (recipe.strength == 0.0) return AppearanceBuffers.retain(input, checkpoint)
        val acrossX = findBoundary(input, false, checkpoint)
        val acrossY = findBoundary(input, true, checkpoint)
        val shadow = when {
            acrossX != null && acrossY == null -> acrossX
            acrossY != null && acrossX == null -> acrossY
            else -> return AppearanceBuffers.retain(input, checkpoint)
        }
        val pixels = AppearanceBuffers.pixels(input.width, input.height)
        val bytes = ByteArray((3 * pixels).toInt())
        val dark = shadow.dark
        val darkLuma = Numeric.luma(dark[0], dark[1], dark[2])
        val litLuma = Numeric.luma(shadow.lit[0], shadow.lit[1], shadow.lit[2])
        val red = Shoulder(dark[0], shadow.lit[0])
        val green = Shoulder(dark[1], shadow.lit[1])
        val blue = Shoulder(dark[2], shadow.lit[2])
        var at = 0
        for (y in 0 until input.height) {
            checkpoint()
            for (x in 0 until input.width) {
                val p = if (shadow.transposed) y else x
                val r = input.getUnsigned(x, y, 0)
                val g = input.getUnsigned(x, y, 1)
                val b = input.getUnsigned(x, y, 2)
                val protected = protection?.sample(x, y) ?: 0f
                if ((p < shadow.edge) != shadow.darkFirst || protected == 1f) {
                    bytes[at++] = r.toByte()
                    bytes[at++] = g.toByte()
                    bytes[at++] = b.toByte()
                    continue
                }
                val y8 = Numeric.luma(r, g, b)
                val chromaError = maxOf(abs((r - g) - (dark[0] - dark[1])),
                    abs((g - b) - (dark[1] - dark[2])), abs((r - b) - (dark[0] - dark[2])))
                // A constant offset preserves faint neutral ink's deficit; far darker or chromatically distinct marks do not inherit the paper model.
                val weight = ramp(y8.toDouble() / darkLuma, .55, .83) * (1 - ramp(chromaError.toDouble(), 12.0, 24.0))
                val cr = Numeric.roundByte(r + red.offset(r) * weight)
                val cg = Numeric.roundByte(g + green.offset(g) * weight)
                val cb = Numeric.roundByte(b + blue.offset(b) * weight)
                // If correcting the paper would make an unprotected brighter mark disappear or turn dark, refuse the whole hypothesis rather than publish a partial correction.
                if (y8 > darkLuma && Numeric.luma(cr, cg, cb) <= litLuma) return AppearanceBuffers.retain(input, checkpoint)
                bytes[at++] = StageBlend.channel(r, cr.toDouble(), recipe.strength, protected).toByte()
                bytes[at++] = StageBlend.channel(g, cg.toDouble(), recipe.strength, protected).toByte()
                bytes[at++] = StageBlend.channel(b, cb.toDouble(), recipe.strength, protected).toByte()
            }
        }
        checkpoint()
        return AppearanceBuffers.output(input, bytes, checkpoint)
    }

    private data class Shadow(val transposed: Boolean, val edge: Int, val darkFirst: Boolean, val dark: IntArray, val lit: IntArray)

    private fun findBoundary(input: RgbImage, transposed: Boolean, checkpoint: () -> Unit): Shadow? {
        val across = if (transposed) input.height else input.width
        val along = if (transposed) input.width else input.height
        if (across < 64 || along < 32) return null
        val scanlines = IntArray(13) { (along - 1) * (it + 1) / 14 }
        fun channel(p: Int, q: Int, c: Int) = if (transposed) input.getUnsigned(q, p, c) else input.getUnsigned(p, q, c)
        fun luma(p: Int, q: Int) = Numeric.luma(channel(p, q, 0), channel(p, q, 1), channel(p, q, 2))
        val scratch = IntArray(scanlines.size)
        fun median(p: Int, sample: (Int, Int) -> Int): Int {
            for (i in scanlines.indices) scratch[i] = sample(p, scanlines[i])
            scratch.sort()
            return scratch[scratch.size / 2]
        }
        val margin = maxOf(16, across / 6)
        var previous = median(0, ::luma)
        var edge = -1
        var sign = 0
        for (p in 1 until across) {
            if (p % 32 == 0) checkpoint()
            val next = median(p, ::luma)
            val step = next - previous
            previous = next
            if (abs(step) < 32) continue
            // A second large discontinuity or a narrow strip is not the single separating boundary this method can correct.
            if (edge != -1 || p !in margin..(across - margin)) return null
            edge = p
            sign = if (step > 0) 1 else -1
        }
        if (edge == -1 || scanlines.count { sign * (luma(edge, it) - luma(edge - 1, it)) >= 28 } < 11) return null
        val first = IntArray(3) { c -> median(edge - 4) { p, q -> channel(p, q, c) } }
        val last = IntArray(3) { c -> median(edge + 3) { p, q -> channel(p, q, c) } }
        fun matches(p: Int, q: Int, reference: IntArray) = (0..2).all { abs(channel(p, q, it) - reference[it]) <= 12 }
        fun agrees(p: Int, reference: IntArray): Boolean {
            var support = 0
            for (q in scanlines) if (matches(p, q, reference)) support++
            return support >= 10
        }
        // Near edge and far into both halves, require a coherent paper color; do not classify from an isolated high-gradient feature.
        val leftSites = intArrayOf(edge - 1, edge - 5, edge / 4, edge / 2, edge * 3 / 4)
        val rightSites = intArrayOf(edge, edge + 4, edge + (across - edge) / 4, (edge + across) / 2, edge + (across - edge) * 3 / 4)
        if (!leftSites.all { agrees(it, first) } || !rightSites.all { agrees(it, last) }) return null
        val dark = if (sign > 0) first else last
        val lit = if (sign > 0) last else first
        val delta = IntArray(3) { lit[it] - dark[it] }
        val darkY = Numeric.luma(dark[0], dark[1], dark[2])
        val litY = Numeric.luma(lit[0], lit[1], lit[2])
        if (darkY !in 98..192 || litY !in 176..250 || lit.max() > 250 || delta.any { it !in 20..115 } ||
            dark.max() - dark.min() > 48 || lit.max() - lit.min() > 48 || delta.max() - delta.min() > 30) return null
        var displaced = 0
        for (q in 0 until along) {
            if (q % 32 == 0) checkpoint()
            val wrongSide = (matches(edge - 1, q, last) && matches(edge - 3, q, last)) ||
                (matches(edge, q, first) && matches(edge + 2, q, first))
            val noStep = sign * (luma(edge, q) - luma(edge - 1, q)) < 28
            displaced = if (wrongSide || noStep) displaced + 1 else 0
            // A missed corner or displaced hard step can leave an obvious uncorrected strip even if the sparse votes agree.
            if (displaced >= 3) return null
        }
        return Shadow(transposed, edge, sign > 0, dark, lit)
    }

    private class Shoulder(private val dark: Int, private val lit: Int) {
        private val compression = 1.0 / (255 - lit) - 1.0 / (255 - dark)

        fun offset(value: Int): Double {
            if (value <= dark) return (lit - dark).toDouble()
            val over = (value - dark).toDouble()
            // Match the additive offset's slope at the paper level, then approach white monotonically instead of fading correction back to zero over an arbitrary narrow band.
            return (lit + over / (1.0 + over * compression) - value).coerceAtLeast(0.0)
        }
    }

    private fun ramp(x: Double, low: Double, high: Double): Double {
        val t = ((x - low) / (high - low)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
