package app.pristinepage

import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** Immutable recipe snapshots, not pixel filtering or repository writes. The native editor consumer must later prove CAS/save/reopen on the actual Application store. */
class EditorRevisionTest {
    private val binding = SourceBinding("a".repeat(64),PixelSize(3,2),"fixture-srgb-v1")
    private val original = Recipe(geometry = GeometryRecipe.None(binding))
    private val white = original.copy(appearance = AppearanceRecipe.PaperGainV1(strength = .5))
    private val rotated = white.copy(geometry = GeometryRecipe.None(binding,outputQuarterTurns = 1))
    private class History(initial: Recipe, limit: Int = 20) {
        private val value: Any = try { Class.forName("app.pristinepage.editor.RecipeHistory").getConstructor(Recipe::class.java,Int::class.javaPrimitiveType).newInstance(initial,limit) }
        catch (_: ClassNotFoundException) { throw AssertionError("Accepted/draft recipe history is absent") }
        fun invoke(name: String, recipe: Recipe? = null): Any? = try { if (recipe == null) value.javaClass.getMethod(name).invoke(value) else value.javaClass.getMethod(name,Recipe::class.java).invoke(value,recipe) } catch (error: InvocationTargetException) { throw error.targetException }
        @Suppress("UNCHECKED_CAST") fun <T> field(name: String): T = value.javaClass.getMethod("get$name").invoke(value) as T
        val current get() = field<Recipe>("Current")
    }

    @Test fun draftUndoRedoRestoresCompleteRecipesWithoutEditingAcceptedSource() {
        val before = RecipeCodec.encode(original); val history = History(original)
        history.invoke("replace",white); history.invoke("replace",rotated)
        assertEquals(original,history.field<Recipe>("Accepted")); assertTrue(history.field("Dirty")); assertEquals(rotated,history.current)
        history.invoke("undo"); assertEquals(white,history.current); history.invoke("undo"); assertEquals(original,history.current); assertFalse(history.field("Dirty"))
        history.invoke("redo"); history.invoke("redo"); assertEquals(rotated,history.current); assertArrayEquals(before,RecipeCodec.encode(original))
    }

    @Test fun divergentEditInvalidatesRedoButAnIdenticalSelectionDoesNotConsumeHistory() {
        val history = History(original); history.invoke("replace",white); history.invoke("replace",white); history.invoke("undo")
        assertEquals(original,history.current); assertFalse(history.field("CanUndo")); assertTrue(history.field("CanRedo"))
        val natural = original.copy(appearance = AppearanceRecipe.NaturalLV1()); history.invoke("replace",natural); assertFalse(history.field("CanRedo"))
        history.invoke("redo"); assertEquals(natural,history.current)
    }

    @Test fun acceptingMovesOnlyTheBaselineAndUndoCanCreateANewDraftAfterApply() {
        val history = History(original); history.invoke("replace",white); history.invoke("accept")
        assertEquals(white,history.field<Recipe>("Accepted")); assertFalse(history.field("Dirty")); history.invoke("undo")
        assertEquals(original,history.current); assertTrue(history.field("Dirty")); assertEquals(white,history.field<Recipe>("Accepted"))
    }

    @Test fun discardRestoresLastAcceptedRecipeAndClearsBothDraftDirections() {
        val history = History(original); history.invoke("replace",white); history.invoke("accept"); history.invoke("replace",rotated); history.invoke("undo"); history.invoke("discard")
        assertEquals(white,history.current); assertFalse(history.field("CanUndo")); assertFalse(history.field("CanRedo")); assertFalse(history.field("Dirty"))
    }

    @Test fun boundedHistoryCannotRevisitAnEvictedDraftButNeverEvictsTheAcceptedBaseline() {
        val history = History(original,2); val q2 = rotated.copy(geometry = GeometryRecipe.None(binding,outputQuarterTurns = 2))
        history.invoke("replace",white); history.invoke("replace",rotated); history.invoke("replace",q2); repeat(3) { history.invoke("undo") }
        assertEquals(white,history.current); assertEquals(original,history.field<Recipe>("Accepted")); history.invoke("discard"); assertEquals(original,history.current)
    }

    @Test fun historyRefusesSourceRebindingWithoutChangingAnyDraftOrUndoState() {
        val history = History(original); history.invoke("replace",white)
        val unrelated = original.copy(geometry = GeometryRecipe.None(binding.copy(sourcePixelHash = "b".repeat(64))))
        assertThrows(IllegalArgumentException::class.java) { history.invoke("replace",unrelated) }
        assertEquals(white,history.current); history.invoke("undo"); assertEquals(original,history.current)
    }
}
