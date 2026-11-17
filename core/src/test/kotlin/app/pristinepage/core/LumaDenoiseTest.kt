package app.pristinepage.core

import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.recipe.DenoiseRecipe
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LumaDenoiseTest {
    private fun apply(input: RgbImage, recipe: DenoiseRecipe.BilateralLumaV1 = DenoiseRecipe.BilateralLumaV1(), mask: ProtectionMask? = null, checkpoint: () -> Unit = {}) = CoreApi.function("image.LumaDenoise", "apply", input, recipe, mask, checkpoint) as RgbImage

    @Test fun bl01ReflectedOffsetsUseOriginalLumaAndOnePublicBlend() {
        AppearanceTestApi.neutral(3, 1, intArrayOf(0, 10, 0)).use { input ->
            apply(input).use { AppearanceTestApi.assertNeutral(intArrayOf(4, 6, 4), it) }
            apply(input, DenoiseRecipe.BilateralLumaV1(strength = .5)).use { AppearanceTestApi.assertNeutral(intArrayOf(2, 8, 2), it) }
            AppearanceTestApi.assertNeutral(intArrayOf(0, 10, 0), input)
        }
    }

    @Test fun bl02ConstantColoredAndSingletonRastersRemainExact() {
        for ((w, h) in listOf(1 to 1, 1 to 7, 7 to 1, 5 to 3)) {
            RgbImage(w, h, w * 3, ByteArray(w * h * 3) { intArrayOf(252, 100, 50)[it % 3].toByte() }).use { input ->
                apply(input).use { assertEquals(input, it) }
                apply(input, DenoiseRecipe.BilateralLumaV1(radiusPx = 2)).use { assertEquals(input, it) }
            }
        }
    }

    @Test fun twoDimensionalRadiusTwoRetainsEveryReflectedOffsetWeight() {
        AppearanceTestApi.neutral(3, 2, intArrayOf(0, 10, 20, 30, 40, 50)).use { input ->
            apply(input, DenoiseRecipe.BilateralLumaV1(radiusPx = 2)).use { AppearanceTestApi.assertNeutral(intArrayOf(5, 11, 19, 31, 39, 45), it) }
        }
    }

    @Test fun positiveLumaChangeUsesCommonRgbHeadroomBeforeCandidateQuantization() {
        RgbImage(3, 1, 9, byteArrayOf(255.toByte(), 110, 60, 252.toByte(), 100, 50, 255.toByte(), 110, 60)).use { input ->
            apply(input).use { output -> for ((channel, expected) in intArrayOf(255, 103, 53).withIndex()) assertEquals(expected, output.getUnsigned(1, 0, channel)) }
            apply(input, DenoiseRecipe.BilateralLumaV1(strength = .5)).use { output -> for ((channel, expected) in intArrayOf(254, 102, 52).withIndex()) assertEquals(expected, output.getUnsigned(1, 0, channel)) }
        }
    }

    @Test fun fullAndFractionalProtectionUseTheAlreadyClippedCandidate() {
        AppearanceTestApi.neutral(3, 1, intArrayOf(0, 10, 0)).use { input ->
            ProtectionMask(3, 1, floatArrayOf(1f, .5f, 0f)).use { mask ->
                apply(input, mask = mask).use { AppearanceTestApi.assertNeutral(intArrayOf(0, 8, 4), it) }
            }
        }
    }

    @Test fun offRetainsAnIndependentLeaseButStillRejectsMisalignedMasks() {
        val source = AppearanceTestApi.neutral(3, 1, intArrayOf(0, 10, 0))
        ProtectionMask(1, 3, FloatArray(3)).use { mask -> assertThrows(IllegalArgumentException::class.java) { apply(source, DenoiseRecipe.BilateralLumaV1(strength = 0.0), mask) } }
        val output = apply(source, DenoiseRecipe.BilateralLumaV1(strength = 0.0)); source.close()
        output.use { AppearanceTestApi.assertNeutral(intArrayOf(0, 10, 0), it) }
    }

    @Test fun cancellationAtDeliveryDoesNotLeakOrModifySource() {
        AppearanceTestApi.neutral(3, 3, intArrayOf(0, 10, 0, 20, 0, 30, 0, 10, 0)).use { input ->
            val before = input.pixels; var calls = 0
            apply(input, checkpoint = { calls++ }).close()
            var current = 0
            assertThrows(CancellationException::class.java) { apply(input, checkpoint = { if (++current == calls) throw CancellationException() }) }
            assertArrayEquals(before, input.pixels)
            var offCalls = 0
            assertThrows(CancellationException::class.java) { apply(input, DenoiseRecipe.BilateralLumaV1(strength = 0.0), checkpoint = { if (++offCalls == 2) throw CancellationException() }) }
            assertEquals(1, AppearanceTestApi.liveLeases(input))
        }
    }

    @Test fun workspaceIncludesLumaAndBothRgbCopiesWithoutAllocatingThem() {
        val bytes = CoreApi.function("image.LumaDenoise", "workspaceBytes", 4000, 3000) as Long
        assertTrue(bytes >= 84_000_000L && bytes < 85_000_000L)
        assertThrows(IllegalArgumentException::class.java) { CoreApi.function("image.LumaDenoise", "workspaceBytes", Int.MAX_VALUE, 1) }
    }
}
