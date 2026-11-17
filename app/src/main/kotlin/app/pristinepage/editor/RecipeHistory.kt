package app.pristinepage.editor

import app.pristinepage.core.pipeline.Recipe

/** Main-confined immutable draft snapshots. Undo/Redo never processes pixels or writes revisions; accept is called ONLY after the matching repository CAS is confirmed saved. The accepted baseline survives draft eviction so Discard always restores the last confirmed recipe. */
class RecipeHistory(initial: Recipe, private val limit: Int = 20) {
    init { require(limit in 1..100) { "Draft history must be bounded" } }
    var accepted: Recipe = initial
        private set
    var current: Recipe = initial
        private set
    private val undo = ArrayDeque<Recipe>()
    private val redo = ArrayDeque<Recipe>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val dirty get() = current != accepted

    fun replace(recipe: Recipe): Boolean {
        require(recipe.geometry.source == accepted.geometry.source) { "A page draft cannot rebind its source" }
        if (recipe == current) return false
        remember(undo, current)
        redo.clear()
        current = recipe
        return true
    }

    fun undo(): Recipe {
        if (canUndo) { remember(redo, current); current = undo.removeLast() }
        return current
    }

    fun redo(): Recipe {
        if (canRedo) { remember(undo, current); current = redo.removeLast() }
        return current
    }

    /** Keep history after Apply: Undo may create a new unsaved draft of an earlier recipe, never decrement the persisted revision. */
    fun accept() { accepted = current }
    fun discard() { current = accepted; undo.clear(); redo.clear() }

    private fun remember(history: ArrayDeque<Recipe>, recipe: Recipe) {
        if (history.size == limit) history.removeFirst()
        history.addLast(recipe)
    }
}
