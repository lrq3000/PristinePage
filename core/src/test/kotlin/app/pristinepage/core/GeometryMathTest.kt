package app.pristinepage.core

import app.pristinepage.core.image.CoverageMask
import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.pipeline.RectPx
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.ProviderReference
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.core.recipe.TargetRule
import org.junit.Assert.*
import org.junit.Test

class GeometryMathTest {
    // The existing reflection bridge makes absent features fail as JUnit assertions, not test-compilation errors; every expected numeric value below is an independent literal oracle.
    private fun sample(name: String, vararg args: Any): Any? = CoreApi.function("geometry.BilinearSampler", name, *args)
    private fun validate(quad: QuadPx, size: PixelSize) { CoreApi.function("geometry.QuadValidation", "validate", quad, size) }
    private fun base(recipe: GeometryRecipe): PixelSize = CoreApi.function("geometry.GeometrySizing", "baseTarget", recipe) as PixelSize
    private fun target(recipe: GeometryRecipe): PixelSize = CoreApi.function("geometry.GeometrySizing", "target", recipe) as PixelSize
    private fun output(recipe: GeometryRecipe, purpose: RenderPurpose, limit: Int): OutputSpec = CoreApi.function("geometry.GeometrySizing", "output", recipe, purpose, limit) as OutputSpec
    private fun binding(width: Int, height: Int) = SourceBinding("a".repeat(64), PixelSize(width, height), "test-srgb-v1")
    private fun quad(vararg xy: Double) = QuadPx(PointPx(xy[0], xy[1]), PointPx(xy[2], xy[3]), PointPx(xy[4], xy[5]), PointPx(xy[6], xy[7]))
    private val full = quad(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
    private val projective = quad(20.0 / 159, 10.0 / 119, 127.27272727272727 / 159, 13.636363636363635 / 119, 115.38461538461539 / 159, 80.76923076923076 / 119, 25.0 / 159, 83.33333333333334 / 119)
    private fun scalarImage() = RgbImage(2, 2, 6, byteArrayOf(0, 0, 0, 100, 100, 100, 200.toByte(), 200.toByte(), 200.toByte(), 255.toByte(), 255.toByte(), 255.toByte()))

    @Test fun whiteBorderScalarVectorsUseIndependentNeighborsAndFloor() {
        scalarImage().use { rgb ->
            for (channel in 0..2) {
                assertEquals(119, sample("rgb", rgb, 0.25, 0.5, channel))
                assertEquals(64, sample("rgb", rgb, -0.25, 0.0, channel))
                assertEquals(139, sample("rgb", rgb, 1.25, 0.0, channel))
                assertEquals(255, sample("rgb", rgb, -1.0, 0.0, channel))
            }
        }
    }

    @Test fun unsignedChannelsIgnoreStridePaddingAndLeaveAllInputBytesUnchanged() {
        val packed = byteArrayOf(0, 128.toByte(), 255.toByte(), 100, 129.toByte(), 240.toByte(), 200.toByte(), 250.toByte(), 130.toByte(), 255.toByte(), 254.toByte(), 200.toByte())
        val padded = packed.copyOfRange(0, 6) + byteArrayOf(17, 99, 42) + packed.copyOfRange(6, 12) + byteArrayOf(88, 77, 66)
        val before = padded.copyOf()
        RgbImage(2, 2, 9, padded).use { rgb ->
            RgbImage(2, 2, 6, packed).use { dense ->
                val expected = intArrayOf(119, 190, 199)
                for (channel in 0..2) {
                    assertEquals(expected[channel], sample("rgb", rgb, 0.25, 0.5, channel))
                    assertEquals(expected[channel], sample("rgb", dense, 0.25, 0.5, channel))
                    assertEquals(expected[channel], sample("resizedRgb", rgb, 0.25, 0.5, channel))
                }
                assertArrayEquals(before, rgb.pixels)
                assertArrayEquals(packed, rgb.toPackedByteArray())
            }
        }
        assertArrayEquals(before, padded)
    }

    @Test fun rgbQuantizesOnceAfterTheFourDoubleContributions() {
        RgbImage(2, 2, 6, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1)).use { rgb ->
            // The true candidate is .25; rounding each horizontal pass first would incorrectly produce one.
            assertEquals(0, sample("rgb", rgb, 0.5, 0.5, 0))
            assertEquals(1, sample("rgb", rgb, 1.0, 0.5, 0))
        }
        // Independent IEEE-754 boundary vectors: regrouping the bottom terms yields 176 instead of 175; nested two-pass interpolation or fused multiply-add yields 51 instead of 52.
        RgbImage(2, 2, 6, byteArrayOf(181.toByte(), 0, 0, 150.toByte(), 0, 0, 145.toByte(), 0, 0, 246.toByte(), 0, 0)).use { rgb -> assertEquals(175, sample("rgb", rgb, 0.10674157303370811, 0.1, 0)) }
        RgbImage(2, 2, 6, byteArrayOf(74, 0, 0, 12, 0, 0, 119, 0, 0, 103, 0, 0)).use { rgb -> assertEquals(52, sample("rgb", rgb, 0.5965909090909092, 0.2, 0)) }
    }

