package app.pristinepage.image

import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.repository.RecipeAdmission
import org.junit.Assert.*
import org.junit.Test

/** The real capability policy, not a fake accepting repository gate. This checks structural support only; actual map proof, output/memory admission and pixels remain the renderer's responsibility. */
class ClassicalRecipeAdmissionTest {
    private val source = SourceBinding("a".repeat(64),PixelSize(3,2),"fixture-srgb-v1")
    private val none = Recipe(geometry = GeometryRecipe.None(source))
    private val quad = QuadPx(PointPx(0.0,0.0),PointPx(1.0,0.0),PointPx(1.0,1.0),PointPx(0.0,1.0))
    private fun admission(): RecipeAdmission = try { Class.forName("app.pristinepage.image.render.ClassicalRecipeAdmission").getConstructor().newInstance() as RecipeAdmission } catch (_: ClassNotFoundException) { throw AssertionError("Classical recipe save admission is absent") }

    @Test fun knownClassicalRecipesCanBeSavedWithoutDecodingOrManufacturingAnExecution() {
        val gate = admission()
        for (recipe in listOf(none,none.copy(appearance = AppearanceRecipe.PaperGainV1()),none.copy(appearance = AppearanceRecipe.NaturalLV1()),none.copy(denoise = DenoiseRecipe.BilateralLumaV1(),detail = DetailRecipe.LumaDetail7V1()),none.copy(binary = BinaryRecipe.SauvolaV1()),none.copy(geometry = GeometryRecipe.Perspective(source,quad)),none.copy(geometry = GeometryRecipe.ManualCurve(source,quad)))) assertNull(gate.check(recipe,false))
    }

    @Test fun missingMethodsAndNeuralGeometryRemainUnavailableEvenAtZeroStrength() {
        val gate = admission(); val provider = ProviderReference("missing",1,"b".repeat(64),"test","c".repeat(64))
        for (recipe in listOf(none.copy(appearance = AppearanceRecipe.LocalMaxGainV1(strength = 0.0)),none.copy(appearance = AppearanceRecipe.ProviderAppearanceV1("missing",1,"b".repeat(64),"test","c".repeat(64),0.0)),none.copy(geometry = GeometryRecipe.RoughCropDewarp(source,RectPx(0,0,3,2),provider)))) assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE,gate.check(recipe,false)!!.code)
    }

    @Test fun zeroStrengthInvalidMaskedGraphCannotHideBehindUnavailableMaskStorage() {
        val gate = admission()
        assertEquals(OperationErrorCode.INVALID_INPUT,gate.check(none.copy(detail = DetailRecipe.LumaDetail7V1(strength = 0.0)),true)!!.code)
        assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE,gate.check(none,true)!!.code)
    }

    @Test fun structurallyCrossedGeometryCannotBeSavedAsAnAvailableCorrection() {
        val invalid = QuadPx(quad.tl,quad.br,quad.tr,quad.bl)
        assertEquals(OperationErrorCode.INVALID_INPUT,admission().check(none.copy(geometry = GeometryRecipe.Perspective(source,invalid)),false)!!.code)
    }

    @Test fun oversizedSourceBindingRefusesWithoutAnyAllocationAttempt() {
        val large = source.copy(sourceSize = PixelSize(12001,2))
        assertEquals(OperationErrorCode.RESOURCE_LIMIT,admission().check(none.copy(geometry = GeometryRecipe.None(large)),false)!!.code)
    }
}
