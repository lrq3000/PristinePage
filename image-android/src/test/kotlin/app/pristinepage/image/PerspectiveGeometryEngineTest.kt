package app.pristinepage.image

import app.pristinepage.core.geometry.AtelierEdgeDetector
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.image.CoverageMask
import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.core.recipe.TargetRule
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.render.GeometryEngine
import app.pristinepage.image.render.GeometryRaster
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Image/mask consumers use literal source-center oracles independent of the solver. These JVM cases are not an Android/native parity or real-page fidelity gate. */
class PerspectiveGeometryEngineTest {
    private val binding = SourceBinding("a".repeat(64), PixelSize(160, 120), "test-srgb-v1")
    private val quad = QuadPx(PointPx(20.0 / 159, 10.0 / 119), PointPx(127.27272727272727 / 159, 13.636363636363635 / 119), PointPx(115.38461538461539 / 159, 80.76923076923076 / 119), PointPx(25.0 / 159, 83.33333333333334 / 119))
    private fun recipe(turns: Int = 0) = GeometryRecipe.Perspective(binding, quad, TargetRule.FixedRatio(1.0), outputQuarterTurns = turns)
    private fun source() = RgbImage(160, 120, 480, ByteArray(160 * 120 * 3) { i -> when (i % 3) { 0 -> (i / 3 % 160).toByte(); 1 -> (i / 3 / 160).toByte(); else -> 0 } })
    private fun render(budget: ImageAllocationBudget, source: RgbImage, recipe: GeometryRecipe.Perspective, output: PixelSize, protection: ProtectionMask? = null, coverage: CoverageMask? = null, checkpoint: () -> Unit = {}): GeometryRaster {
        return try { GeometryEngine(budget).render(source, recipe.source, recipe, OutputSpec(output, RenderPurpose.PREVIEW), protection, coverage, checkpoint) }
        catch (error: RepositoryFailure) {
            if (error.error.code == OperationErrorCode.PROVIDER_UNAVAILABLE) throw AssertionError("Validated perspective raster path is unavailable", error)
            throw error
        }
    }
    private fun pixel(image: RgbImage, x: Int, y: Int, red: Int, green: Int) {
        assertEquals(red, image.getUnsigned(x, y, 0)); assertEquals(green, image.getUnsigned(x, y, 1)); assertEquals(0, image.getUnsigned(x, y, 2))
    }

