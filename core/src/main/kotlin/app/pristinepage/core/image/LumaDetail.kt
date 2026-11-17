package app.pristinepage.core.image

import app.pristinepage.core.recipe.DetailRecipe
import kotlin.math.abs

/** luma_detail7_v1 uses replicated borders and byte rounding after EACH binomial axis, then a soft residual threshold and common nonclipping RGB headroom. */
object LumaDetail {
    private val kernel = intArrayOf(1, 6, 15, 20, 15, 6, 1)

    @JvmStatic fun workspaceBytes(width: Int, height: Int): Long = Math.addExact(8L * AppearanceBuffers.pixels(width, height), 8192L)

    @JvmStatic fun apply(input: RgbImage, recipe: DetailRecipe.LumaDetail7V1, protection: ProtectionMask? = null, checkpoint: () -> Unit = {}): RgbImage {
        AppearanceBuffers.preflight(input, protection)
        require(protection == null) { "Detail does not support protection masks" }
        workspaceBytes(input.width, input.height)
        checkpoint()
        if (recipe.amount == 0.0 || recipe.maxDelta == 0 || recipe.strength == 0.0) return AppearanceBuffers.retain(input, checkpoint)
        val width = input.width; val height = input.height
        val luma = FinishingBuffers.luma(input, checkpoint)
        val horizontal = ByteArray(luma.size)
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) {
                var sum = 0
                for (k in kernel.indices) sum += kernel[k] * (luma[y * width + (x + k - 3).coerceIn(0, width - 1)].toInt() and 255)
                horizontal[y * width + x] = ((sum + 32) / 64).toByte()
            }
        }
        val output = ByteArray((3L * width * height).toInt())
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) {
                var sum = 0
                for (k in kernel.indices) sum += kernel[k] * (horizontal[(y + k - 3).coerceIn(0, height - 1) * width + x].toInt() and 255)
                val blurred = (sum + 32) / 64
                val index = y * width + x
                val residual = (luma[index].toInt() and 255) - blurred
                val change = delta(input.getUnsigned(x, y, 0), input.getUnsigned(x, y, 1), input.getUnsigned(x, y, 2), residual, recipe)
                for (channel in 0..2) {
                    val original = input.getUnsigned(x, y, channel)
                    output[3 * index + channel] = StageBlend.channel(original, (original + change).toDouble(), recipe.strength, 0f).toByte()
                }
            }
        }
        return AppearanceBuffers.output(input, output, checkpoint)
    }

    private fun delta(red: Int, green: Int, blue: Int, residual: Int, recipe: DetailRecipe.LumaDetail7V1): Int {
        val magnitude = maxOf(abs(residual) - recipe.thresholdY, 0)
        val softened = if (residual < 0) -magnitude else magnitude
        val raw = (recipe.amount * softened).coerceIn(-recipe.maxDelta.toDouble(), recipe.maxDelta.toDouble())
        return Numeric.roundSigned(FinishingBuffers.headroom(red, green, blue, raw))
    }
}
