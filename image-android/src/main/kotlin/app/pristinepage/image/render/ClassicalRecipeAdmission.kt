package app.pristinepage.image.render

import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.RecipeAdmission
import kotlinx.coroutines.CancellationException

/** Bounded structural/capability preflight for storing supported recipes, not an execution, map certificate or memory guarantee. Reuse the actual appearance policy and geometry sizing; actual source proof, output admission and geometry validation still run in RenderCoordinator. Data independently checks the live source binding and expected revision before writing. */
class ClassicalRecipeAdmission : RecipeAdmission {
    override fun check(recipe: Recipe, hasProtection: Boolean): OperationError? = try {
        val source = recipe.geometry.source.sourceSize
        if (source.width > 12000 || source.height > 12000 || source.pixelCount > 48_000_000) {
            error(OperationErrorCode.RESOURCE_LIMIT,"recipe_source_dimensions_limit")
        } else {
            GeometrySizing.target(recipe.geometry)
            // Keep zero-strength incompatibility ordering identical to the real coordinator. A valid protected graph is still unavailable until its durable mask consumer exists, never silently unmasked.
            AppearanceEngine.preflight(recipe,hasProtection)
            when {
                hasProtection -> error(OperationErrorCode.PROVIDER_UNAVAILABLE,"render_protection_not_implemented")
                recipe.geometry is GeometryRecipe.RoughCropDewarp -> error(OperationErrorCode.PROVIDER_UNAVAILABLE,"render_geometry_not_implemented")
                else -> null
            }
        }
    } catch (canceled: CancellationException) { throw canceled }
    catch (failure: RepositoryFailure) { failure.error }
    catch (_: IllegalArgumentException) { error(OperationErrorCode.INVALID_INPUT,"invalid_recipe_geometry") }
    catch (_: Exception) { error(OperationErrorCode.PROCESSING_FAILED,"recipe_admission_failed") }

    private fun error(code: OperationErrorCode, key: String) = OperationError(code,false,key,null)
}