    @Test fun detectedLowContrastSheetRunsThroughTheRealPerspectiveRendererAndKeepsInk() {
        fun luminance(x: Int, y: Int): Int = when {
            x in 64..96 && y in 56..64 -> 50
            x in 24..135 && y in 20..99 -> 160
            else -> 139
        }
        val proposed = checkNotNull(AtelierEdgeDetector.detect(160, 120, luminance = ::luminance))
        val perspective = GeometryRecipe.Perspective(binding, proposed)
        val output = GeometrySizing.output(perspective, RenderPurpose.PREVIEW, 384).size
        val budget = ImageAllocationBudget(2_000_000)
        RgbImage(160, 120, 480, ByteArray(160 * 120 * 3) { i -> luminance(i / 3 % 160, i / 480).toByte() }).use { input ->
            render(budget, input, perspective, output).use { result ->
                assertEquals("perspective_v1", result.stage.stageId)
                assertTrue(result.image.width in 110..114 && result.image.height in 78..82)
                assertEquals(50, result.image.getUnsigned(result.image.width / 2, result.image.height / 2, 0))
                assertEquals(160, result.image.getUnsigned(result.image.width / 8, result.image.height / 8, 0))
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun publishedNonAffineMapRendersItsProjectiveCenterAndCorners() {
        val budget = ImageAllocationBudget(2_000_000)
        source().use { input ->
            render(budget, input, recipe(), PixelSize(11, 11)).use { result ->
                pixel(result.image, 0, 0, 20, 10); pixel(result.image, 10, 0, 127, 14)
                pixel(result.image, 10, 10, 115, 81); pixel(result.image, 0, 10, 25, 83)
                pixel(result.image, 5, 5, 74, 50)
                assertEquals("perspective_v1", result.stage.stageId)
                assertEquals("OK", result.stage.outcome)
                assertTrue(result.stage.warningCodes.contains("GEOMETRY_VISIBILITY_V1"))
                assertTrue(budget.usedBytes > 0)
            }
            assertEquals(0L, budget.usedBytes); pixel(input, 20, 10, 20, 10)
        }
    }

    @Test fun publicNonAffineProofUsesUnequalSourceAndDestinationAxesAndRetainsItsDiagnostics() {
        val budget = ImageAllocationBudget(2_000_000)
        source().use { input ->
            val asymmetric = GeometryRecipe.Perspective(binding, quad, TargetRule.FixedRatio(11.0 / 7))
            render(budget, input, asymmetric, PixelSize(11, 7)).use { result ->
                val diagnostic = result.javaClass.getMethod("getPerspectiveValidation").invoke(result)
                assertNotNull(diagnostic)
                fun value(name: String) = diagnostic!!.javaClass.getMethod("get$name").invoke(diagnostic) as Double
                // Independent determinant=.5475397706252313, median denominator1.15 on the129² lattice. Raw G20/G21 must be scaled by their respective unequal destination axes.
                assertEquals(.3600162870881771, value("ReferenceJacobian"), 1e-12)
                assertTrue(value("MinimumJacobianBound") <= .24922156150442934 + 1e-12)
                assertTrue(value("MaximumFrobeniusBound") >= 1.0488933435885168 - 1e-12)
                assertEquals(1.0, value("MeanSupport"), 1e-12)
                pixel(result.image, 5, 3, 74, 50)
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun quarterTurnsComposeTheActualPerspectiveMapRatherThanRotatingSourceControls() {
        val corners = listOf(intArrayOf(20, 10, 127, 14, 115, 81, 25, 83), intArrayOf(25, 83, 20, 10, 127, 14, 115, 81), intArrayOf(115, 81, 25, 83, 20, 10, 127, 14), intArrayOf(127, 14, 115, 81, 25, 83, 20, 10))
        val budget = ImageAllocationBudget(2_000_000)
        source().use { input ->
            for (turn in 0..3) render(budget, input, recipe(turn), PixelSize(11, 11)).use { result ->
                val p = corners[turn]
                pixel(result.image, 0, 0, p[0], p[1]); pixel(result.image, 10, 0, p[2], p[3])
                pixel(result.image, 10, 10, p[4], p[5]); pixel(result.image, 0, 10, p[6], p[7])
                if (turn == 1) pixel(result.image, 7, 3, 54, 35)
                assertTrue(result.stage.warningCodes.contains("OUTPUT_QUARTER_TURNS_$turn"))
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun protectionAndCoverageShareEveryProjectiveSampleWithoutByteQuantization() {
        val budget = ImageAllocationBudget(2_000_000)
        source().use { input -> ProtectionMask(160, 120, FloatArray(160 * 120) { (it % 160).toFloat() / 159 }).use { protected ->
            CoverageMask(160, 120, FloatArray(160 * 120) { (it / 160).toFloat() / 119 }).use { covered ->
                render(budget, input, recipe(1), PixelSize(11, 11), protected, covered).use { result ->
                    val transformedProtection = checkNotNull(result.protection)
                    val transformedCoverage = checkNotNull(result.coverage)
                    assertEquals(73.91304347826087 / 159, transformedProtection.sample(5, 5).toDouble(), 1e-6)
                    assertEquals(50.0 / 119, transformedCoverage.sample(5, 5).toDouble(), 1e-6)
                    assertEquals(54.12844036697248 / 159, transformedProtection.sample(7, 3).toDouble(), 1e-6)
                    assertEquals(35.32110091743119 / 119, transformedCoverage.sample(7, 3).toDouble(), 1e-6)
                }
                assertEquals(0L, budget.usedBytes); assertEquals(1f, protected.sample(159, 0), 0f); assertEquals(1f, covered.sample(0, 119), 0f)
            }
        } }
    }

    @Test fun smallerFullFramePerspectiveIsCornerAlignedNotTheNoneHalfPixelResize() {
        val b = binding.copy(sourceSize = PixelSize(5, 3))
        val q = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
        val budget = ImageAllocationBudget(2_000_000)
        RgbImage(5, 3, 15, ByteArray(45) { i -> (50 * (i / 3 % 5) + 10 * (i / 3 / 5)).toByte() }).use { input ->
            render(budget, input, GeometryRecipe.Perspective(b, q), PixelSize(3, 2)).use { result ->
                val expected = intArrayOf(0, 100, 200, 20, 120, 220)
                assertArrayEquals(ByteArray(18) { expected[it / 3].toByte() }, result.image.toPackedByteArray())
                assertEquals("perspective_v1", result.stage.stageId)
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun nonSquareCropAndOddTurnKeepSourceOriginAndDestinationAspect() {
        val b = binding.copy(sourceSize = PixelSize(7, 5))
        val q = QuadPx(PointPx(1.0 / 6, .25), PointPx(5.0 / 6, .25), PointPx(5.0 / 6, .75), PointPx(1.0 / 6, .75))
        val budget = ImageAllocationBudget(2_000_000)
        RgbImage(7, 5, 21, ByteArray(105) { i -> (i / 3 % 7 + 10 * (i / 3 / 7)).toByte() }).use { input ->
            render(budget, input, GeometryRecipe.Perspective(b, q, outputQuarterTurns = 1), PixelSize(3, 5)).use { result ->
                val expected = intArrayOf(31,21,11,32,22,12,33,23,13,34,24,14,35,25,15)
                assertArrayEquals(ByteArray(45) { expected[it / 3].toByte() }, result.image.toPackedByteArray())
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun validQuadWithExcessiveNormalizedAnisotropyFailsAsInvalidMapWithoutPixels() {
        val b = binding.copy(sourceSize = PixelSize(4001, 3))
        val q = QuadPx(PointPx(.5, 0.0), PointPx(.5005, 0.0), PointPx(.5005, 1.0), PointPx(.5, 1.0))
        val budget = ImageAllocationBudget(2_000_000)
        RgbImage(4001, 3, 12003, ByteArray(36009) { 73 }).use { input ->
            val failure = assertThrows(RepositoryFailure::class.java) { render(budget, input, GeometryRecipe.Perspective(b, q), PixelSize(3, 3)).close() }
            assertEquals(OperationErrorCode.INVALID_MAP, failure.error.code)
            assertEquals(0L, budget.usedBytes); assertEquals(73, input.getUnsigned(0, 0, 0))
        }
    }

    @Test fun proofWorkspaceIsAdmittedAndCancellationReleasesItBeforeAnyPublication() {
        source().use { input ->
            val denied = ImageAllocationBudget(100_000)
            val failure = assertThrows(RepositoryFailure::class.java) { render(denied, input, recipe(), PixelSize(11, 11)).close() }
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code); assertEquals(0L, denied.usedBytes)
            val budget = ImageAllocationBudget(2_000_000)
            var admittedCalls = 0
            assertThrows(CancellationException::class.java) { render(budget, input, recipe(), PixelSize(11, 11), checkpoint = { if (budget.usedBytes > 0 && ++admittedCalls == 5) throw CancellationException("perspective proof") }).close() }
            assertEquals(0L, budget.usedBytes); pixel(input, 20, 10, 20, 10)
        }
    }

    @Test fun cancellationAfterPartialRowsAndAfterPlaneConstructionReleasesAllOwners() {
        val budget = ImageAllocationBudget(2_000_000)
        source().use { input -> ProtectionMask(160, 120, FloatArray(160 * 120) { .25f }).use { mask ->
            var calls = 0
            render(budget, input, recipe(), PixelSize(11, 11), mask, checkpoint = { calls++ }).close()
            for (stop in listOf(calls - 6, calls)) {
                var current = 0
                assertThrows(CancellationException::class.java) { render(budget, input, recipe(), PixelSize(11, 11), mask, checkpoint = { if (++current == stop) throw CancellationException("late geometry") }).close() }
                assertEquals(0L, budget.usedBytes); assertEquals(.25f, mask.sample(0, 0), 0f); pixel(input, 20, 10, 20, 10)
            }
        } }
    }

    @Test fun admittedManualStraightPathStillRefusesInsufficientSharedBudgetWithoutSubstitute() {
        val b = binding.copy(sourceSize = PixelSize(3, 2))
        val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
        val budget = ImageAllocationBudget(1)
        RgbImage(3, 2, 9, ByteArray(18)).use { input ->
            val failure = assertThrows(RepositoryFailure::class.java) { GeometryEngine(budget).render(input, b, GeometryRecipe.ManualCurve(b, full), OutputSpec(PixelSize(3, 2), RenderPurpose.PREVIEW), null, null, {}).close() }
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code); assertEquals(0L, budget.usedBytes)
        }
    }
}
