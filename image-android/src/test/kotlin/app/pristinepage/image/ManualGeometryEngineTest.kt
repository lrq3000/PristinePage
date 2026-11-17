package app.pristinepage.image

import app.pristinepage.core.image.*
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.render.GeometryEngine
import app.pristinepage.image.render.GeometryRaster
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Independent source-coordinate and pixel oracles for the admitted direct manual consumer. These synthetic JVM cases do not establish representative-page fidelity or Android/native parity. */
class ManualGeometryEngineTest {
    private val binding = SourceBinding("c".repeat(64), PixelSize(101, 101), "literal-srgb-v1")
    private val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
    private fun recipe(depth: Double = .4, turns: Int = 0) = GeometryRecipe.ManualCurve(binding, full, PointPx(.5, .1), PointPx(.5, .9), depth, outputQuarterTurns = turns)
    private fun source() = RgbImage(101, 101, 303, ByteArray(101 * 101 * 3) { when (it % 3) { 0 -> (it / 3 % 101).toByte(); 1 -> (it / 3 / 101).toByte(); else -> 37 } })
    private fun render(budget: ImageAllocationBudget, input: RgbImage, r: GeometryRecipe.ManualCurve = recipe(), size: PixelSize = PixelSize(21, 21), protection: ProtectionMask? = null, coverage: CoverageMask? = null, check: () -> Unit = {}): GeometryRaster = try {
        GeometryEngine(budget).render(input, r.source, r, OutputSpec(size, RenderPurpose.PREVIEW), protection, coverage, check)
    } catch (e: RepositoryFailure) {
        if (e.error.code == OperationErrorCode.PROVIDER_UNAVAILABLE) throw AssertionError("Admitted direct manual raster path is unavailable", e)
        throw e
    }
    private fun pixel(image: RgbImage, x: Int, y: Int, sx: Int, sy: Int) {
        assertEquals(sx, image.getUnsigned(x, y, 0)); assertEquals(sy, image.getUnsigned(x, y, 1)); assertEquals(37, image.getUnsigned(x, y, 2))
    }

    @Test fun directDepthAndOnCurveHandlesRenderWithoutHalfSagittaOrHiddenInset() {
        val budget = ImageAllocationBudget(32_000_000)
        source().use { input ->
            for ((d, cy) in listOf(0.0 to 50, .4 to 58, -.4 to 42, .95 to 69)) render(budget, input, recipe(d)).use { result ->
                pixel(result.image, 0, 0, 0, 0); pixel(result.image, 20, 20, 100, 100)
                pixel(result.image, 10, 0, 50, 10); pixel(result.image, 10, 20, 50, 90); pixel(result.image, 10, 10, 50, cy)
                assertEquals("manual_ruled_v1", result.stage.stageId)
                assertTrue(result.stage.warningCodes.contains("GEOMETRY_VISIBILITY_V1"))
                val report = result.javaClass.getMethod("getManualValidation").invoke(result)
                assertNotNull(report); assertEquals(true, report!!.javaClass.getMethod("getInjectivityProven").invoke(report))
            }
            assertEquals(0L, budget.usedBytes); pixel(input, 50, 10, 50, 10)
        }
    }

