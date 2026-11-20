package app.pristinepage.editor

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BatchEditSessionTest {
    @Test fun selectionDoesNotRestartPreviewsButUndoGetsAFreshEditedPageGeneration() = runTest {
        val f = Fixture()
        val session = f.session()
        val first = f.ids.first()
        val second = f.ids[1]
        val initial = session.previewGeneration(first)
        session.toggle(first)
        assertEquals(initial, session.previewGeneration(first))
        session.rotateClockwise()
        val rotated = session.previewGeneration(first)
        assertTrue(rotated > initial)
        session.toggle(second)
        assertEquals(rotated, session.previewGeneration(first))
        session.undo()
        assertEquals(f.original, session.recipes.getValue(first))
        assertTrue(session.previewGeneration(first) > rotated)
        assertEquals(initial, session.previewGeneration(second))
    }

    @Test fun aStrengthChangeRequiresOneCommonMethodAndPreservesEachPagesOtherFields() = runTest {
        val f = Fixture()
        val session = f.session()
        val first = f.ids.first()
        val second = f.ids[1]
        session.toggle(first)
        session.chooseLook(BatchLook.PAPER)
        session.rotateClockwise()
        session.setAppearanceStrength(25)
        session.toggle(second)
        val before = session.recipes
        assertEquals(BatchValue.Mixed, session.appearanceStrength())
        assertEquals(second, session.setAppearanceStrength(50)?.page)
        assertEquals(before, session.recipes)

        session.toggle(first)
        session.chooseLook(BatchLook.PAPER)
        session.toggle(first)
        assertTrue(session.canSetAppearanceStrength)
        assertEquals(BatchValue.Mixed, session.appearanceStrength())
        assertEquals(BatchValue.Mixed, session.rotation())
        assertNull(session.setAppearanceStrength(50))
        assertEquals(BatchValue.Shared(.5), session.appearanceStrength())
        assertEquals(
            before.getValue(first).copy(appearance = AppearanceRecipe.PaperGainV1(strength = .5)),
            session.recipes.getValue(first),
        )
        assertEquals(f.original, session.recipes.getValue(f.ids.last()))
        session.clearSelection()
        assertTrue(session.done() is BatchEditOutcome.Saved)
        assertEquals(listOf(first, second), f.saved.map { it.pageId })
    }

    @Test fun doneIncludesDeselectedPagesAndUsesIndependentAccumulatedTurns() = runTest {
        val f = Fixture()
        val session = f.session()
        f.ids.take(3).forEach(session::toggle)
        session.rotateClockwise()
        session.toggle(f.ids[0])
        session.rotateClockwise()
        session.clearSelection()
        assertEquals(listOf(1, 2, 2, 0), f.ids.map { session.recipes.getValue(it).geometry.outputQuarterTurns })
        assertTrue(session.done() is BatchEditOutcome.Saved)
        assertEquals(f.ids.take(3), f.saved.map { it.pageId })
        assertEquals(listOf(1, 2, 2), f.saved.map { it.recipe.geometry.outputQuarterTurns })
    }

    @Test fun undoRedoTargetsTheOriginalSelectionAndFourTurnsAreNoOp() = runTest {
        val f = Fixture()
        val session = f.session()
        session.selectAll()
        session.rotateClockwise()
        session.clearSelection()
        session.undo()
        assertTrue(session.recipes.values.all { it == f.original })
        session.redo()
        assertTrue(session.recipes.values.all { it.geometry.outputQuarterTurns == 1 })
        assertTrue(session.selection.isEmpty())
        session.selectAll()
        repeat(3) { session.rotateClockwise() }
        assertEquals(BatchEditOutcome.Unchanged, session.done())
        assertEquals(0, f.saves)
    }

    @Test fun pendingDoneLocksEditsAndConflictKeepsDrafts() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<DocumentMutationResult>()
        val session = f.session { _, _, _ -> entered.complete(Unit); release.await() }
        session.toggle(f.ids[0])
        session.rotateClockwise()
        val saved = async { session.done() }
        entered.await()
        val before = session.recipes
        session.rotateClockwise()
        session.undo()
        session.clearSelection()
        assertFalse(session.cancel())
        assertEquals(BatchEditOutcome.Busy, session.done())
        release.complete(DocumentMutationResult.Conflict(20))
        assertEquals(BatchEditOutcome.Conflict(20), saved.await())
        assertEquals(before, session.recipes)
        assertEquals(setOf(f.ids[0]), session.selection)
    }

    @Test fun selectingAnExistingLookKeepsDifferentTurnsAndAnIncompatiblePageVetoesAll() = runTest {
        val f = Fixture()
        val session = f.session(admit = { page, recipe ->
            if (page == f.ids.last() && recipe.appearance != f.original.appearance)
                OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "test_veto", page.value) else null
        })
        session.toggle(f.ids.first())
        session.rotateClockwise()
        session.selectAll()
        val before = session.recipes
        assertEquals(f.ids.last(), session.chooseLook(BatchLook.PAPER)?.page)
        assertEquals(before, session.recipes)
        session.toggle(f.ids.last())
        assertNull(session.chooseLook(BatchLook.PAPER))
        assertEquals(1, session.recipes.getValue(f.ids.first()).geometry.outputQuarterTurns)
        assertEquals(0, session.recipes.getValue(f.ids[1]).geometry.outputQuarterTurns)
        assertEquals(f.original, session.recipes.getValue(f.ids.last()))
    }

    private class Fixture {
        private val document = DocumentId(UUID.randomUUID().toString())
        val ids = List(4) { PageId(UUID.randomUUID().toString()) }
        val original = Recipe(geometry = GeometryRecipe.None(SourceBinding("a".repeat(64), PixelSize(3, 2), "synthetic-v1")))
        private val pages = ids.map { EditablePage(document, 7, it, SourceId(UUID.randomUUID().toString()), 1, RecipeCodec.hash(original), original) }
        var saves = 0
        var saved = emptyList<BatchRecipeChange>()

        fun session(admit: (PageId, Recipe) -> OperationError? = { _, _ -> null },
            save: suspend (DocumentId, Long, List<BatchRecipeChange>) -> DocumentMutationResult = { target, revision, changes ->
                assertEquals(document, target)
                assertEquals(7, revision)
                saves++
                saved = changes
                DocumentMutationResult.Applied(8, changes.map { it.pageId }, UndoId(UUID.randomUUID().toString()))
            }): BatchEditSession = BatchEditSession(pages, admit, save)
    }
}
