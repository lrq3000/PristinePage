package app.pristinepage.editor

import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.preview.RenderedPreviewBitmap
import app.pristinepage.image.preview.RenderedPreviewBitmaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The preview is borrowed from the scheduler, not another owner. A view must copy/convert it within the owning lifecycle; neither the result nor its pixels belong in saved state. Previous explicitly distinguishes an older completed same-page image from the requested selection. */
data class PreviewState(val request: RenderRequest? = null, val preview: RenderResult? = null, val processing: Boolean = false, val failure: RenderStatus? = null, val previous: Boolean = false, val display: RenderedPreviewBitmap? = null)

/** Main-confined selected-only interactive work, never WorkManager or a second render queue. The Application renderer still serializes/admit processes. The session calls close before its scope ends; native work that ignores cancellation may finish privately but cannot publish or escape ownership. Neighbor/card prioritization, debounce, bitmap lifetime and durable source pins are separate consumers. */
class PreviewScheduler @JvmOverloads constructor(private val renderer: RenderCoordinator, private val scope: CoroutineScope, private val converter: RenderedPreviewBitmaps? = null) : AutoCloseable {
    private val current = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = current.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    private var closed = false

    fun schedule(request: RenderRequest) {
        check(!closed) { "Preview session is closed" }
        // Never reuse a generation after Undo, retry or a page switch. Pixel identity alone does not distinguish an obsolete in-flight request from a newly selected identical recipe (ABA).
        require(request.generation > generation && request.recipeRevision > 0) { "A preview request needs a newer positive generation and retained revision" }
        generation = request.generation
        work?.cancel()
        val oldState = current.value
        val old = oldState.preview
        val previous = old?.takeIf { samePageSource(it.request, request) }
        current.value = PreviewState(request, previous, processing = true, previous = previous != null, display = oldState.display.takeIf { previous != null })
        if (old !== previous) release(oldState)
        work = scope.launch {
            var candidate: RenderResult? = null
            var display: RenderedPreviewBitmap? = null
            try {
                candidate = renderer.render(request)
                currentCoroutineContext().ensureActive()
                if (!isCurrent(request)) return@launch
                val image = candidate.image
                when {
                    candidate.request != request -> fail(request, RenderStatus.PROCESSING_FAILED)
                    candidate.status != RenderStatus.OK -> fail(request, candidate.status)
                    image == null || image.width != request.output.size.width || image.height != request.output.size.height -> fail(request, RenderStatus.PROCESSING_FAILED)
                    else -> {
                        // Keep the complete RGB/accounting owner private and alive through native conversion. Publishing first and converting the borrowed image asynchronously would race replacement/close.
                        display = converter?.convert(candidate)
                        currentCoroutineContext().ensureActive()
                        if (!isCurrent(request)) return@launch
                        if (display != null && display.request != request) { fail(request, RenderStatus.PROCESSING_FAILED); return@launch }
                        val replaced = current.value
                        current.value = PreviewState(request, candidate, display = display)
                        candidate = null // Transfer the WHOLE result, including accounting, only after publication succeeded.
                        display = null
                        release(replaced)
                    }
                }
            } catch (canceled: CancellationException) {
                fail(request, RenderStatus.CANCELED)
                throw canceled
            } catch (_: OutOfMemoryError) {
                // Conversion runs after the renderer's resource boundary. A replacement adapter can also exhaust memory; retain only the already completed previous pair and let finally release the private candidate.
                fail(request, RenderStatus.RESOURCE_LIMIT)
            } catch (failure: RepositoryFailure) {
                fail(request, when (failure.error.code) {
                    OperationErrorCode.RESOURCE_LIMIT -> RenderStatus.RESOURCE_LIMIT
                    OperationErrorCode.STORAGE_FAILED -> RenderStatus.STORAGE_FAILED
                    OperationErrorCode.INVALID_INPUT, OperationErrorCode.INTEGRITY_FAILED -> RenderStatus.INVALID_INPUT
                    OperationErrorCode.CANCELED -> RenderStatus.CANCELED
                    OperationErrorCode.PROVIDER_UNAVAILABLE, OperationErrorCode.UNSUPPORTED_VERSION -> RenderStatus.UNAVAILABLE
                    else -> RenderStatus.PROCESSING_FAILED
                })
            } catch (_: Exception) {
                // A broken adapter is not a user-visible exception message/path and must not erase a completed previous preview.
                fail(request, RenderStatus.PROCESSING_FAILED)
            } finally { try { display?.close() } finally { candidate?.close() } }
        }
    }

    private fun isCurrent(request: RenderRequest) = !closed && current.value.request == request
    private fun fail(request: RenderRequest, status: RenderStatus) {
        if (isCurrent(request)) current.value = current.value.copy(processing = false, failure = status, previous = current.value.preview != null)
    }

    private fun samePageSource(a: RenderRequest, b: RenderRequest) = a.pageId == b.pageId && a.sourceId == b.sourceId && a.sourcePixelHash == b.sourcePixelHash && a.recipe.geometry.source == b.recipe.geometry.source

    /** Detach draw state before releasing both owners. Pause is reversible but never resets generation, so late work cannot exploit request reuse after resume. */
    fun clear() {
        work?.cancel()
        val previous = current.value
        current.value = PreviewState()
        release(previous)
    }

    private fun release(state: PreviewState) { try { state.display?.close() } finally { state.preview?.close() } }

    override fun close() {
        if (closed) return
        closed = true
        clear()
    }
}
