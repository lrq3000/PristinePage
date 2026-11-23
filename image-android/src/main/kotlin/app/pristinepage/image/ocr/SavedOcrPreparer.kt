package app.pristinepage.image.ocr

import android.graphics.Bitmap
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.preview.SrgbPreviewBitmap
import app.pristinepage.image.render.DefaultRenderCoordinator
import app.pristinepage.image.render.OCR_RENDER_BUDGET_BUSY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Prepare the exact saved recipe through the shared renderer without creating a second decoder or image allowance. The store retains the live source and calls this off Main. */
class SavedOcrPreparer(
    private val renderer: RenderCoordinator,
    private val budget: ImageAllocationBudget,
    private val sessions: SessionFactory,
) : SavedOcrWork.Preparer {
    fun interface SessionFactory {
        /** Open the exact independently verified installed configuration, not current defaults. Check cancellation during bounded I/O and pixel copying. Borrow the private input until the returned session closes; never recycle or publish it. On failure the factory owns cleanup of any partial session. Neither open nor close may recognize. */
        fun open(configuration: OcrConfiguration, input: Bitmap, checkpoint: () -> Unit): SavedOcrWork.Prepared
    }

    override suspend fun prepare(page: EditablePage, configuration: OcrConfiguration, handoff: SavedOcrWork.Handoff) {
        val active = currentCoroutineContext()
        active.ensureActive()
        if (configuration.languagePacks == null) refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "ocr_exact_packs_unavailable")
        val geometry = page.recipe.geometry
        val output = try { GeometrySizing.ocr(geometry) }
        catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INVALID_INPUT, "ocr_render_invalid_geometry") }
        val size = output.size
        val request = RenderRequest(page.sourceId.value, geometry.source.sourcePixelHash, page.pageId.value,
            page.recipeRevision, page.recipe, output, 0, null, FallbackPolicy.FAIL)
        val resources = Resources()
        try {
            val exactRenderer = renderer as? DefaultRenderCoordinator
            val rendered = try { exactRenderer?.renderForOcrPreparation(request, budget) ?: renderer.render(request) }
            catch (failure: RepositoryFailure) {
                if (exactRenderer?.provedImpossibleIdleLimit(failure, request) == true) throw SavedOcrWork.IdleBudgetRefusal(page)
                throw failure
            }
            rendered.use { result ->
                active.ensureActive()
                if (result.request != request) refuse(OperationErrorCode.REVISION_CONFLICT, "ocr_render_binding_changed")
                if (result.status != RenderStatus.OK) rejectStatus(result)
                val image = result.image
                if (image == null || image.width != size.width || image.height != size.height) {
                    refuse(OperationErrorCode.INTEGRITY_FAILED, "ocr_render_size_invalid")
                }
                resources.bitmap = SrgbPreviewBitmap.convertForOcr(image, budget) { active.ensureActive() }
            }
            // Release the full render and its permit before allocating native resources. All remaining ownership transfers synchronously.
            active.ensureActive()
            resources.session = sessions.open(configuration, checkNotNull(resources.bitmap).bitmap) { active.ensureActive() }
            active.ensureActive()
            handoff.retain(resources)
        } catch (failure: Throwable) {
            try { resources.close() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private class Resources : SavedOcrWork.Prepared {
        var bitmap: SrgbPreviewBitmap? = null
        var session: SavedOcrWork.Prepared? = null
        override suspend fun recognize(): OcrText = checkNotNull(session).recognize()

        override fun close() {
            val engine = session.also { session = null }
            val pixels = bitmap.also { bitmap = null }
            // No draw consumer ever received this bitmap; stop every borrowed native use before recycling the private copy and returning the permit.
            try { engine?.close() } finally { pixels?.discardUndelivered() }
        }
    }

    private fun rejectStatus(result: RenderResult): Nothing = when (result.status) {
        RenderStatus.CANCELED -> throw CancellationException("OCR source rendering canceled")
        RenderStatus.RESOURCE_LIMIT -> when (result.warnings.singleOrNull()) {
            OCR_RENDER_BUDGET_BUSY -> refuse(OperationErrorCode.RESOURCE_LIMIT, "ocr_render_resource_limit", retryable = true)
            else -> refuse(OperationErrorCode.RESOURCE_LIMIT, "ocr_render_resource_limit")
        }
        RenderStatus.STORAGE_FAILED -> refuse(OperationErrorCode.STORAGE_FAILED, "ocr_render_storage_failed", true)
        RenderStatus.UNAVAILABLE -> refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "ocr_render_unavailable")
        RenderStatus.INVALID_INPUT -> refuse(OperationErrorCode.INVALID_INPUT, "ocr_render_invalid_input")
        RenderStatus.INVALID_MAP -> refuse(OperationErrorCode.INVALID_MAP, "ocr_render_invalid_map")
        RenderStatus.DEGRADED, RenderStatus.PROCESSING_FAILED, RenderStatus.OK ->
            refuse(OperationErrorCode.PROCESSING_FAILED, "ocr_render_not_exact")
    }

    private fun refuse(code: OperationErrorCode, key: String, retryable: Boolean = false): Nothing =
        throw RepositoryFailure(OperationError(code, retryable, key, null))
}