    @Test fun protectionRetainsFloatPrecisionAndOutsideOneWithoutMutatingSamples() {
        val values = floatArrayOf(0.2f, 0.6f, 1f, 0f)
        ProtectionMask(2, 2, values).use { mask ->
            assertEquals(0.525f, sample("protection", mask, 0.25, 0.5) as Float, 0.00000006f)
            assertEquals(0.4f, sample("protection", mask, -0.25, 0.0) as Float, 0.00000006f)
            assertEquals(1f, sample("protection", mask, -1.0, 0.0))
            assertArrayEquals(values, mask.samples, 0f)
        }
        assertArrayEquals(floatArrayOf(0.2f, 0.6f, 1f, 0f), values, 0f)
    }

    @Test fun coverageOutsideZeroIsNotProtectionOrPixelDerivedSupport() {
        CoverageMask(2, 2, floatArrayOf(0.2f, 0.6f, 1f, 0f)).use { mask ->
            assertEquals(0.525f, sample("coverage", mask, 0.25, 0.5) as Float, 0.00000006f)
            assertEquals(0.15f, sample("coverage", mask, -0.25, 0.0) as Float, 0.00000002f)
            assertEquals(0f, sample("coverage", mask, -1.0, 0.0))
            assertArrayEquals(floatArrayOf(0.2f, 0.6f, 1f, 0f), mask.samples, 0f)
        }
        assertEquals(1.0, sample("support", PixelSize(2, 2), 0.25, 0.5))
        assertEquals(0.75, sample("support", PixelSize(2, 2), -0.25, 0.0))
        assertEquals(0.5625, sample("support", PixelSize(2, 2), -0.25, -0.25))
    }

    @Test fun exactIntegerEdgesIgnoreTheirZeroWeightOutsideNeighbors() {
        scalarImage().use { rgb ->
            assertEquals(0, sample("rgb", rgb, 0.0, 0.0, 0))
            assertEquals(100, sample("rgb", rgb, 1.0, 0.0, 0))
            assertEquals(200, sample("rgb", rgb, 0.0, 1.0, 0))
            assertEquals(255, sample("rgb", rgb, 1.0, 1.0, 0))
        }
        ProtectionMask(2, 2, floatArrayOf(0f, 0.25f, 0.75f, 0.125f)).use { mask -> assertEquals(0.125f, sample("protection", mask, 1.0, 1.0)) }
        CoverageMask(2, 2, floatArrayOf(0f, 0.25f, 0.75f, 0.125f)).use { mask -> assertEquals(0.125f, sample("coverage", mask, 1.0, 1.0)) }
        assertEquals(1.0, sample("support", PixelSize(2, 2), 1.0, 1.0))
    }

