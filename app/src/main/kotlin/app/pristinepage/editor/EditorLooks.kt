package app.pristinepage.editor

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.BinaryRecipe

/** These are documented complete presets, not labels for one hidden filter. The geometry/source binding is preserved; UI must disclose finishing changes and never substitute a recipe for an unknown/gated method. */
object EditorLooks {
    @JvmStatic fun select(recipe: Recipe, id: String): Recipe = when (id) {
        "none" -> recipe.copy(appearance = AppearanceRecipe.NoneV1, denoise = null, detail = null, binary = null)
        "paper" -> recipe.copy(appearance = AppearanceRecipe.PaperGainV1(), binary = null)
        "natural" -> recipe.copy(appearance = AppearanceRecipe.NaturalLV1(), binary = null)
        "gray" -> recipe.copy(appearance = AppearanceRecipe.NoneV1, binary = BinaryRecipe.GrayscaleV1)
        "binary" -> recipe.copy(appearance = AppearanceRecipe.NoneV1, detail = null, binary = BinaryRecipe.SauvolaV1())
        else -> throw IllegalArgumentException("Unknown editor Look")
    }
}
