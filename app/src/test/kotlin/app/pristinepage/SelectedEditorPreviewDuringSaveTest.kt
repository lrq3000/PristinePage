package app.pristinepage

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.SaveRecipeResult
import app.pristinepage.data.model.SourceId
import app.pristinepage.editor.EditorSession
import app.pristinepage.editor.EditorSaveCandidate
import app.pristinepage.editor.SelectedEditorActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectedEditorPreviewDuringSaveTest {
    @Test fun aMismatchedSaveReceiptDischargesThePendingDeliveryWithoutAcceptingTheDraft() = runTest {
        val document = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
        val page = PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002")
        val source = SourceId("aaaaaaaa-aaaa-4aaa-8aaa-000000000003")
        val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding("a".repeat(64), PixelSize(3, 2), "fixture-v1")))
        var acknowledged = 0
        var abandoned = 0
        val actions = object : SelectedEditorActions {
            override val usesSelectedFolder = true
            override suspend fun load(documentId: DocumentId, pageId: PageId) = EditablePage(document, 1, page, source, 1, RecipeCodec.hash(recipe), recipe)
            override suspend fun save(pageId: PageId, expectedRevision: Long, recipe: Recipe): SaveRecipeResult =
                error("Selected saves must carry their pending acknowledgment to the editor")

            override suspend fun prepareSave(pageId: PageId, expectedRevision: Long, recipe: Recipe) = EditorSaveCandidate(
                SaveRecipeResult.Saved(page, 2, 2, "d".repeat(64)),
                onAccepted = { acknowledged++ }, onAbandoned = { abandoned++ },
            )
        }
        val editor = EditorSession(document.value, page.value, actions, object : RenderCoordinator {
            override suspend fun render(request: RenderRequest): RenderResult = error("This receipt control does not render")
        }, this)
        try {
            editor.setActive(false)
            advanceUntilIdle()
            editor.rotateClockwise()
            editor.apply()
            advanceUntilIdle()
            assertEquals(0, acknowledged)
            assertEquals(1, abandoned)
            assertEquals(1L, editor.state.value.page?.recipeRevision)
            assertTrue(editor.state.value.dirty)
        } finally { editor.close() }
    }

    @Test fun selectedSaveClosesThePreviousPreviewAndDoesNotReloadOnResumeUntilDefiniteRefusal() = runTest {
        val document = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
        val page = PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002")
        val source = SourceId("aaaaaaaa-aaaa-4aaa-8aaa-000000000003")
        val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding("a".repeat(64), PixelSize(3, 2), "fixture-v1")))
        val permit = CompletableDeferred<SaveRecipeResult>()
        val actions = object : SelectedEditorActions {
            override val usesSelectedFolder = true
            override suspend fun load(documentId: DocumentId, pageId: PageId) = EditablePage(document, 1, page, source, 1, RecipeCodec.hash(recipe), recipe)
            override suspend fun save(pageId: PageId, expectedRevision: Long, recipe: Recipe) = permit.await()
        }
        var releases = 0
        var renders = 0
        val renderer = object : RenderCoordinator {
            override suspend fun render(request: RenderRequest): RenderResult {
                renders++
                val (width, height) = request.output.size
                return RenderResult(request, RenderStatus.OK, RgbImage(width, height, width * 3, ByteArray(width * height * 3)),
                    emptyList(), emptyList(), AutoCloseable { releases++ })
            }
        }
        val editor = EditorSession(document.value, page.value, actions, renderer, this)
        try {
            advanceUntilIdle()
            editor.rotateClockwise()
            advanceUntilIdle()
            assertNotNull(editor.preview.value.preview)
            editor.apply()
            runCurrent()
            assertTrue(editor.state.value.saving)
            assertNull(editor.preview.value.preview)
            assertEquals(renders, releases)
            val stopped = renders
            editor.setActive(false)
            editor.setActive(true)
            runCurrent()
            assertEquals(stopped, renders)
            permit.complete(SaveRecipeResult.Conflict(2))
            advanceUntilIdle()
            assertEquals(stopped + 1, renders)
            assertTrue(editor.state.value.dirty)
            assertFalse(editor.state.value.saving)
        } finally {
            permit.cancel()
            editor.close()
        }
    }
}
