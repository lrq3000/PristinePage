package app.pristinepage.core

import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.recipe.AppearanceRecipe
import java.util.concurrent.CancellationException
import kotlin.math.ln
import org.junit.Assert.*
import org.junit.Test

class NaturalLightnessTest {
    private fun apply(input: RgbImage, recipe: AppearanceRecipe.NaturalLV1 = AppearanceRecipe.NaturalLV1(), mask: ProtectionMask? = null, checkpoint: () -> Unit = {}) = AppearanceTestApi.apply("NaturalLightness", input, recipe, mask, checkpoint)
    private fun helper(name: String, vararg args: Any?) = AppearanceTestApi.helper("NaturalLightness", name, *args)

    @Test fun nl01FlatNeutralAndColoredInputsAreExactAtEveryStrength() {
        for (rgb in listOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255), intArrayOf(200, 180, 160), intArrayOf(40, 100, 220))) {
            for ((w, h) in listOf(1 to 1, 1 to 17, 17 to 1, 31 to 47)) {
                RgbImage(w, h, w * 3, ByteArray(w * h * 3) { rgb[it % 3].toByte() }).use { input ->
                    for (strength in listOf(0.0, .1, .5, 1.0)) apply(input, AppearanceRecipe.NaturalLV1(strength = strength)).use { assertEquals(input, it) }
                }
            }
        }
    }

    @Test fun nl02LiteralDefaultAndHalfStrengthCandidates() {
        AppearanceTestApi.neutral(4, 1, intArrayOf(100, 100, 200, 200)).use { input ->
            apply(input).use { AppearanceTestApi.assertNeutral(intArrayOf(108, 111, 188, 191), it) }
            apply(input, AppearanceRecipe.NaturalLV1(strength = .5)).use { AppearanceTestApi.assertNeutral(intArrayOf(104, 106, 194, 196), it) }
            assertArrayEquals(byteArrayOf(100, 100, 100, 100, 100, 100, 200.toByte(), 200.toByte(), 200.toByte(), 200.toByte(), 200.toByte(), 200.toByte()), input.pixels)
        }
    }

    @Test fun nl03OneWorkingSampleReturnsTheOriginalNotAnEpsilonNormalizedField() {
        AppearanceTestApi.neutral(2, 1, intArrayOf(100, 200)).use { input -> apply(input).use { assertEquals(input, it) } }
        AppearanceTestApi.neutral(2, 2, intArrayOf(100, 200, 40, 240)).use { input -> apply(input).use { assertEquals(input, it) } }
        // Nonflat source but identical area cells: the field-flat guard is independent of the source-flat guard.
        AppearanceTestApi.neutral(4, 1, intArrayOf(100, 200, 100, 200)).use { input -> apply(input).use { assertEquals(input, it) } }
    }

    @Test fun threeDistinctScalesUseNextOddKernelsAndTheWholeOddSizedSource() {
        // Independent direct-sum Python oracle (not periodic-prefix code): width201→working101, kernels[3,11,51], clipped final area block and full-image mean.
        val expected = intArrayOf(60,89,120,149,177,201,89,115,145,172,196,88,113,141,169,193,81,108,138,164,189,80,106,134,163,186,72,100,130,157,181,72,98,126,156,178,63,91,122,151,179,201,90,117,147,173,197,89,115,143,171,194,81,110,140,165,190,81,107,136,165,186,73,102,133,158,182,73,99,128,158,178,64,94,124,153,181,202,91,120,149,174,198,90,116,145,173,194,82,112,142,167,191,82,108,137,166,187,74,105,135,159,184,75,101,130,160,179,65,97,127,152,176,66,93,122,151,175,200,92,118,147,174,195,84,114,143,168,192,84,110,139,167,187,76,107,136,161,185,76,103,133,160,179,68,99,129,153,177,68,95,124,152,177,201,93,120,149,175,195,86,116,144,169,193,85,112,141,167,188,78,109,137,162,186,78,105,135,160,180,71,102,130,155,179,70,98,127,152,171,62,93,123,151,176,196,88,118,146,174,200)
        AppearanceTestApi.neutral(201, 1, IntArray(201) { 40 + (it * 37) % 190 }).use { input -> apply(input).use { AppearanceTestApi.assertNeutral(expected, it) } }
    }

    @Test fun nl04AreaPlusOnePrecedesLogAndOddBlocksUseActualCount() {
        val z = FloatArray(4)
        helper("areaLog", floatArrayOf(0f, 2f, 8f, 4f, 6f, 10f, 12f, 14f, 16f), 3, 3, z, {})
        assertArrayEquals(floatArrayOf(1.3862944f, 2.3025851f, 2.6390574f, 2.8332133f), z, .000001f)
        val horizontal = FloatArray(2); val filtered = FloatArray(2)
        helper("box", floatArrayOf(0f, ln(9.0).toFloat()), 2, 1, 3, horizontal, filtered, DoubleArray(5), {})
        assertArrayEquals(floatArrayOf(1.4648163f, .73240817f), filtered, .000001f)
        assertEquals(1.0986123f, (filtered[0] + filtered[1]) / 2f, .000001f)
        assertTrue(kotlin.math.abs((filtered[0] + filtered[1]) / 2f - 1.6094379f) > .5f)
    }

    @Test fun areaReductionPromotesSamplesBeforePlusOneAndRoundsOnlyItsStoredMean() {
        // Independent CIE conversion of neutral11/13, followed by Double(+1)/mean and one Float area assignment. An early Float(+1) produces the distinct Z2.250185012817383.
        val z = FloatArray(1)
        helper("areaLog", floatArrayOf(7.70842981338501f, 9.270556449890137f), 2, 1, z, {})
        assertEquals(2.250185251235962f, z[0], 0f)
    }

    @Test fun residualReductionKeepsDoublePrecisionUntilTheCompleteThreeScaleMean() {
        // Independent direct reflected sums: q kernels3/3/5. Premature Float subtraction/accumulation crosses the red-channel half tie at source x6, producing(1,0,0) instead of(0,0,0).
        val cells = intArrayOf(3, 254, 252, 0, 1, 253, 2, 250, 255)
        AppearanceTestApi.neutral(18, 1, IntArray(18) { cells[it / 2] }).use { input ->
            apply(input, AppearanceRecipe.NaturalLV1(amplitudeL255 = 60.0, lightnessBlend = 0.002721958547402176)).use { output ->
                for (channel in 0..2) assertEquals(0, output.getUnsigned(6, 0, channel))
            }
        }
    }

    @Test fun reflectedPrefixBoxesHandleLargeWindowsSingletonsAndBothAxes() {
        val input = floatArrayOf(0f, 3f, 9f)
        val horizontal = FloatArray(3); val result = FloatArray(3)
        helper("box", input, 3, 1, 9, horizontal, result, DoubleArray(7), {})
        assertArrayEquals(floatArrayOf(10f / 3f, 11f / 3f, 13f / 3f), result, .000001f)
        helper("box", input, 1, 3, 9, horizontal, result, DoubleArray(7), {})
        assertArrayEquals(floatArrayOf(10f / 3f, 11f / 3f, 13f / 3f), result, .000001f)
        val singleton = FloatArray(1)
        helper("box", floatArrayOf(7f), 1, 1, 1_000_000_001, FloatArray(1), singleton, DoubleArray(3), {})
        assertEquals(7f, singleton[0], 0f)
        assertArrayEquals(floatArrayOf(0f, 3f, 9f), input, 0f)
    }

    @Test fun nl05LightnessMixHasNoExtraAutocontrast() {
        assertEquals(108.25f, helper("mixLightness", 100f, 150.0, 0f, 45.0, .30) as Float, 0f)
        assertEquals(121.75f, helper("mixLightness", 100f, 150.0, 1f, 45.0, .30) as Float, 0f)
        assertEquals(0f, helper("mixLightness", 0f, 0.0, 0f, 60.0, .60) as Float, 0f)
        assertEquals(255f, helper("mixLightness", 255f, 255.0, 1f, 60.0, .60) as Float, 0f)
    }

    @Test fun twoDimensionalBoxStoresTheHorizontalPassBeforeVerticalReflection() {
        val horizontal = FloatArray(6); val result = FloatArray(6)
        helper("box", floatArrayOf(0f, 3f, 9f, 12f, 15f, 21f), 3, 2, 3, horizontal, result, DoubleArray(7), {})
        assertArrayEquals(floatArrayOf(2f, 4f, 5f, 14f, 16f, 17f), horizontal, 0f)
        assertArrayEquals(floatArrayOf(10f, 12f, 13f, 6f, 8f, 9f), result, 0f)
    }

    @Test fun cancellationCanInterruptTheLongVerticalPrefixBeforeItsLastRow() {
        val prefix = DoubleArray(19) { -999.0 }
        var calls = 0
        assertThrows(CancellationException::class.java) {
            helper("box", FloatArray(9) { it.toFloat() }, 1, 9, 3, FloatArray(9), FloatArray(9), prefix, { if (++calls == 11) throw CancellationException() })
        }
        // Nine horizontal rows, then vertical-column admission: cancellation must still interrupt prefix formation, not only the later output scan.
        assertEquals(-999.0, prefix[16], 0.0)
    }

    @Test fun nl06OffBlendAndAlignedProtectionHaveExactOwnershipAndSamples() {
        val packed = byteArrayOf(250.toByte(), 10, 0, 10, 240.toByte(), 20, 40, 80, 250.toByte(), 150.toByte(), 100, 80)
        RgbImage(4, 1, 12, packed).use { input ->
            ProtectionMask(4, 1, floatArrayOf(1f, 1f, 1f, 1f)).use { mask -> apply(input, mask = mask).use { assertEquals(input, it) } }
            for (recipe in listOf(AppearanceRecipe.NaturalLV1(strength = 0.0), AppearanceRecipe.NaturalLV1(lightnessBlend = 0.0))) {
                val output = apply(input, recipe); assertNotSame(input, output); output.close(); assertArrayEquals(packed, input.pixels)
            }
            ProtectionMask(4, 1, floatArrayOf(1f, 0f, .5f, 1f)).use { mask -> apply(input, mask = mask).use { output ->
                for (channel in 0..2) { assertEquals(input.getUnsigned(0, 0, channel), output.getUnsigned(0, 0, channel)); assertEquals(input.getUnsigned(3, 0, channel), output.getUnsigned(3, 0, channel)) }
                assertArrayEquals(floatArrayOf(1f, 0f, .5f, 1f), mask.samples, 0f)
            } }
        }
        val source = RgbImage(4, 1, 12, packed)
        val output = apply(source, AppearanceRecipe.NaturalLV1(strength = 0.0)); source.close()
        output.use { assertArrayEquals(packed, it.toPackedByteArray()) }
    }

    @Test fun zeroAmplitudeStillMovesNonflatLightnessTowardTheGlobalMean() {
        AppearanceTestApi.neutral(4, 1, intArrayOf(100, 100, 200, 200)).use { input ->
            apply(input, AppearanceRecipe.NaturalLV1(amplitudeL255 = 0.0)).use { AppearanceTestApi.assertNeutral(intArrayOf(114, 114, 184, 184), it) }
        }
    }

    @Test fun cieLabUsesFloatStorageClampedWhiteAndOriginalChromaticAxes() {
        val lab = FloatArray(3)
        AppearanceTestApi.helper("ColorLab", "forward", 100, 100, 100, lab)
        assertEquals(108.0552444458f, lab[0], .000001f)
        AppearanceTestApi.helper("ColorLab", "forward", 200, 200, 200, lab)
        assertEquals(205.5404205322f, lab[0], .000001f)
        AppearanceTestApi.helper("ColorLab", "forward", 255, 255, 255, lab)
        assertEquals(255f, lab[0], 0f)
        for (rgb in listOf(intArrayOf(200, 180, 160), intArrayOf(255, 0, 0), intArrayOf(0, 0, 255), intArrayOf(0, 255, 0))) {
            AppearanceTestApi.helper("ColorLab", "forward", rgb[0], rgb[1], rgb[2], lab)
            val candidate = AppearanceTestApi.helper("ColorLab", "inverse", lab[0], lab[1], lab[2]) as Int
            assertEquals((rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2], candidate)
        }
    }

    @Test fun halfPixelUpsamplingClampsEdgesAndRetainsWorkingFloatPrecision() {
        val n = floatArrayOf(0f, 1f)
        val expected = floatArrayOf(0f, .25f, .75f, 1f)
        for (x in 0..3) assertEquals(expected[x], helper("upsample", n, 2, 1, 4, 1, x, 0) as Float, 0f)
        assertEquals(.5f, helper("upsample", floatArrayOf(0f, 1f, 1f, 0f), 2, 2, 3, 3, 1, 1) as Float, 0f)
    }

    @Test fun oddDimensionsPaddingAndFractionalMasksDoNotChangeSourceStatistics() {
        val w = 5; val h = 3
        val packed = ByteArray(w * h * 3) { ((it * 37 + 61) % 256).toByte() }
        val padded = ByteArray((w * 3 + 4) * h) { 97 }
        for (y in 0 until h) packed.copyInto(padded, y * (w * 3 + 4), y * w * 3, (y + 1) * w * 3)
        RgbImage(w, h, w * 3, packed).use { dense -> RgbImage(w, h, w * 3 + 4, padded).use { input ->
            apply(dense).use { expected -> apply(input).use { assertEquals(expected, it) } }
            ProtectionMask(w, h, FloatArray(w * h) { if (it % 2 == 0) 1f else 0f }).use { mask ->
                apply(input, mask = mask).use { protected -> apply(input).use { ordinary ->
                    for (y in 0 until h) for (x in 0 until w) for (c in 0..2) assertEquals(if ((y * w + x) % 2 == 0) input.getUnsigned(x, y, c) else ordinary.getUnsigned(x, y, c), protected.getUnsigned(x, y, c))
                } }
            }
            assertArrayEquals(padded, input.pixels); assertArrayEquals(packed, dense.pixels)
        } }
    }

    @Test fun invalidMaskAlignmentRejectsBeforeEitherExactOffBranch() {
        CoreApi.type("image.NaturalLightness")
        AppearanceTestApi.neutral(2, 1, intArrayOf(100, 200)).use { input -> ProtectionMask(1, 2, floatArrayOf(1f, 1f)).use { mask ->
            for (recipe in listOf(AppearanceRecipe.NaturalLV1(strength = 0.0), AppearanceRecipe.NaturalLV1(lightnessBlend = 0.0))) assertThrows(IllegalArgumentException::class.java) { apply(input, recipe, mask) }
        } }
    }

    @Test fun cancellationAndOrdinaryCallbackFailuresPropagateWithoutMutatingSource() {
        CoreApi.type("image.NaturalLightness")
        AppearanceTestApi.neutral(9, 7, IntArray(63) { 30 + it * 3 }).use { input ->
            val before = input.pixels
            var calls = 0; apply(input, checkpoint = { calls++ }).close()
            assertTrue(calls >= 7 * 3)
            for (stop in listOf(1, 4, calls)) {
                var current = 0
                assertThrows(CancellationException::class.java) { apply(input, checkpoint = { if (++current == stop) throw CancellationException() }) }
                assertArrayEquals(before, input.pixels)
            }
            assertThrows(IllegalStateException::class.java) { apply(input, checkpoint = { throw IllegalStateException("injected callback failure") }) }
            var offCalls = 0
            assertThrows(CancellationException::class.java) { apply(input, AppearanceRecipe.NaturalLV1(strength = 0.0), checkpoint = { if (++offCalls == 2) throw CancellationException() }) }
            assertEquals(1, AppearanceTestApi.liveLeases(input))
        }
    }

    @Test fun workspaceChargesOneLPlaneFourWorkingPlanesAndBothOutputCopies() {
        val estimate = AppearanceTestApi.bytes("NaturalLightness", 4000, 3000)
        assertTrue(estimate >= 168_000_000L)
        assertTrue(estimate < 169_000_000L)
        assertTrue(AppearanceTestApi.bytes("NaturalLightness", 5, 3) >= 10L * 15 + 16L * 6)
        for ((w, h) in listOf(0 to 1, 1 to -1, Int.MAX_VALUE to 1, 50000 to 50000)) assertThrows(IllegalArgumentException::class.java) { AppearanceTestApi.bytes("NaturalLightness", w, h) }
    }

    @Test fun customNaturalParametersRejectNonfiniteAndOutOfRangeValues() {
        CoreApi.type("image.NaturalLightness")
        for (bad in listOf(-.01, 60.01, Double.NaN, Double.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) { AppearanceRecipe.NaturalLV1(amplitudeL255 = bad) }
        for (bad in listOf(-.01, .601, Double.NaN, Double.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) { AppearanceRecipe.NaturalLV1(lightnessBlend = bad) }
        for (bad in listOf(-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) { AppearanceRecipe.NaturalLV1(strength = bad) }
    }
}
