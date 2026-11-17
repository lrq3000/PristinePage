package app.pristinepage.core

import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.recipe.BinaryRecipe
import app.pristinepage.core.recipe.WindowPolicy
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SauvolaTest {
    private fun apply(input: RgbImage, recipe: BinaryRecipe.SauvolaV1 = BinaryRecipe.SauvolaV1(), mask: ProtectionMask? = null, checkpoint: () -> Unit = {}) = CoreApi.function("image.Sauvola", "apply", input, recipe, mask, checkpoint) as RgbImage

    @Test fun gs01EncodedByteLumaHasNoHiddenEnhancement() {
        RgbImage(5, 1, 15, byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0, 0, 0, 255.toByte(), 200.toByte(), 200.toByte(), 200.toByte(), 0, 0, 0)).use { input ->
            (CoreApi.function("image.Grayscale", "apply", input, null, {}) as RgbImage).use { AppearanceTestApi.assertNeutral(intArrayOf(76, 149, 28, 200, 0), it) }
        }
    }

    @Test fun sv01ClippedWholeImageAndSmallWindowsProduceTheLiteralBinaryBytes() {
        AppearanceTestApi.neutral(3, 1, intArrayOf(0, 100, 200)).use { input ->
            apply(input).use { AppearanceTestApi.assertNeutral(intArrayOf(0, 255, 255), it) }
            apply(input, BinaryRecipe.SauvolaV1(window = WindowPolicy.ExplicitOdd(3))).use { AppearanceTestApi.assertNeutral(intArrayOf(0, 255, 255), it) }
        }
    }

    @Test fun sv02DarkConstantPositiveBecomesWhiteButZeroEqualityStaysBlack() {
        for (value in listOf(0, 10, 200)) AppearanceTestApi.neutral(3, 2, IntArray(6) { value }).use { input ->
            apply(input).use { AppearanceTestApi.assertNeutral(IntArray(6) { if (value == 0) 0 else 255 }, it) }
        }
    }

    @Test fun sv03SingletonUsesOneActualSampleEvenWithNominalWindow1001() {
        for (value in listOf(0, 200)) AppearanceTestApi.neutral(1, 1, intArrayOf(value)).use { input ->
            apply(input, BinaryRecipe.SauvolaV1(window = WindowPolicy.ExplicitOdd(1001))).use { AppearanceTestApi.assertNeutral(intArrayOf(if (value == 0) 0 else 255), it) }
        }
    }

    @Test fun sv04ClippedCountsAndPopulationVarianceDetermineUnroundedThresholds() {
        assertEquals(43.90625, AppearanceTestApi.helper("Sauvola", "threshold", 100L, 10000L, 2L, .2) as Double, 1e-12)
        assertEquals(92.75775907699786, AppearanceTestApi.helper("Sauvola", "threshold", 300L, 50000L, 3L, .2) as Double, 1e-9)
        assertEquals(131.71875, AppearanceTestApi.helper("Sauvola", "threshold", 300L, 50000L, 2L, .2) as Double, 1e-12)
    }

    @Test fun sv05Bright230PixelCanRemainBlackWithoutAWhiteShortcut() {
        AppearanceTestApi.neutral(15, 15, IntArray(225) { when (it) { 0 -> 0; 112 -> 230; else -> 255 } }).use { input ->
            apply(input, BinaryRecipe.SauvolaV1(window = WindowPolicy.ExplicitOdd(15), k = .10)).use { assertEquals(0, it.getUnsigned(7, 7, 0)) }
        }
    }

    @Test fun sv06AutoWindowUsesShortSideAndExplicitPolicyUnchanged() {
        for ((side, expected) in listOf(1 to 15, 600 to 15, 2480 to 63, 5000 to 101)) assertEquals(expected, CoreApi.function("image.Sauvola", "resolvedWindow", side, side + 1, WindowPolicy.AutoShortSideV1))
        assertEquals(1001, CoreApi.function("image.Sauvola", "resolvedWindow", 1, 1, WindowPolicy.ExplicitOdd(1001)))
    }

    @Test fun sv07IncreasingKDoesNotPretendToPreserveMoreFaintInk() {
        AppearanceTestApi.neutral(3, 1, intArrayOf(100, 100, 80)).use { input ->
            apply(input, BinaryRecipe.SauvolaV1(k = .10)).use { assertEquals(0, it.getUnsigned(2, 0, 0)) }
            apply(input, BinaryRecipe.SauvolaV1(k = .30)).use { assertEquals(255, it.getUnsigned(2, 0, 0)) }
        }
    }

    @Test fun rollingRowsAndColumnsMatchIndependentClippedSquareSums() {
        // Independent integer fixture: 5x5 ramp(0,10,...240), window3, defaultk; borders use2x2/2x3 samples, not replication/reflection.
        val expected = intArrayOf(0,0,0,0,0,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255)
        AppearanceTestApi.neutral(5, 5, IntArray(25) { it * 10 }).use { input -> apply(input, BinaryRecipe.SauvolaV1(window = WindowPolicy.ExplicitOdd(3))).use { AppearanceTestApi.assertNeutral(expected, it) } }
        val varied = intArrayOf(12,200,25,240,40,190,30,180,50,160,60,170,70,150,80,140,90,130,100,120,110,0,220,10,255)
        val rolling = intArrayOf(0,255,0,255,0,255,0,255,0,255,0,255,0,255,0,255,0,255,0,255,255,0,255,0,255)
        AppearanceTestApi.neutral(5, 5, varied).use { input -> apply(input, BinaryRecipe.SauvolaV1(window = WindowPolicy.ExplicitOdd(3))).use { AppearanceTestApi.assertNeutral(rolling, it) } }
    }

    @Test fun binaryAndGrayscaleRejectProtectionBeforeProducingPixels() {
        RgbImage(1, 1, 3, byteArrayOf(1, 2, 3)).use { input -> ProtectionMask(1, 1, floatArrayOf(1f)).use { mask ->
            assertThrows(IllegalArgumentException::class.java) { apply(input, mask = mask) }
            assertThrows(IllegalArgumentException::class.java) { CoreApi.function("image.Grayscale", "apply", input, mask, {}) }
        } }
    }

    @Test fun cancellationAndPaddingNeverReadModifiedOutputIntoStatistics() {
        val bytes = byteArrayOf(0,0,0,100,100,100,77,77,200.toByte(),200.toByte(),200.toByte(),0,0,0)
        RgbImage(2, 2, 8, bytes).use { input ->
            apply(input).use { AppearanceTestApi.assertNeutral(intArrayOf(0,255,255,0), it) }
            val before = input.pixels; var calls = 0
            apply(input, checkpoint = { calls++ }).close()
            var current = 0
            assertThrows(CancellationException::class.java) { apply(input, checkpoint = { if (++current == calls) throw CancellationException() }) }
            assertArrayEquals(before, input.pixels)
        }
    }

    @Test fun workspaceUsesOneBytePlaneAndFourLongLinesNotIntegralImages() {
        val bytes = CoreApi.function("image.Sauvola", "workspaceBytes", 4000, 3000) as Long
        assertTrue(bytes >= 84_128_016L && bytes < 85_000_000L)
        assertThrows(IllegalArgumentException::class.java) { CoreApi.function("image.Sauvola", "workspaceBytes", Int.MAX_VALUE, Int.MAX_VALUE) }
        val gray = CoreApi.function("image.Grayscale", "workspaceBytes", 4000, 3000) as Long
        assertTrue(gray >= 72_000_000L && gray < 73_000_000L)
    }
}
