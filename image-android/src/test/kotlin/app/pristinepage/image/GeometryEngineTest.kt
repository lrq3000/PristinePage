package app.pristinepage.image

import app.pristinepage.core.image.CoverageMask
import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** The actual geometry consumer must preserve source identity, pixel/mask correspondence and shared allocation lifetime. Native sampling and nontrivial maps have additional gates. */
class GeometryEngineTest {
    private val sourceBinding = SourceBinding("a".repeat(64), PixelSize(3, 2), "test-srgb-v1")
    private val bytes = ByteArray(18) { it.toByte() }
    private val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
    private fun render(budget: ImageAllocationBudget, source: RgbImage, binding: SourceBinding, recipe: GeometryRecipe, output: PixelSize, protection: ProtectionMask? = null, coverage: CoverageMask? = null, checkpoint: () -> Unit = {}): AutoCloseable {
        val type = try { Class.forName("app.pristinepage.image.render.GeometryEngine") }
        catch (_: ClassNotFoundException) { throw AssertionError("Resource-owned geometry engine is absent") }
        val engine = type.getConstructor(ImageAllocationBudget::class.java).newInstance(budget)
        val method = type.methods.single { it.name == "render" && it.parameterCount == 7 }
        return try { method.invoke(engine, source, binding, recipe, OutputSpec(output, RenderPurpose.PREVIEW), protection, coverage, checkpoint) as AutoCloseable }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun image(result: AutoCloseable) = result.javaClass.getMethod("getImage").invoke(result) as RgbImage
    private fun protection(result: AutoCloseable) = result.javaClass.getMethod("getProtection").invoke(result) as ProtectionMask?
    private fun coverage(result: AutoCloseable) = result.javaClass.getMethod("getCoverage").invoke(result) as CoverageMask?
    private fun failure(expected: OperationErrorCode, operation: () -> AutoCloseable) {
        try { operation().close(); fail("Invalid geometry unexpectedly published a raster") }
        catch (error: RepositoryFailure) { assertEquals(expected, error.error.code) }
    }

    @Test fun noneAtSourceSizeReturnsExactPixelsWithoutBorrowingTheSourceLifetime() {
        val budget = ImageAllocationBudget(1_000_000)
        RgbImage(3, 2, 9, bytes).use { source ->
            val result = render(budget, source, sourceBinding, GeometryRecipe.None(sourceBinding), PixelSize(3, 2))
            assertArrayEquals(bytes, image(result).toPackedByteArray())
            assertTrue(budget.usedBytes > 0)
            result.close(); result.close()
            assertEquals(0L, budget.usedBytes)
            assertArrayEquals(bytes, source.toPackedByteArray())
            assertThrows(IllegalStateException::class.java) { image(result).toPackedByteArray() }
        }
    }

    @Test fun quarterTurnsAreExactSourcePermutationsRatherThanAnotherExifNormalization() {
        val expectedOrders = listOf(intArrayOf(0, 1, 2, 3, 4, 5), intArrayOf(3, 0, 4, 1, 5, 2), intArrayOf(5, 4, 3, 2, 1, 0), intArrayOf(2, 5, 1, 4, 0, 3))
        val budget = ImageAllocationBudget(1_000_000)
        RgbImage(3, 2, 9, bytes).use { source ->
            for (turn in 0..3) {
                val output = if (turn % 2 == 0) PixelSize(3, 2) else PixelSize(2, 3)
                render(budget, source, sourceBinding, GeometryRecipe.None(sourceBinding, outputQuarterTurns = turn), output).use { result ->
                    val expected = expectedOrders[turn].flatMap { i -> listOf(bytes[3 * i], bytes[3 * i + 1], bytes[3 * i + 2]) }.toByteArray()
                    assertArrayEquals(expected, image(result).toPackedByteArray())
                    assertEquals(output.width, image(result).width); assertEquals(output.height, image(result).height)
                }
            }
            assertArrayEquals(bytes, source.toPackedByteArray())
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun protectionAndCoverageUseTheIdenticalRotationButRemainDistinctFloatPlanes() {
        val budget = ImageAllocationBudget(1_000_000)
        RgbImage(3, 2, 9, bytes).use { source ->
            ProtectionMask(3, 2, floatArrayOf(0f, .25f, .5f, .75f, 1f, .125f)).use { mask ->
                CoverageMask(3, 2, floatArrayOf(1f, .125f, .75f, .5f, .25f, 0f)).use { page ->
                    val result = render(budget, source, sourceBinding, GeometryRecipe.None(sourceBinding, outputQuarterTurns = 1), PixelSize(2, 3), mask, page)
                    val transformedProtection = protection(result)!!; val transformedCoverage = coverage(result)!!
                    assertArrayEquals(floatArrayOf(.75f, 0f, 1f, .25f, .125f, .5f), transformedProtection.samples, 0f)
                    assertArrayEquals(floatArrayOf(.5f, 1f, .25f, .125f, 0f, .75f), transformedCoverage.samples, 0f)
                    result.close(); assertEquals(0L, budget.usedBytes)
                    assertThrows(IllegalStateException::class.java) { transformedProtection.samples }
                    assertThrows(IllegalStateException::class.java) { transformedCoverage.samples }
                    assertEquals(.25f, mask.sample(1, 0), 0f); assertEquals(.125f, page.sample(1, 0), 0f)
                }
            }
        }
    }

    @Test fun smallerNoneOutputUsesAnExplicitHalfPixelSourceResize() {
        val binding = sourceBinding.copy(sourceSize = PixelSize(4, 2))
        val input = intArrayOf(0, 0, 0, 100, 20, 40, 200, 40, 80, 240, 60, 120, 20, 100, 200, 120, 120, 160, 220, 140, 120, 255, 160, 80).map(Int::toByte).toByteArray()
        val budget = ImageAllocationBudget(1_000_000)
        RgbImage(4, 2, 12, input).use { source ->
            render(budget, source, binding, GeometryRecipe.None(binding), PixelSize(2, 1)).use { result ->
                assertArrayEquals(byteArrayOf(60, 60, 100, 229.toByte(), 100, 100), image(result).toPackedByteArray())
            }
            assertArrayEquals(input, source.toPackedByteArray()); assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun exactFullFramePerspectiveUsesTheSameLosslessQuarterTurnBranch() {
        val budget = ImageAllocationBudget(1_000_000)
        RgbImage(3, 2, 9, bytes).use { source ->
            render(budget, source, sourceBinding, GeometryRecipe.Perspective(sourceBinding, full, outputQuarterTurns = 1), PixelSize(2, 3)).use { result ->
                assertArrayEquals(byteArrayOf(9, 10, 11, 0, 1, 2, 12, 13, 14, 3, 4, 5, 15, 16, 17, 6, 7, 8), image(result).toPackedByteArray())
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun bindingAndMaskMismatchRejectBeforeAllocationEvenForNone() {
        val budget = ImageAllocationBudget(1_000_000)
        RgbImage(3, 2, 9, bytes).use { source ->
            failure(OperationErrorCode.INVALID_INPUT) { render(budget, source, sourceBinding.copy(sourcePixelHash = "b".repeat(64)), GeometryRecipe.None(sourceBinding), PixelSize(3, 2)) }
            failure(OperationErrorCode.INVALID_INPUT) { render(budget, source, sourceBinding.copy(sourceSize = PixelSize(2, 3)), GeometryRecipe.None(sourceBinding), PixelSize(3, 2)) }
            ProtectionMask(1, 1, floatArrayOf(1f)).use { mask ->
                failure(OperationErrorCode.INVALID_INPUT) { render(budget, source, sourceBinding, GeometryRecipe.None(sourceBinding), PixelSize(3, 2), mask) }
            }
            assertEquals(0L, budget.usedBytes); assertArrayEquals(bytes, source.toPackedByteArray())
        }
    }

    @Test fun invalidAspectOrUpscalingCannotSilentlyAlterTheRequestedOutput() {
        val budget = ImageAllocationBudget(1_000_000)
        RgbImage(3, 2, 9, bytes).use { source ->
            for (size in listOf(PixelSize(4, 2), PixelSize(3, 3), PixelSize(2, 2))) {
                failure(OperationErrorCode.INVALID_INPUT) { render(budget, source, sourceBinding, GeometryRecipe.None(sourceBinding), size) }
            }
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun allocationRefusalLeavesTheCompleteSourceAndNoOutputReservation() {
        val budget = ImageAllocationBudget(1)
        RgbImage(3, 2, 9, bytes).use { source ->
            failure(OperationErrorCode.RESOURCE_LIMIT) { render(budget, source, sourceBinding, GeometryRecipe.None(sourceBinding), PixelSize(3, 2)) }
            assertEquals(0L, budget.usedBytes); assertArrayEquals(bytes, source.toPackedByteArray())
        }
    }

    @Test fun cancellationAfterAdmissionReleasesScratchWithoutPublishingPixels() {
        val budget = ImageAllocationBudget(1_000_000)
        RgbImage(3, 2, 9, bytes).use { source ->
            assertThrows(CancellationException::class.java) {
                render(budget, source, sourceBinding, GeometryRecipe.None(sourceBinding), PixelSize(3, 2), checkpoint = { if (budget.usedBytes > 0) throw CancellationException("test cancellation") }).close()
            }
            assertEquals(0L, budget.usedBytes); assertArrayEquals(bytes, source.toPackedByteArray())
        }
    }
}
