package app.pristinepage

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.*
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** Independent complete preset expectations from UX_SPECIFICATION. No filter is executed and no previous rendered pixels enter a recipe. */
class EditorLooksTest {
    private val source = SourceBinding("a".repeat(64),PixelSize(3,2),"fixture-srgb-v1")
    private val recipe = Recipe(geometry = GeometryRecipe.None(source,outputQuarterTurns = 3),appearance = AppearanceRecipe.PaperGainV1(strength = .4),denoise = DenoiseRecipe.BilateralLumaV1(),detail = DetailRecipe.LumaDetail7V1(),binary = BinaryRecipe.GrayscaleV1)

    @Test fun noEnhancementIsTheFullPhotometricReferenceWithoutChangingGeometry() {
        assertEquals(Recipe(geometry = recipe.geometry),select("none"))
    }

    @Test fun whitePaperAndNaturalClearBinaryButRetainCompatibleFinishing() {
        assertEquals(recipe.copy(appearance = AppearanceRecipe.PaperGainV1(),binary = null),select("paper"))
        assertEquals(recipe.copy(appearance = AppearanceRecipe.NaturalLV1(),binary = null),select("natural"))
    }

    @Test fun grayscaleUsesAppearanceOffAndKeepsTheExplicitCompatibleFinishing() {
        assertEquals(recipe.copy(appearance = AppearanceRecipe.NoneV1,binary = BinaryRecipe.GrayscaleV1),select("gray"))
    }

    @Test fun blackAndWhiteExplicitlyClearsDetailAndRetainsSupportedDenoiseOnly() {
        assertEquals(recipe.copy(appearance = AppearanceRecipe.NoneV1,detail = null,binary = BinaryRecipe.SauvolaV1()),select("binary"))
        assertEquals(source,select("binary").geometry.source); assertEquals(3,select("binary").geometry.outputQuarterTurns)
    }

    @Test fun unknownPresetNeverBecomesAnotherMethodsRecipe() {
        assertThrows(IllegalArgumentException::class.java) { select("unavailable-neural-method") }
        assertEquals(.4,(recipe.appearance as AppearanceRecipe.PaperGainV1).strength,0.0); assertNotNull(recipe.detail)
    }

    private fun select(id: String): Recipe {
        val type = try { Class.forName("app.pristinepage.editor.EditorLooks") } catch (_: ClassNotFoundException) { throw AssertionError("Explicit editor Look mappings are absent") }
        return try { type.getMethod("select",Recipe::class.java,String::class.java).invoke(null,recipe,id) as Recipe } catch (error: InvocationTargetException) { throw error.targetException }
    }
}
