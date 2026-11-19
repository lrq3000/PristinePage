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
    private var gestureStart: Recipe? = null
    private val gestureChanged get() = gestureStart?.let { it != current } == true
    val canUndo get() = gestureChanged || undo.isNotEmpty()
    val canRedo get() = !gestureChanged && redo.isNotEmpty()
    val dirty get() = current != accepted

    internal fun beginGesture() { check(gestureStart == null); gestureStart = current }

    /** Live replacements are visible immediately; only the net change consumes one bounded history slot. */
    internal fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (current != start) recordUndo(start)
    }

    fun replace(recipe: Recipe): Boolean {
        require(recipe.geometry.source == accepted.geometry.source) { "A page draft cannot rebind its source" }
        if (recipe == current) return false
        if (gestureStart == null) recordUndo(current)
        current = recipe
        return true
    }

    fun undo(): Recipe {
        endGesture()
        if (canUndo) { remember(redo, current); current = undo.removeLast() }
        return current
    }

    fun redo(): Recipe {
        endGesture()
        if (canRedo) { remember(undo, current); current = redo.removeLast() }
        return current
    }

    /** Keep history after Apply: Undo may create a new unsaved draft of an earlier recipe, never decrement the persisted revision. */
    fun accept() { endGesture(); accepted = current }
    fun discard() { gestureStart = null; current = accepted; undo.clear(); redo.clear() }

    private fun recordUndo(recipe: Recipe) {
        remember(undo, recipe)
        redo.clear()
    }

    private fun remember(history: ArrayDeque<Recipe>, recipe: Recipe) {
        if (history.size == limit) history.removeFirst()
        history.addLast(recipe)
    }
}
