package app.pristinepage.image.export

import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.CommittedSources
import app.pristinepage.image.preview.PreviewProcessing
import app.pristinepage.image.render.ClassicalRecipeAdmission
import app.pristinepage.image.render.GeometryEngine
import kotlinx.coroutines.*

/** One ordinary frozen page to a complete owned raster through the Application renderer. Caller retains its snapshot/job source pins until close; this is not a lease acquisition, journal checkpoint, encoder, completed export or accepted-degraded replay path. */
class FrozenPageRenderer @JvmOverloads constructor(private val sources: CommittedSources, private val renderer: RenderCoordinator, private val rendererVersion: String, encodingVersion: String, backend: String, precision: String, private val dispatcher: CoroutineDispatcher = PreviewProcessing.dispatcher) {
    private val profile = OrdinaryExportProfile(rendererVersion,encodingVersion,backend,precision)
    private val descriptor = profile.providerDescriptor()

    suspend fun render(page: ExportPageSnapshot): RenderResult {
        var completed: RenderResult? = null
        try {
            val delivered = withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                val request = request(page)
                val source = try { sources.load(page.sourceId) }
                catch (canceled: CancellationException) { throw canceled }
                catch (failure: RepositoryFailure) { throw failure }
                catch (_: Exception) { refuse(OperationErrorCode.STORAGE_FAILED,"export_source_read_failed",page) }
                val info = source.inspection
                if (source.sourceId != page.sourceId || source.sourceBlobId != page.sourceBlobId || source.sourceByteHash != page.byteHash || info.sourcePixelHash != page.pixelHash || info.normalizedSize != page.sourceSize || info.conversionFingerprint != page.conversionFingerprint) refuse(OperationErrorCode.INTEGRITY_FAILED,"export_source_changed",page)
                currentCoroutineContext().ensureActive()
                val result = renderer.render(request).also { completed = it }
                currentCoroutineContext().ensureActive()
                if (result.request != request) refuse(OperationErrorCode.INTEGRITY_FAILED,"export_render_identity_mismatch",page)
                if (result.status != RenderStatus.OK) refuse(status(result.status),"export_render_refused",page)
                val image = result.image ?: refuse(OperationErrorCode.INTEGRITY_FAILED,"export_render_missing_pixels",page)
                if (PixelSize(image.width,image.height) != page.output.size) refuse(OperationErrorCode.INTEGRITY_FAILED,"export_render_size_mismatch",page)
                image.getUnsigned(0,0,0) // Refuse an already-closed raster rather than forwarding an unusable success to an encoder.
                result
            }
            completed = null // The complete result, including its accounting owner, passes to the caller only after dispatcher delivery succeeds.
            return delivered
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: OutOfMemoryError) { refuse(OperationErrorCode.RESOURCE_LIMIT,"export_render_allocation_failed",page) }
        catch (_: Exception) { refuse(OperationErrorCode.PROCESSING_FAILED,"export_render_failed",page) }
        finally {
            // Non-null only on failure/canceled delivery: cleanup must not replace the initiating safe refusal with a raw private-path exception. A delivered caller still owns and observes its own close normally.
            try { completed?.close() } catch (_: Exception) { }
        }
    }

    private fun request(page: ExportPageSnapshot): RenderRequest {
        if (page.fallbackPolicy != FallbackPolicy.FAIL || page.acceptedRenderId != null || page.acceptedExecutionHash != null || !page.masks.contentEquals("[]".toByteArray()) || page.rendererVersion != rendererVersion || !page.providers.contentEquals(descriptor) || page.assetSetHash != OrdinaryExportProfile.emptyAssetSetHash) refuse(OperationErrorCode.PROVIDER_UNAVAILABLE,"export_frozen_capability_unavailable",page)
        if (page.recipeSchemaVersion != 1) refuse(OperationErrorCode.UNSUPPORTED_VERSION,"export_recipe_unsupported",page)
        val bytes = page.canonicalRecipe
        val recipe = try { RecipeCodec.decode(bytes) } catch (_: IllegalArgumentException) { refuse(OperationErrorCode.UNSUPPORTED_VERSION,"export_recipe_unsupported",page) }
        if (!bytes.contentEquals(RecipeCodec.encode(recipe)) || RecipeCodec.hash(recipe) != page.recipeHash || recipe.geometry.source != SourceBinding(page.pixelHash,page.sourceSize,page.conversionFingerprint) || page.output != page.requestedOutput || page.output.purpose != RenderPurpose.EXPORT || page.recipeRevision < 1) refuse(OperationErrorCode.INTEGRITY_FAILED,"export_frozen_recipe_mismatch",page)
        ClassicalRecipeAdmission().check(recipe,false)?.let { throw RepositoryFailure(it.copy(affectedId = page.pageId.value)) }
        try { GeometryEngine.validateOutput(GeometrySizing.target(recipe.geometry),page.output.size,recipe.geometry !is app.pristinepage.core.recipe.GeometryRecipe.None) }
        catch (_: RepositoryFailure) { refuse(OperationErrorCode.INTEGRITY_FAILED,"export_frozen_output_invalid",page) }
        return RenderRequest(page.sourceId.value,page.pixelHash,page.pageId.value,page.recipeRevision,recipe,page.output,0,null,FallbackPolicy.FAIL)
    }

    private fun status(value: RenderStatus): OperationErrorCode = when (value) {
        RenderStatus.RESOURCE_LIMIT -> OperationErrorCode.RESOURCE_LIMIT
        RenderStatus.STORAGE_FAILED -> OperationErrorCode.STORAGE_FAILED
        RenderStatus.CANCELED -> OperationErrorCode.CANCELED
        RenderStatus.INVALID_MAP -> OperationErrorCode.INVALID_MAP
        RenderStatus.INVALID_INPUT -> OperationErrorCode.INVALID_INPUT
        RenderStatus.UNAVAILABLE -> OperationErrorCode.PROVIDER_UNAVAILABLE
        else -> OperationErrorCode.PROCESSING_FAILED
    }
    private fun refuse(code: OperationErrorCode, key: String, page: ExportPageSnapshot): Nothing = throw RepositoryFailure(OperationError(code,false,key,page.pageId.value))
}