    @Test fun clockwiseTurnsComposeTheBaseRuledMapBeforeItsSingleSample() {
        val budget = ImageAllocationBudget(32_000_000)
        source().use { input -> render(budget, input, recipe(turns = 1)).use { result ->
            pixel(result.image, 0, 0, 0, 100); pixel(result.image, 20, 0, 0, 0)
            pixel(result.image, 0, 20, 100, 100); pixel(result.image, 20, 20, 100, 0)
            // Final(.75,.5)→base(.5,.25), w=.325; top/bottom y10/90→36, not source rotation or a second interpolated raster.
            pixel(result.image, 15, 10, 50, 36)
            assertTrue(result.stage.warningCodes.contains("OUTPUT_QUARTER_TURNS_1"))
        } }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun protectionAndCoverageUseTheIdenticalCertifiedCurvedSamples() {
        val budget = ImageAllocationBudget(32_000_000)
        source().use { input -> ProtectionMask(101, 101, FloatArray(10201) { (it % 101) / 100f }).use { protection -> CoverageMask(101, 101, FloatArray(10201) { (it / 101) / 100f }).use { coverage ->
            render(budget, input, recipe(turns = 1), protection = protection, coverage = coverage).use { result ->
                assertEquals(.5f, result.protection!!.sample(15, 10), 1e-6f)
                assertEquals(.36f, result.coverage!!.sample(15, 10), 1e-6f)
                assertEquals(.58f, result.coverage.sample(10, 10), 1e-6f)
            }
            assertEquals(1f, protection.sample(100, 0), 0f); assertEquals(1f, coverage.sample(0, 100), 0f)
        } } }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun straightManualTrapezoidUsesTheActualHomographyAndRecordsRequestedDispatch() {
        val b = binding.copy(sourceSize = PixelSize(160, 120))
        val q = QuadPx(PointPx(20.0 / 159, 10.0 / 119), PointPx(127.27272727272727 / 159, 13.636363636363635 / 119), PointPx(115.38461538461539 / 159, 80.76923076923076 / 119), PointPx(25.0 / 159, 83.33333333333334 / 119))
        val r = GeometryRecipe.ManualCurve(b, q, targetRule = TargetRule.FixedRatio(1.0))
        val budget = ImageAllocationBudget(2_000_000)
        RgbImage(160, 120, 480, ByteArray(160 * 120 * 3) { when (it % 3) { 0 -> (it / 3 % 160).toByte(); 1 -> (it / 3 / 160).toByte(); else -> 37 } }).use { input ->
            render(budget, input, r, PixelSize(11, 11)).use { result ->
                pixel(result.image, 5, 5, 74, 50)
                assertEquals("manual_ruled_v1", result.stage.stageId)
                assertTrue(result.stage.warningCodes.contains("MANUAL_STRAIGHT_PERSPECTIVE")); assertNotNull(result.perspectiveValidation)
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun nonSquareSourceAndOddTurnKeepBothAxesAndDepthInBaseSpace() {
        val b = binding.copy(sourceSize = PixelSize(121, 81))
        val r = GeometryRecipe.ManualCurve(b, full, PointPx(.5, .1), PointPx(.5, .9), .4, outputQuarterTurns = 1)
        val budget = ImageAllocationBudget(32_000_000)
        RgbImage(121, 81, 363, ByteArray(121 * 81 * 3) { when (it % 3) { 0 -> (it / 3 % 121).toByte(); 1 -> (it / 3 / 121).toByte(); else -> 37 } }).use { input ->
            render(budget, input, r, PixelSize(9, 13)).use { result ->
                pixel(result.image, 0, 0, 0, 80); pixel(result.image, 8, 0, 0, 0)
                pixel(result.image, 4, 6, 60, 46); pixel(result.image, 6, 6, 60, 29)
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun tinyRealCurvatureStillUsesDirectProofRatherThanAStraightnessHeuristic() {
        val r = GeometryRecipe.ManualCurve(binding, full, topMidpoint = PointPx(.5, 1e-15))
        val budget = ImageAllocationBudget(32_000_000)
        source().use { input -> render(budget, input, r).use { result ->
            assertFalse(result.stage.warningCodes.contains("MANUAL_STRAIGHT_PERSPECTIVE"))
            assertNotNull(result.manualValidation); assertNull(result.perspectiveValidation)
            assertTrue(budget.usedBytes > 13_000_000)
        } }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun exactFullFrameStraightBranchKeepsPixelPermutationAndMaskBits() {
        val b = binding.copy(sourceSize = PixelSize(3, 2)); val r = GeometryRecipe.ManualCurve(b, full, outputQuarterTurns = 1)
        val budget = ImageAllocationBudget(1_000_000)
        RgbImage(3, 2, 9, ByteArray(18) { it.toByte() }).use { input -> ProtectionMask(3, 2, floatArrayOf(0f, .25f, .5f, .75f, 1f, .125f)).use { protection ->
            render(budget, input, r, PixelSize(2, 3), protection).use { result ->
                val order = intArrayOf(3, 0, 4, 1, 5, 2)
                assertArrayEquals(ByteArray(18) { (3 * order[it / 3] + it % 3).toByte() }, result.image.toPackedByteArray())
                assertEquals(.125f, result.protection!!.sample(0, 2), 0f); assertEquals("EXACT_PERMUTATION", result.stage.outcome)
            }
        } }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun foldedControlsRefuseAsInvalidMapWithoutPerspectiveSubstitutionOrSourceClosure() {
        val budget = ImageAllocationBudget(32_000_000)
        source().use { input ->
            val r = recipe().copy(topMidpoint = PointPx(.5, .9), bottomMidpoint = PointPx(.5, .1))
            val e = assertThrows(RepositoryFailure::class.java) { GeometryEngine(budget).render(input, binding, r, OutputSpec(PixelSize(21, 21), RenderPurpose.PREVIEW), null, null, {}).close() }
            assertEquals(OperationErrorCode.INVALID_MAP, e.error.code); assertEquals(0L, budget.usedBytes); pixel(input, 50, 50, 50, 50)
        }
    }

    @Test fun completeManualWorkspaceIsAdmittedBeforePreparationOrAnyProofWork() {
        val budget = ImageAllocationBudget(1_000_000)
        source().use { input ->
            var admittedCalls = 0
            val e = assertThrows(RepositoryFailure::class.java) { GeometryEngine(budget).render(input, binding, recipe(), OutputSpec(PixelSize(21, 21), RenderPurpose.PREVIEW), null, null) { if (budget.usedBytes > 0) admittedCalls++ }.close() }
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, e.error.code); assertEquals(0, admittedCalls); assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun exactWholeManualChargeAndEachMaskCopyRemainOwnedUntilClose() {
        // 21² pixels:65536 metadata +65536 candidate +13MiB proofs +840 axis bytes +2646 RGB bytes; each optional float mask adds3528bytes for scratch+copy.
        for (masks in 0..2) {
            val required = 13_766_046L + 3528L * masks
            source().use { input -> ProtectionMask(101, 101, FloatArray(10201) { .25f }).use { protection -> CoverageMask(101, 101, FloatArray(10201) { .75f }).use { coverage ->
                for (capacity in listOf(required - 1, required)) {
                    val budget = ImageAllocationBudget(capacity)
                    if (capacity < required) {
                        val error = assertThrows(RepositoryFailure::class.java) { GeometryEngine(budget).render(input, binding, recipe(), OutputSpec(PixelSize(21, 21), RenderPurpose.PREVIEW), if (masks > 0) protection else null, if (masks > 1) coverage else null, {}).close() }
                        assertEquals(OperationErrorCode.RESOURCE_LIMIT, error.error.code)
                    } else render(budget, input, protection = if (masks > 0) protection else null, coverage = if (masks > 1) coverage else null).use { assertEquals(required, budget.usedBytes) }
                    assertEquals(0L, budget.usedBytes)
                }
            } } }
        }
    }

    @Test fun cancellationInProofRowsAndAfterOutputConstructionReleasesAllOwners() {
        val budget = ImageAllocationBudget(32_000_000)
        source().use { input -> ProtectionMask(101, 101, FloatArray(10201) { .25f }).use { protection ->
            var total = 0; render(budget, input, protection = protection, check = { total++ }).close()
            for (stop in listOf(5, 600, total - 6, total)) {
                var calls = 0; val signal = CancellationException("manual-render-$stop")
                assertSame(signal, assertThrows(CancellationException::class.java) { render(budget, input, protection = protection, check = { if (++calls == stop) throw signal }).close() })
                assertEquals(0L, budget.usedBytes); pixel(input, 50, 50, 50, 50); assertEquals(.25f, protection.sample(0, 0), 0f)
            }
        } }
    }
}
