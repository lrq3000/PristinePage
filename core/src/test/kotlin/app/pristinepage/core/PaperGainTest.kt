package app.pristinepage.core

import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.recipe.AppearanceRecipe
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PaperGainTest {
    private fun apply(input: RgbImage, recipe: AppearanceRecipe.PaperGainV1 = AppearanceRecipe.PaperGainV1(), mask: ProtectionMask? = null, checkpoint: () -> Unit = {}) = AppearanceTestApi.apply("PaperGain", input, recipe, mask, checkpoint)
    private fun helper(name: String, vararg args: Any?) = AppearanceTestApi.helper("PaperGain", name, *args)

    @Test fun pg01TiedPaperSamplesPreserveTheSixLevelFaintMark() {
        AppearanceTestApi.neutral(4, 1, intArrayOf(200, 200, 200, 195)).use { input -> apply(input).use { AppearanceTestApi.assertNeutral(intArrayOf(245, 245, 245, 239), it) } }
    }

    @Test fun pg02BlackStaysBlackAndAlreadyWhiteNeverDarkens() {
        for (value in listOf(0, 255)) AppearanceTestApi.neutral(17, 3, IntArray(51) { value }).use { input -> apply(input).use { AppearanceTestApi.assertNeutral(IntArray(51) { value }, it) } }
    }

    @Test fun pg03ColoredSubstrateIsNeutralizedUnlessProtected() {
        RgbImage(1, 1, 3, byteArrayOf(200.toByte(), 180.toByte(), 160.toByte())).use { input ->
            apply(input).use { assertArrayEquals(byteArrayOf(245.toByte(), 245.toByte(), 245.toByte()), it.toPackedByteArray()) }
            ProtectionMask(1, 1, floatArrayOf(1f)).use { mask -> apply(input, mask = mask).use { assertEquals(input, it) } }
        }
    }

    @Test fun pg04RejectedDarkRegionIsNotInventedWhitePaper() {
        AppearanceTestApi.neutral(32, 16, IntArray(512) { if (it % 32 < 16) 200 else 10 }).use { input ->
            apply(input).use { AppearanceTestApi.assertNeutral(IntArray(512) { if (it % 32 < 16) 245 else 12 }, it) }
        }
    }

    @Test fun pg05FillWavesFreezeValidityAndHaveDefensiveNoSeedBehavior() {
        val paper = floatArrayOf(90f, 90f, 90f, -1f, -1f, -1f, -1f, -1f, -1f, 210f, 210f, 210f)
        helper("fill", paper, booleanArrayOf(true, false, false, true), 4, 1, 150, {})
        assertArrayEquals(floatArrayOf(90f, 90f, 90f, 90f, 90f, 90f, 210f, 210f, 210f, 210f, 210f, 210f), paper, 0f)
        val noSeed = FloatArray(9)
        helper("fill", noSeed, BooleanArray(3), 3, 1, 77, {})
        assertArrayEquals(FloatArray(9) { 77f }, noSeed, 0f)
    }

    @Test fun pg06RegularCellCoordinatesInterpolateGainsNotReciprocalPaper() {
        val gains = floatArrayOf(1f, 1f, 1f, 3f, 3f, 3f)
        val xs = intArrayOf(0, 15, 16, 31)
        val expected = floatArrayOf(1f, 1.9375f, 2.0625f, 3f)
        for (i in xs.indices) assertEquals(expected[i], helper("sampleGain", gains, 2, 1, 16, xs[i], 0, 0) as Float, 0f)
        // Vertical-first interpolation is independently distinguishable from a horizontal-first float32 evaluation.
        val plane = floatArrayOf(1.1f, 0f, 0f, 2.9f, 0f, 0f, 1.7f, 0f, 0f, 2.2f, 0f, 0f)
        assertEquals(1.28369140625f, helper("sampleGain", plane, 2, 2, 16, 9, 8, 0) as Float, 0f)
    }

    @Test fun pg07CandidateQuantizationPrecedesDoubleStrengthAndProtection() {
        AppearanceTestApi.neutral(1, 1, intArrayOf(200)).use { input ->
            apply(input, AppearanceRecipe.PaperGainV1(strength = .5)).use { AppearanceTestApi.assertNeutral(intArrayOf(223), it) }
            ProtectionMask(1, 1, floatArrayOf(.5f)).use { mask -> apply(input, AppearanceRecipe.PaperGainV1(strength = .5), mask).use { AppearanceTestApi.assertNeutral(intArrayOf(211), it) } }
        }
        assertEquals(228, CoreApi.function("image.StageBlend", "channel", 200, 255.0, .5, 0f))
    }

    @Test fun pg08LowerLocalMedianUpperTypicalMedianAndInclusiveTies() {
        val histogram = IntArray(256).also { it[0] = 2; it[100] = 1; it[200] = 1 }
        assertEquals(0, helper("lowerMedian", histogram, 4))
        assertEquals(200, helper("typical", intArrayOf(10, 200)))
        histogram.fill(0); histogram[0] = 1; histogram[200] = 3
        assertEquals(200, helper("brightCutoff", histogram, 4))
        RgbImage(4, 1, 12, byteArrayOf(0, 0, 0, 200.toByte(), 200.toByte(), 200.toByte(), 201.toByte(), 202.toByte(), 187.toByte(), 199.toByte(), 198.toByte(), 213.toByte())).use { input ->
            val paper = FloatArray(3); val luma = IntArray(1); val flat = BooleanArray(1)
            helper("collect", input, 16, 1, 1, paper, luma, flat, {})
            assertArrayEquals(floatArrayOf(200f, 200f, 200f), paper, 0f)
            assertEquals(200, luma[0])
        }
    }

    @Test fun oneSidedFlatRuleAndClippedFrozenBoxPassesRemainDistinct() {
        assertEquals(true, helper("accepted", 20, true, 60))
        assertEquals(false, helper("accepted", 20, false, 60))
        val source = floatArrayOf(0f, 0f, 0f, 90f, 90f, 90f, 210f, 210f, 210f)
        val destination = FloatArray(9)
        helper("smooth", source, destination, 3, 1, {})
        assertArrayEquals(floatArrayOf(45f, 45f, 45f, 100f, 100f, 100f, 150f, 150f, 150f), destination, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 90f, 90f, 90f, 210f, 210f, 210f), source, 0f)
    }

    @Test fun oddLastCellsUseAllPixelsAndRegularSideRatherThanClippedCentroids() {
        AppearanceTestApi.neutral(17, 3, IntArray(51) { if (it % 17 < 16) 100 else 200 }).use { input ->
            apply(input).use { output ->
                // Frozen clipped smoothing makes both grid paper estimates150: gain245/150, including the one-column last cell.
                AppearanceTestApi.assertNeutral(IntArray(51) { if (it % 17 < 16) 163 else 255 }, output)
            }
        }
    }

    @Test fun paddedRowsMasksAndOffResultsHaveIndependentOwnership() {
        val packed = byteArrayOf(200.toByte(), 180.toByte(), 160.toByte(), 128.toByte(), 201.toByte(), 230.toByte(), 250.toByte(), 240.toByte(), 220.toByte(), 80, 40, 120)
        val padded = packed.copyOfRange(0, 6) + byteArrayOf(99, 17) + packed.copyOfRange(6, 12)
        RgbImage(2, 2, 8, padded).use { input ->
            RgbImage(2, 2, 6, packed).use { dense ->
                ProtectionMask(2, 2, floatArrayOf(1f, 0f, .5f, .25f)).use { mask ->
                    apply(input, mask = mask).use { a -> apply(dense, mask = mask).use { b -> assertEquals(a, b); assertEquals(200, a.getUnsigned(0, 0, 0)) } }
                    assertArrayEquals(floatArrayOf(1f, 0f, .5f, .25f), mask.samples, 0f)
                }
            }
            assertArrayEquals(padded, input.pixels)
            val retained = apply(input, AppearanceRecipe.PaperGainV1(strength = 0.0))
            assertNotSame(input, retained)
            retained.close()
            assertArrayEquals(padded, input.pixels)
        }
        val input = RgbImage(2, 2, 8, padded)
        val retained = apply(input, AppearanceRecipe.PaperGainV1(strength = 0.0))
        input.close()
        retained.use { assertArrayEquals(packed, it.toPackedByteArray()) }
    }

    @Test fun maskAlignmentIsValidatedBeforeTheOffBranch() {
        CoreApi.type("image.PaperGain")
        AppearanceTestApi.neutral(2, 1, intArrayOf(100, 200)).use { input -> ProtectionMask(1, 2, floatArrayOf(1f, 1f)).use { mask ->
            assertThrows(IllegalArgumentException::class.java) { apply(input, AppearanceRecipe.PaperGainV1(strength = 0.0), mask) }
        } }
    }

    @Test fun customTargetGainAndRecipeValidationHaveNoHiddenToneCurve() {
        AppearanceTestApi.neutral(1, 1, intArrayOf(100)).use { input -> apply(input, AppearanceRecipe.PaperGainV1(target = 240, maxGain = 1.5)).use { AppearanceTestApi.assertNeutral(intArrayOf(150), it) } }
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, .99, 3.01)) assertThrows(IllegalArgumentException::class.java) { AppearanceRecipe.PaperGainV1(maxGain = bad) }
        assertThrows(IllegalArgumentException::class.java) { AppearanceRecipe.PaperGainV1(target = 239) }
    }

    @Test fun cancellationBeforeWorkBetweenRowsAndAtDeliveryNeverMutatesInput() {
        CoreApi.type("image.PaperGain")
        AppearanceTestApi.neutral(32, 17, IntArray(544) { 100 + it % 100 }).use { input ->
            val before = input.pixels
            var calls = 0
            apply(input, checkpoint = { calls++ }).close()
            assertTrue(calls >= 17 * 3)
            for (stop in listOf(1, 5, calls)) {
                var current = 0
                assertThrows(CancellationException::class.java) { apply(input, checkpoint = { if (++current == stop) throw CancellationException("test cancellation") }) }
                assertArrayEquals(before, input.pixels)
            }
            var offCalls = 0
            assertThrows(CancellationException::class.java) { apply(input, AppearanceRecipe.PaperGainV1(strength = 0.0), checkpoint = { if (++offCalls == 2) throw CancellationException() }) }
            assertEquals(1, AppearanceTestApi.liveLeases(input))
        }
    }

    @Test fun workspaceIncludesDefensiveRgbCopyAndRejectsUnallocatableExtents() {
        val estimate = AppearanceTestApi.bytes("PaperGain", 4000, 3000)
        assertTrue(estimate >= 72_000_000L + 80L * 1200)
        assertTrue(estimate < 73_000_000L)
        assertTrue(AppearanceTestApi.bytes("PaperGain", 1, 1) >= 6L + 1024L)
        for ((w, h) in listOf(0 to 1, 1 to -1, Int.MAX_VALUE to 1, 50000 to 50000)) assertThrows(IllegalArgumentException::class.java) { AppearanceTestApi.bytes("PaperGain", w, h) }
    }
}
