package app.pristinepage.core

import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import org.junit.Assert.*
import org.junit.Test

/** Literal independently calculated targets distinguish export's area cap from the existing long-edge-only preview sizing. No pixels, providers, files or allocation admission are implied. */
class ExportSizingTest {
    private val full = QuadPx(PointPx(0.0,0.0),PointPx(1.0,0.0),PointPx(1.0,1.0),PointPx(0.0,1.0))
    private fun binding(w: Int, h: Int) = SourceBinding("a".repeat(64),PixelSize(w,h),"test-srgb-v1")
    private fun normal(recipe: GeometryRecipe) = CoreApi.function("geometry.ExportSizing","normal",recipe) as OutputSpec
    private fun source(recipe: GeometryRecipe) = CoreApi.function("geometry.ExportSizing","sourceResolution",recipe) as OutputSpec

    @Test fun squareUsesTheAreaCapInsteadOfAnUnreviewedSixteenMegapixelExport() {
        assertEquals(OutputSpec(PixelSize(3464,3464),RenderPurpose.EXPORT),normal(GeometryRecipe.None(binding(8000,8000))))
    }

    @Test fun longEdgeAndAreaLimitsUseOneCommonFlooredScaleWithoutUpscaling() {
        for ((input, expected) in listOf(PixelSize(6000,4000) to PixelSize(4000,2666),PixelSize(6000,5000) to PixelSize(3794,3162),PixelSize(4001,3000) to PixelSize(4000,2999),PixelSize(4800,3600) to PixelSize(4000,3000),PixelSize(256,192) to PixelSize(256,192))) {
            assertEquals(OutputSpec(expected,RenderPurpose.EXPORT),normal(GeometryRecipe.None(binding(input.width,input.height))))
        }
    }

    @Test fun turnsSwapTheActualPageTargetBeforeExportSizing() {
        assertEquals(PixelSize(3162,3794),normal(GeometryRecipe.None(binding(6000,5000),outputQuarterTurns = 1)).size)
        assertEquals(PixelSize(3162,3794),normal(GeometryRecipe.Perspective(binding(6000,5000),full,outputQuarterTurns = 1)).size)
    }

    @Test fun longEdgeBoundaryFloorsBothAxesFromTheSameExactScale() {
        val target = binding(5090, 1018)
        assertEquals(PixelSize(4000, 800), normal(GeometryRecipe.None(target)).size)
        assertEquals(PixelSize(800, 4000), normal(GeometryRecipe.None(target, outputQuarterTurns = 1)).size)
    }

    @Test fun areaCapChangesTheCommonScaleOnlyAboveTwelveMegapixels() {
        assertEquals(PixelSize(4000, 2999), normal(GeometryRecipe.None(binding(4000, 2999))).size)
        assertEquals(PixelSize(4000, 3000), normal(GeometryRecipe.None(binding(4000, 3000))).size)
        assertEquals(PixelSize(3999, 3000), normal(GeometryRecipe.None(binding(4000, 3001))).size)
        assertEquals(PixelSize(3464, 3464), normal(GeometryRecipe.None(binding(Int.MAX_VALUE, Int.MAX_VALUE))).size)
    }

    @Test fun areaLimitedTargetCanLandExactlyOnBothAxes() {
        // A 25/29 scale gives 3750 × 3200, exactly 12 MP without reaching the long-edge cap.
        assertEquals(PixelSize(3750, 3200), normal(GeometryRecipe.None(binding(4350, 3712))).size)
    }

    @Test fun fixedPageRatioComesFromGeometryNotTheOriginalPhotograph() {
        val recipe = GeometryRecipe.Perspective(binding(6000,4000),full,TargetRule.FixedRatio(1.0))
        assertEquals(PixelSize(3464,3464),normal(recipe).size)
    }

    @Test fun impossibleFlooredAxesRejectRatherThanClampDistortOrUsePreviewDimensions() {
        assertThrows(IllegalArgumentException::class.java) { normal(GeometryRecipe.Perspective(binding(12000,2),full)) }
        assertThrows(IllegalArgumentException::class.java) { normal(GeometryRecipe.None(binding(12000,2))) }
        assertEquals(PixelSize(1,2),normal(GeometryRecipe.None(binding(1,2))).size)
    }

    @Test fun sourceResolutionIsAnExplicitUncappedChoiceNotResourceAdmission() {
        val recipe = GeometryRecipe.None(binding(6000,5000),outputQuarterTurns = 1)
        assertEquals(OutputSpec(PixelSize(5000,6000),RenderPurpose.EXPORT),source(recipe))
        assertEquals(OutputSpec(PixelSize(3162,3794),RenderPurpose.EXPORT),normal(recipe))
    }
}
