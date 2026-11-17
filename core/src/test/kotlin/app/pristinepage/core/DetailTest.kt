package app.pristinepage.core

import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.recipe.DetailRecipe
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DetailTest {
    private fun apply(input: RgbImage, recipe: DetailRecipe.LumaDetail7V1 = DetailRecipe.LumaDetail7V1(), mask: ProtectionMask? = null, checkpoint: () -> Unit = {}) = CoreApi.function("image.LumaDetail", "apply", input, recipe, mask, checkpoint) as RgbImage

    @Test fun dt01SevenTapReplicatedBytePassesGiveTheLiteralSoftThresholdResult() {
        AppearanceTestApi.neutral(7, 1, intArrayOf(100, 100, 100, 164, 100, 100, 100)).use { input ->
            apply(input).use { AppearanceTestApi.assertNeutral(intArrayOf(100, 99, 95, 172, 95, 99, 100), it) }
        }
    }

    @Test fun dt02NegativeCorrectionsAtBlackAreHeadroomClippedNotWrapped() {
        AppearanceTestApi.neutral(7, 1, intArrayOf(0, 0, 0, 64, 0, 0, 0)).use { input ->
            apply(input).use { AppearanceTestApi.assertNeutral(intArrayOf(0, 0, 0, 72, 0, 0, 0), it) }
        }
    }

    @Test fun twoDimensionalBytePassQuantizationCannotBeDeferredUntilAfterBothAxes() {
        // Independent direct weighted-sum oracle. Deferring the horizontal rounding produces116/109 at the last-row columns1/3 rather than115/108.
        val source = intArrayOf(84, 95, 115, 117, 110, 112, 88, 111, 81, 108, 95, 107)
        val expected = intArrayOf(76, 91, 122, 122, 122, 122, 76, 113, 71, 115, 89, 108)
        AppearanceTestApi.neutral(4, 3, source).use { input ->
            apply(input, DetailRecipe.LumaDetail7V1(amount = .8, thresholdY = 0, maxDelta = 12)).use { AppearanceTestApi.assertNeutral(expected, it) }
        }
    }

    @Test fun dt03SignedHalfTiesAndThresholdEqualityAreSymmetric() {
        for ((residual, expected) in listOf(4 to 1, -4 to -1, 3 to 0, -3 to 0)) {
            assertEquals(expected, AppearanceTestApi.helper("LumaDetail", "delta", 100, 100, 100, residual, DetailRecipe.LumaDetail7V1(amount = .5)))
        }
    }

    @Test fun dt04OneCommonRgbHeadroomBoundPrecedesSignedRounding() {
        assertEquals(5, AppearanceTestApi.helper("LumaDetail", "delta", 250, 100, 100, 33, DetailRecipe.LumaDetail7V1()))
        assertEquals(-2, AppearanceTestApi.helper("LumaDetail", "delta", 2, 100, 200, -33, DetailRecipe.LumaDetail7V1()))
    }

    @Test fun dt05OffAndConstantPathsAreExactAndIndependentlyOwned() {
        val packed = byteArrayOf(200.toByte(), 100, 50)
        RgbImage(1, 1, 3, packed).use { input ->
            for (recipe in listOf(DetailRecipe.LumaDetail7V1(), DetailRecipe.LumaDetail7V1(amount = 0.0), DetailRecipe.LumaDetail7V1(maxDelta = 0), DetailRecipe.LumaDetail7V1(strength = 0.0))) {
                apply(input, recipe).use { assertNotSame(input, it); assertArrayEquals(packed, it.toPackedByteArray()) }
            }
        }
    }

    @Test fun protectionIsRefusedBeforeAnyOffOrPartialRender() {
        RgbImage(1, 1, 3, byteArrayOf(1, 2, 3)).use { input -> ProtectionMask(1, 1, floatArrayOf(1f)).use { mask ->
            assertThrows(IllegalArgumentException::class.java) { apply(input, DetailRecipe.LumaDetail7V1(strength = 0.0), mask) }
        } }
    }

    @Test fun cancellationDuringRowsAndFinalDeliveryLeavesOriginalLive() {
        AppearanceTestApi.neutral(7, 3, IntArray(21) { if (it % 7 == 3) 164 else 100 }).use { input ->
            val before = input.pixels; var calls = 0
            apply(input, checkpoint = { calls++ }).close()
            for (stop in listOf(2, calls)) {
                var current = 0
                assertThrows(CancellationException::class.java) { apply(input, checkpoint = { if (++current == stop) throw CancellationException() }) }
                assertArrayEquals(before, input.pixels)
            }
            assertEquals(1, AppearanceTestApi.liveLeases(input))
        }
    }

    @Test fun workspaceChargesTwoBytePlanesAndBothOutputCopies() {
        val bytes = CoreApi.function("image.LumaDetail", "workspaceBytes", 4000, 3000) as Long
        assertTrue(bytes >= 96_000_000L && bytes < 97_000_000L)
        assertThrows(IllegalArgumentException::class.java) { CoreApi.function("image.LumaDetail", "workspaceBytes", 50000, 50000) }
    }
}