    @Test fun singletonPlanesHaveRealBilinearSupportWithoutDivisionByZero() {
        RgbImage(1, 1, 3, byteArrayOf(128.toByte(), 128.toByte(), 128.toByte())).use { rgb ->
            assertEquals(128, sample("rgb", rgb, 0.0, 0.0, 0))
            assertEquals(160, sample("rgb", rgb, -0.25, 0.0, 0))
            assertEquals(207, sample("rgb", rgb, 0.25, 0.5, 0))
            assertEquals(128, sample("resizedRgb", rgb, -Double.MAX_VALUE, Double.MAX_VALUE, 0))
        }
        ProtectionMask(1, 1, floatArrayOf(0.25f)).use { mask -> assertEquals(0.71875f, sample("protection", mask, 0.25, 0.5)) }
        CoverageMask(1, 1, floatArrayOf(0.25f)).use { mask -> assertEquals(0.09375f, sample("coverage", mask, 0.25, 0.5)) }
        assertEquals(0.375, sample("support", PixelSize(1, 1), 0.25, 0.5))
        RgbImage(1, 3, 3, byteArrayOf(0, 0, 0, 100, 100, 100, 200.toByte(), 200.toByte(), 200.toByte())).use { rgb -> assertEquals(150, sample("rgb", rgb, 0.0, 1.5, 0)) }
        RgbImage(3, 1, 9, byteArrayOf(0, 0, 0, 100, 100, 100, 200.toByte(), 200.toByte(), 200.toByte())).use { rgb -> assertEquals(150, sample("rgb", rgb, 1.5, 0.0, 0)) }
    }

    @Test fun extremeFiniteCoordinatesCannotOverflowNeighborIntegerConversion() {
        scalarImage().use { rgb ->
            ProtectionMask(2, 2, floatArrayOf(0f, 0f, 0f, 0f)).use { protection ->
                CoverageMask(2, 2, floatArrayOf(1f, 1f, 1f, 1f)).use { coverage ->
                    for (coordinate in listOf(-Double.MAX_VALUE, Double.MAX_VALUE, Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())) {
                        for ((x, y) in listOf(coordinate to 0.0, 0.0 to coordinate)) {
                            assertEquals(255, sample("rgb", rgb, x, y, 0))
                            assertEquals(1f, sample("protection", protection, x, y))
                            assertEquals(0f, sample("coverage", coverage, x, y))
                            assertEquals(0.0, sample("support", PixelSize(2, 2), x, y))
                        }
                    }
                }
            }
        }
        assertEquals(1.0, sample("support", PixelSize(Int.MAX_VALUE, 2), 2147483646.0, 0.0))
        assertEquals(0.0, sample("support", PixelSize(Int.MAX_VALUE, 2), 2147483647.0, 0.0))
    }

