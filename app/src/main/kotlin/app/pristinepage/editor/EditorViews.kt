package app.pristinepage.editor

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe

enum class EditorView { RESULT, GEOMETRY_INPUT, ORIGINAL }

/** Ephemeral view recipes, never accepted/draft mutations. Both scheduling and display labeling use this mapping so an old result cannot acquire the new comparison's label merely because the same page remains selected. */
object EditorViews {
    fun parse(id: String): EditorView = when (id) {
        "result" -> EditorView.RESULT
        "input" -> EditorView.GEOMETRY_INPUT
        "original" -> EditorView.ORIGINAL
        else -> throw IllegalArgumentException("Unknown editor comparison")
    }

    fun recipe(draft: Recipe, view: EditorView): Recipe = when (view) {
        EditorView.RESULT -> draft
        EditorView.GEOMETRY_INPUT -> EditorLooks.select(draft,"none")
        EditorView.ORIGINAL -> Recipe(geometry = GeometryRecipe.None(draft.geometry.source))
    }
}
