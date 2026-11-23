package app.pristinepage.image.ocr

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.core.recipe.TargetRule
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.render.GeometryEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedOcrPreparerTest {
    @Test fun nonExactOrFailedRastersCloseWithoutAllowingAnEngineSession() = runBlocking {
        for ((outcome, expected) in listOf(
            Case.STALE to OperationErrorCode.REVISION_CONFLICT,
            Case.WRONG_SIZE to OperationErrorCode.INTEGRITY_FAILED,
            Case.DEGRADED to OperationErrorCode.PROCESSING_FAILED,
            Case.PRESSURE to OperationErrorCode.RESOURCE_LIMIT,
            Case.STORAGE to OperationErrorCode.STORAGE_FAILED,
            Case.CANCELED to null,
        )) {
            val budget = ImageAllocationBudget(64)
            var closed = 0
            val renderer = object : RenderCoordinator {
                override suspend fun render(request: RenderRequest): RenderResult {
                    assertEquals(page.sourceId.value, request.sourceId)
                    assertEquals(page.pageId.value, request.pageId)
                    assertEquals(page.recipeRevision, request.recipeRevision)
                    assertEquals(page.recipe, request.recipe)
                    assertEquals(HASH, request.sourcePixelHash)
                    assertEquals(OutputSpec(PixelSize(2, 3), RenderPurpose.OCR), request.output)
                    assertEquals(FallbackPolicy.FAIL, request.fallback)
                    val status = when (outcome) {
                        Case.DEGRADED -> RenderStatus.DEGRADED
                        Case.PRESSURE -> RenderStatus.RESOURCE_LIMIT
                        Case.STORAGE -> RenderStatus.STORAGE_FAILED
                        Case.CANCELED -> RenderStatus.CANCELED
                        else -> RenderStatus.OK
                    }
                    val image = if (status == RenderStatus.OK || status == RenderStatus.DEGRADED) {
                        val size = if (outcome == Case.WRONG_SIZE) PixelSize(3, 2) else PixelSize(2, 3)
                        RgbImage(size.width, size.height, size.width * 3, ByteArray(18))
                    } else null
                    val binding = if (outcome == Case.STALE) request.copy(recipeRevision = request.recipeRevision - 1) else request
                    val permit = budget.reserve(18)
                    return RenderResult(binding, status, image, emptyList(), emptyList(), AutoCloseable {
                        closed++
                        permit.close()
                    })
                }
            }
            val preparer = SavedOcrPreparer(renderer, budget) { _, _, _ -> throw AssertionError("Invalid render reached engine") }
            val failure = runCatching { preparer.prepare(page, config) { throw AssertionError("Invalid render reached handoff") } }.exceptionOrNull()
            if (expected == null) assertTrue(failure is CancellationException)
            else assertEquals(expected, (failure as? RepositoryFailure)?.error?.code)
            assertEquals(1, closed)
            assertEquals(0L, budget.usedBytes)
        }
    }

    @Test fun unboundEngineConfigurationDoesNotReadTheSavedSource() = runBlocking {
        val renderer = object : RenderCoordinator {
            override suspend fun render(request: RenderRequest): RenderResult = throw AssertionError("Unbound engine triggered decode")
        }
        val preparer = SavedOcrPreparer(renderer, ImageAllocationBudget(64)) { _, _, _ -> throw AssertionError("Unbound engine was opened") }
        val failure = runCatching {
            preparer.prepare(page, config.copy(languagePacks = null)) { throw AssertionError("Unbound engine reached handoff") }
        }.exceptionOrNull()
        assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, (failure as? RepositoryFailure)?.error?.code)
    }

    @Test fun aDiagnosticWarningCannotRetireRecognitionWithoutAnActualIdleBudgetCheck() = runBlocking {
        val renderer = object : RenderCoordinator {
            override suspend fun render(request: RenderRequest): RenderResult = RenderResult(
                request, RenderStatus.RESOURCE_LIMIT, null, emptyList(), listOf("ocr_render_exceeds_idle_budget"),
            )
        }
        val preparer = SavedOcrPreparer(renderer, ImageAllocationBudget(64)) { _, _, _ -> error("No native session expected") }
        val failure = runCatching { preparer.prepare(page, config) { error("No prepared handoff expected") } }
            .exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure?.error?.code)
        assertFalse(failure is SavedOcrWork.IdleBudgetRefusal)
    }

    @Test fun incompatibleSavedGeometryIsNotRetryableAndDoesNotReadTheSource() = runBlocking {
        // A valid legacy recipe envelope can be incompatible with today's minimum destination axes.
        val quad = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
        val incompatibleRecipe = Recipe(geometry = GeometryRecipe.Perspective(SourceBinding(HASH, PixelSize(100, 100), "fixture-v1"),
            quad, TargetRule.FixedRatio(0.01)))
        val incompatible = page.copy(recipe = incompatibleRecipe, recipeHash = RecipeCodec.hash(incompatibleRecipe))
        val renderer = object : RenderCoordinator {
            override suspend fun render(request: RenderRequest): RenderResult = throw AssertionError("Incompatible geometry reached decode")
        }
        val preparer = SavedOcrPreparer(renderer, ImageAllocationBudget(64)) { _, _, _ -> throw AssertionError("Incompatible geometry reached engine") }
        val failure = runCatching {
            preparer.prepare(incompatible, config) { throw AssertionError("Incompatible geometry reached handoff") }
        }.exceptionOrNull() as? RepositoryFailure
        assertEquals(OperationErrorCode.INVALID_INPUT, failure?.error?.code)
        assertEquals(false, failure?.error?.retryable)
    }

    @Test fun boundsPortraitAndRotatedLandscapeWithoutRoundingBelowTheCommonScale() = runBlocking {
        val source = SourceBinding(HASH, PixelSize(2105, 2526), "fixture-v1")
        for ((turns, expected) in listOf(0 to PixelSize(2000, 2400), 1 to PixelSize(2400, 2000))) {
            val recipe = Recipe(geometry = GeometryRecipe.None(source, outputQuarterTurns = turns))
            val saved = page.copy(recipe = recipe, recipeHash = RecipeCodec.hash(recipe))
            val renderer = object : RenderCoordinator {
                override suspend fun render(request: RenderRequest): RenderResult {
                    assertEquals(OutputSpec(expected, RenderPurpose.OCR), request.output)
                    assertEquals(saved.recipe, request.recipe)
                    assertEquals(FallbackPolicy.FAIL, request.fallback)
                    GeometryEngine.validateOutput(GeometrySizing.target(saved.recipe.geometry), request.output.size, false)
                    return RenderResult(request, RenderStatus.RESOURCE_LIMIT, null, emptyList(), emptyList())
                }
            }
            val preparer = SavedOcrPreparer(renderer, ImageAllocationBudget(64)) { _, _, _ -> throw AssertionError("Refused raster reached engine") }
            val failure = try {
                preparer.prepare(saved, config) { throw AssertionError("Refused raster reached handoff") }
                throw AssertionError("Preparation should report the renderer's resource limit")
            } catch (expected: RepositoryFailure) { expected }
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, failure.error.code)
        }
    }

    private enum class Case { STALE, WRONG_SIZE, DEGRADED, PRESSURE, STORAGE, CANCELED }

    private companion object {
        val HASH = "a".repeat(64)
        val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding(HASH, PixelSize(3, 2), "fixture-v1"), outputQuarterTurns = 1))
        val page = EditablePage(
            DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001"), 4,
            PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002"),
            SourceId("aaaaaaaa-aaaa-4aaa-8aaa-000000000003"), 3, RecipeCodec.hash(recipe), recipe,
        )
        val config = OcrConfiguration("fixture", 1, OcrLayout.AUTOMATIC, OcrLanguagePacks(listOf(OcrLanguagePack("eng", HASH))))
    }
}
