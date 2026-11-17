package app.pristinepage.editor

import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.core.pipeline.RenderStatus
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
data class PreviewState(val request: RenderRequest? = null, val preview: RenderResult? = null, val processing: Boolean = false, val failure: RenderStatus? = null, val previous: Boolean = false)

/** Main-confined selected-only interactive work, never WorkManager or a second render queue. The Application renderer still serializes/admit processes. The session calls close before its scope ends; native work that ignores cancellation may finish privately but cannot publish or escape ownership. Neighbor/card prioritization, debounce, bitmap lifetime and durable source pins are separate consumers. */
class PreviewScheduler(private val renderer: RenderCoordinator, private val scope: CoroutineScope) : AutoCloseable {
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
        val old = current.value.preview
        val previous = old?.takeIf { samePageSource(it.request, request) }
        current.value = PreviewState(request, previous, processing = true, previous = previous != null)
        if (old !== previous) old?.close()
        work = scope.launch {
            var candidate: RenderResult? = null
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
                        val replaced = current.value.preview
                        current.value = PreviewState(request, candidate)
                        candidate = null // Transfer the WHOLE result, including accounting, only after publication succeeded.
                        replaced?.close()
                    }
                }
            } catch (canceled: CancellationException) {
                fail(request, RenderStatus.CANCELED)
                throw canceled
            } catch (_: Exception) {
                // A broken adapter is not a user-visible exception message/path and must not erase a completed previous preview.
                fail(request, RenderStatus.PROCESSING_FAILED)
            } finally { candidate?.close() }
        }
    }

    private fun isCurrent(request: RenderRequest) = !closed && current.value.request == request
    private fun fail(request: RenderRequest, status: RenderStatus) {
        if (isCurrent(request)) current.value = current.value.copy(processing = false, failure = status, previous = current.value.preview != null)
    }

    private fun samePageSource(a: RenderRequest, b: RenderRequest) = a.pageId == b.pageId && a.sourceId == b.sourceId && a.sourcePixelHash == b.sourcePixelHash && a.recipe.geometry.source == b.recipe.geometry.source

    override fun close() {
        if (closed) return
        closed = true
        work?.cancel()
        val previous = current.value.preview
        current.value = PreviewState()
        previous?.close()
    }
}