    @Test fun everySamplerRejectsNonfiniteCoordinatesBeforeOutsideShortcuts() {
        scalarImage().use { rgb ->
            ProtectionMask(2, 2, floatArrayOf(0f, 0f, 0f, 0f)).use { protection ->
                CoverageMask(2, 2, floatArrayOf(1f, 1f, 1f, 1f)).use { coverage ->
                    for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                        for ((x, y) in listOf(bad to -Double.MAX_VALUE, Double.MAX_VALUE to bad)) {
                            for (name in listOf("rgb", "resizedRgb")) assertThrows(IllegalArgumentException::class.java) { sample(name, rgb, x, y, 0) }
                            assertThrows(IllegalArgumentException::class.java) { sample("protection", protection, x, y) }
                            assertThrows(IllegalArgumentException::class.java) { sample("coverage", coverage, x, y) }
                            assertThrows(IllegalArgumentException::class.java) { sample("support", PixelSize(2, 2), x, y) }
                        }
                    }
                }
            }
        }
    }

    @Test fun invalidRgbChannelsRejectEvenWhenTheCoordinateIsFullyOutside() {
        scalarImage().use { rgb ->
            for (channel in listOf(-1, 3, Int.MAX_VALUE)) for (name in listOf("rgb", "resizedRgb")) {
                assertThrows(IllegalArgumentException::class.java) { sample(name, rgb, 0.25, 0.5, channel) }
                assertThrows(IllegalArgumentException::class.java) { sample(name, rgb, Double.MAX_VALUE, 0.0, channel) }
            }
        }
    }

    @Test fun resizedRgbUsesClampBorderOnAlreadyComputedSourceCoordinates() {
        scalarImage().use { rgb ->
            assertEquals(0, sample("resizedRgb", rgb, -0.25, 0.0, 0))
            assertEquals(100, sample("resizedRgb", rgb, 1.25, 0.0, 0))
            assertEquals(64, sample("rgb", rgb, -0.25, 0.0, 0))
            assertEquals(139, sample("rgb", rgb, 1.25, 0.0, 0))
            assertEquals(178, sample("resizedRgb", rgb, Double.MAX_VALUE, 0.5, 0))
            assertEquals(100, sample("resizedRgb", rgb, -Double.MAX_VALUE, 0.5, 0))
            assertEquals(200, sample("resizedRgb", rgb, -Double.MAX_VALUE, Double.MAX_VALUE, 0))
            assertEquals(119, sample("resizedRgb", rgb, 0.25, 0.5, 0))
        }
    }

    @Test fun fullFrameThreeByTwoAndExactOnePixelEdgesAreValid() {
        validate(quad(0.0, 0.0, 2.0, 0.0, 2.0, 1.0, 0.0, 1.0), PixelSize(3, 2))
        validate(quad(0.0, 0.0, 400.0, 0.0, 400.0, 300.0, 0.0, 300.0), PixelSize(401, 301))
        validate(quad(2.0, 2.0, 3.0, 2.0, 3.0, 4.0, 2.0, 4.0), PixelSize(5, 5))
    }

    @Test fun labeledBowTieReflectionRepeatedCornersAndTouchesAreNotRepaired() {
        val invalid = listOf(
            quad(0.0, 0.0, 4.0, 4.0, 4.0, 0.0, 0.0, 4.0),
            quad(0.0, 0.0, 0.0, 4.0, 4.0, 4.0, 4.0, 0.0),
            quad(0.0, 0.0, 0.0, 0.0, 4.0, 4.0, 0.0, 4.0),
            quad(0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 0.0, 4.0),
            quad(0.0, 0.0, 4.0, 0.0, 2.0, 0.0, 0.0, 4.0),
            quad(0.0, 0.0, 4.0, 0.0, 1.0, 1.0, 0.0, 4.0),
        )
        for (q in invalid) assertThrows(IllegalArgumentException::class.java) { validate(q, PixelSize(5, 5)) }
    }

    @Test fun quadAreaAtMostOneAndSubpixelEdgesRejectIndependently() {
        val invalid = listOf(
            quad(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0),
            quad(1.0, 0.0, 1.4, 1.2, 1.0, 2.4, 0.6, 1.2),
            quad(0.0, 0.0, 0.75, 0.0, 0.75, 3.0, 0.0, 3.0),
        )
        for (q in invalid) assertThrows(IllegalArgumentException::class.java) { validate(q, PixelSize(5, 5)) }
    }

    @Test fun quadBoundsAndSingletonSourceRejectInsteadOfClamping() {
        for (q in listOf(quad(-0.000000001, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0), quad(0.0, 0.0, 4.000000001, 0.0, 4.0, 4.0, 0.0, 4.0))) {
            assertThrows(IllegalArgumentException::class.java) { validate(q, PixelSize(5, 5)) }
        }
        assertThrows(IllegalArgumentException::class.java) { validate(full, PixelSize(1, 5)) }
        // PointPx rejects nonfinite coordinates at the typed-input boundary, before a QuadPx can carry them.
        assertThrows(IllegalArgumentException::class.java) { PointPx(Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { PointPx(0.0, Double.POSITIVE_INFINITY) }
    }

    @Test fun uncertainPositiveTurnRejectsEvenWithLargeAreaAndLongEdges() {
        val uncertain = quad(0.0, 0.0, 100.0, 0.0, 200.0, 0.000000001, 0.0, 10.0)
        assertThrows(IllegalArgumentException::class.java) { validate(uncertain, PixelSize(401, 301)) }
        validate(quad(0.0, 0.0, 100.0, 0.0, 200.0, 0.000001, 0.0, 10.0), PixelSize(401, 301))
    }

    @Test fun translatedQuadUsesStableAreaWithoutChangingItsLabels() {
        val q = quad(999999990.0, 999999990.0, 1000000010.0, 999999990.0, 1000000010.0, 1000000010.0, 999999990.0, 1000000010.0)
        // At this source scale the cross uncertainty is about two million, so a 400px² quad must conservatively reject despite stable area arithmetic.
        assertThrows(IllegalArgumentException::class.java) { validate(q, PixelSize(1000000020, 1000000020)) }
        validate(quad(999990000.0, 999990000.0, 1000000000.0, 999990000.0, 1000000000.0, 1000000000.0, 999990000.0, 1000000000.0), PixelSize(1000000020, 1000000020))
    }

    @Test fun projectiveLiteralCornersResolveNaturalAndFixedTargetsBeforeExplicitPurposeLimit() {
        val edge = GeometryRecipe.Perspective(binding(160, 120), projective)
        assertEquals(PixelSize(109, 75), base(edge))
        val fixed = edge.copy(targetRule = TargetRule.FixedRatio(1.0))
        assertEquals(PixelSize(75, 75), base(fixed))
        for (purpose in RenderPurpose.entries) assertEquals(OutputSpec(PixelSize(11, 11), purpose), output(fixed, purpose, 11))
    }

    @Test fun manualTargetUsesTheSameChordsNotCurvedBoundaryArcLengths() {
        val recipe = GeometryRecipe.ManualCurve(binding(160, 120), projective, PointPx(0.4, 0.2), PointPx(0.4, 0.6), depth = 0.4)
        assertEquals(PixelSize(109, 75), base(recipe))
        assertEquals(PixelSize(75, 75), base(recipe.copy(targetRule = TargetRule.FixedRatio(1.0))))
    }

    @Test fun fullFrameTargetCountsCentersIncludingTheLastOne() {
        assertEquals(PixelSize(3, 2), base(GeometryRecipe.Perspective(binding(3, 2), full)))
        val recipe = GeometryRecipe.Perspective(binding(401, 301), full)
        assertEquals(PixelSize(401, 301), base(recipe))
        assertEquals(PixelSize(401, 301), target(recipe))
        assertEquals(PixelSize(301, 401), target(recipe.copy(outputQuarterTurns = 1)))
        assertEquals(PixelSize(401, 301), target(recipe.copy(outputQuarterTurns = 2)))
        assertEquals(PixelSize(301, 401), target(recipe.copy(outputQuarterTurns = 3)))
        assertEquals(PixelSize(75, 100), output(recipe.copy(outputQuarterTurns = 1), RenderPurpose.PREVIEW, 100).size)
    }

    @Test fun fixedRatioIsBaseOrientedAndNeverSilentlySwapsPortrait() {
        val recipe = GeometryRecipe.Perspective(binding(401, 301), full, TargetRule.FixedRatio(2.0), outputQuarterTurns = 1)
        assertEquals(PixelSize(401, 200), base(recipe))
        assertEquals(PixelSize(200, 401), target(recipe))
        assertEquals(PixelSize(49, 100), output(recipe, RenderPurpose.CARD, 100).size)
        assertEquals(PixelSize(150, 301), base(recipe.copy(targetRule = TargetRule.FixedRatio(0.5))))
    }

    @Test fun noneUsesSourceAspectAndAllowsSingletonAxesForEveryPurpose() {
        val recipe = GeometryRecipe.None(binding(1, 17))
        assertEquals(PixelSize(1, 17), base(recipe))
        assertEquals(PixelSize(17, 1), target(recipe.copy(outputQuarterTurns = 1)))
        for (purpose in RenderPurpose.entries) {
            assertEquals(OutputSpec(PixelSize(1, 4), purpose), output(recipe, purpose, 4))
            assertEquals(OutputSpec(PixelSize(4, 1), purpose), output(recipe.copy(outputQuarterTurns = 1), purpose, 4))
            assertEquals(OutputSpec(PixelSize(1, 1), purpose), output(GeometryRecipe.None(binding(1, 1)), purpose, 100))
        }
    }

    @Test fun roughCropSizingConsumesOnlyAcceptedBoundsAndRuleWithoutExecutingAProvider() {
        val provider = ProviderReference("deliberately-uninstalled", 1, "b".repeat(64), "geometry-only-v1", "c".repeat(64))
        val recipe = GeometryRecipe.RoughCropDewarp(binding(100, 100), RectPx(10, 20, 5, 3), provider)
        assertEquals(PixelSize(5, 3), base(recipe))
        assertEquals(PixelSize(3, 5), target(recipe.copy(outputQuarterTurns = 1)))
        assertEquals(PixelSize(3, 3), base(recipe.copy(targetRule = TargetRule.FixedRatio(1.0))))
        assertEquals(OutputSpec(PixelSize(5, 3), RenderPurpose.EXPORT), output(recipe, RenderPurpose.EXPORT, 1000))
    }

    @Test fun explicitLimitsNeverUpscaleAndHaveNoHiddenExportDefault() {
        val recipe = GeometryRecipe.Perspective(binding(401, 301), full)
        for (purpose in RenderPurpose.entries) {
            assertEquals(OutputSpec(PixelSize(401, 301), purpose), output(recipe, purpose, Int.MAX_VALUE))
            assertEquals(OutputSpec(PixelSize(100, 75), purpose), output(recipe, purpose, 100))
        }
        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { output(recipe, RenderPurpose.EXPORT, bad) }
            assertThrows(IllegalArgumentException::class.java) { output(GeometryRecipe.None(binding(1, 1)), RenderPurpose.PREVIEW, bad) }
        }
    }

    @Test fun fixedRatioSingletonsAndPurposeFlooredGeometricSingletonsReject() {
        val recipe = GeometryRecipe.Perspective(binding(12000, 2), full)
        assertEquals(PixelSize(12000, 2), base(recipe))
        assertThrows(IllegalArgumentException::class.java) { output(recipe, RenderPurpose.PREVIEW, 4000) }
        assertThrows(IllegalArgumentException::class.java) { output(recipe.copy(outputQuarterTurns = 1), RenderPurpose.CARD, 4000) }
        assertThrows(IllegalArgumentException::class.java) { output(recipe, RenderPurpose.EXPORT, 1) }
        assertEquals(PixelSize(4000, 1), output(GeometryRecipe.None(binding(12000, 2)), RenderPurpose.PREVIEW, 4000).size)
        for (ratio in listOf(Double.MIN_VALUE, Double.MAX_VALUE, 0.00001, 100000.0)) {
            assertThrows(IllegalArgumentException::class.java) { base(GeometryRecipe.Perspective(binding(401, 301), full, TargetRule.FixedRatio(ratio))) }
        }
    }

    @Test fun sizingValidatesDenormalizedQuadsWithoutRepairOrOverflow() {
        val invalid = GeometryRecipe.Perspective(binding(401, 301), quad(0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0))
        assertThrows(IllegalArgumentException::class.java) { base(invalid) }
        assertThrows(IllegalArgumentException::class.java) { target(invalid) }
        assertThrows(IllegalArgumentException::class.java) { output(invalid, RenderPurpose.PREVIEW, 100) }
        assertThrows(IllegalArgumentException::class.java) { base(GeometryRecipe.Perspective(binding(2, 2), full)) }
        val max = 2147483646.0
        val tooLong = quad(0.0, 0.0, 1.0, (max - 1.0) / max, (max - 1.0) / max, 1.0, 0.0, 1.0 / max)
        assertThrows(IllegalArgumentException::class.java) { base(GeometryRecipe.Perspective(binding(Int.MAX_VALUE, Int.MAX_VALUE), tooLong)) }
    }

    @Test fun sizingLeavesSourceBindingControlsAndRecipeIdentityUnchanged() {
        val geometry = GeometryRecipe.ManualCurve(binding(160, 120), projective, outputQuarterTurns = 1)
        val recipe = Recipe(geometry = geometry)
        val before = RecipeCodec.encode(recipe)
        val beforeHash = RecipeCodec.hash(recipe)
        val pointsBefore = geometry.quad.points().toList()
        val sourceBefore = geometry.source.copy()
        assertEquals(PixelSize(109, 75), base(geometry))
        assertEquals(PixelSize(75, 109), target(geometry))
        assertEquals(PixelSize(7, 11), output(geometry, RenderPurpose.PREVIEW, 11).size)
        assertEquals(sourceBefore, geometry.source)
        assertEquals(pointsBefore, geometry.quad.points())
        assertArrayEquals(before, RecipeCodec.encode(recipe))
        assertEquals(beforeHash, RecipeCodec.hash(recipe))
    }
}
