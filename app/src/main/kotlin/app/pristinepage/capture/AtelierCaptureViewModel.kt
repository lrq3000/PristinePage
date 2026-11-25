package app.pristinepage.capture

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pristinepage.ApplicationImports
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.AtelierDraftView
import app.pristinepage.editor.EditorActions
import app.pristinepage.editor.EditorSession
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.image.preview.RenderedPreviewBitmapLoader
import app.pristinepage.library.ApplicationLibraryActions
import app.pristinepage.library.LibraryPhotoResult
import app.pristinepage.ocr.sendCommittedOcrHint
import app.pristinepage.data.library.LibraryActivation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A null position is a picker/camera handoff failure; a null error is an unfinished child copy. */
internal data class AtelierImportIssue(val position: Int?, val error: OperationError?)

internal data class AtelierCaptureState(
    val open: Boolean = false,
    val draft: AtelierDraftView? = null,
    val busy: Boolean = false,
    val request: PhotoImportRequest? = null,
    val error: OperationError? = null,
    val saved: DocumentId? = null,
    val editor: EditorSession? = null,
    val resumeChoice: Boolean = false,
    val importIssues: List<AtelierImportIssue> = emptyList(),
) {
    fun withImportResults(batch: ImportSessionId, results: List<IngestResult>): AtelierCaptureState =
        copy(importIssues = results.mapIndexedNotNull { index, result ->
            when (result) {
                is IngestResult.Committed -> null // Only the refreshed durable queue supplies accepted pages.
                is IngestResult.Pending -> AtelierImportIssue(index + 1, null)
                is IngestResult.Rejected -> if (result.sessionId == batch && result.error.code == OperationErrorCode.CANCELED) null
                    else AtelierImportIssue((index + 1).takeIf { result.sessionId != batch }, result.error.copy(affectedId = null))
            }
        })
}

/** One retained owner of platform callbacks and the durable draft. A reopened batch is never treated as a fresh picker launch or a retained permission. */
internal class AtelierCaptureViewModel(
    context: Context,
    private val graph: suspend () -> ApplicationImports,
    private val onPagesSaved: (LibraryActivation, DocumentId, List<PageId>) -> Unit,
) : ViewModel() {
    private val imports = ApplicationLibraryActions(context.applicationContext, graph)
    private val current = MutableStateFlow(AtelierCaptureState())
    val state = current.asStateFlow()
    private var dispatched: PhotoImportRequest? = null
    fun acknowledgeSave() { if (!current.value.open) current.value = AtelierCaptureState() }

    fun open(title: String, target: DocumentSummary?) {
        if (current.value.open) return
        current.value = AtelierCaptureState(open = true)
        run {
            val api = graph().store.atelier
            val existing = api.pending()
            if (existing != null) current.value = current.value.copy(draft = existing, resumeChoice = true)
            else show(api.create(title, target?.documentId, target?.documentRevision))
        }
    }

    fun resumeChoice() {
        val existing = current.value.draft ?: return
        run {
            show(graph().store.atelier.refresh(existing))
            current.value = current.value.copy(resumeChoice = false)
        }
    }

    fun reserve(kind: PhotoImportKind) {
        val draft = current.value.draft ?: return
        if (current.value.request != null || draft.pending != null || draft.editing) return
        run {
            current.value = current.value.copy(importIssues = emptyList())
            val api = graph().store.atelier
            val request = PhotoImportRequest(api.begin(draft, camera = kind == PhotoImportKind.CAMERA).value, kind)
            dispatched = null
            current.value = current.value.copy(draft = api.refresh(draft), request = request, busy = false)
        }
    }

    fun claim(request: PhotoImportRequest): Boolean {
        if (current.value.request != request || dispatched == request) return false
        dispatched = request
        return true
    }

    fun delivered(request: PhotoImportRequest, result: LibraryPhotoResult): Boolean {
        val state = current.value
        val draft = state.draft ?: return false
        if (state.request != request || dispatched != request || state.busy) return false
        current.value = state.copy(request = null)
        run {
            try {
                val results = imports.deliver(request, result)
                current.value = current.value.withImportResults(request.session, results)
            } finally { current.value = current.value.copy(draft = graph().store.atelier.refresh(draft)) }
        }
        return true
    }

    fun cancelPending() {
        val draft = current.value.draft ?: return
        val request = current.value.request ?: draft.pending?.let { PhotoImportRequest(it.value) } ?: return
        run {
            imports.cancel(request)
            current.value = current.value.copy(request = null, draft = graph().store.atelier.refresh(draft))
        }
    }

    fun finish() {
        val draft = current.value.draft ?: return
        run { show(graph().store.atelier.finish(draft)) }
    }

    fun skipPerspective() {
        val draft = current.value.draft ?: return
        val selected = draft.selected ?: return
        if (!draft.editing || draft.step != 0 || current.value.editor?.state?.value?.saving == true) return
        run {
            val app = graph()
            val api = app.store.atelier
            withSkipRecovery(recover = {
                var restored = false
                try {
                    val latest = api.refresh(draft)
                    val editorPage = current.value.editor?.state?.value?.page
                    val livePage = latest.pages.singleOrNull { it.pageId == latest.selected }
                    if (latest.generation != draft.generation || latest.step != draft.step ||
                        livePage == null || editorPage?.pageId != livePage.pageId || editorPage.recipeRevision != livePage.recipeRevision) {
                        show(latest)
                    } else current.value = current.value.copy(draft = latest)
                    restored = true
                } finally {
                    // An unverifiable revision is not an editable snapshot. Resume refreshes the same opaque draft before enabling controls again.
                    if (!restored) {
                        current.value.editor?.close()
                        current.value = current.value.copy(editor = null, resumeChoice = true)
                    }
                }
            }) {
                for (item in draft.pages) {
                    val page = api.load(draft, item.pageId)
                    val shape = page.recipe.geometry
                    val whole = GeometryRecipe.None(shape.source, outputQuarterTurns = shape.outputQuarterTurns)
                    if (whole == shape) continue
                    val recipe = page.recipe.copy(geometry = whole)
                    when (val result = api.checkpoint(draft, page.pageId, page.recipeRevision, recipe, app.renderer)) {
                        is SaveRecipeResult.Saved -> Unit
                        is SaveRecipeResult.Conflict -> throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT,
                            false, "draft_checkpoint_conflict", page.pageId.value))
                        is SaveRecipeResult.Rejected -> throw RepositoryFailure(result.error)
                    }
                }
                show(api.navigate(draft, selected, 1))
            }
        }
    }

    fun navigate(page: PageId, step: Int) {
        val draft = current.value.draft ?: return
        if (!draft.editing || page == draft.selected && step == draft.step) return
        afterCheckpoint { show(graph().store.atelier.navigate(draft, page, step)) }
    }

    fun addMore() {
        val draft = current.value.draft ?: return
        afterCheckpoint { show(graph().store.atelier.addMore(draft)) }
    }

    fun save() {
        val draft = current.value.draft ?: return
        afterCheckpoint {
            val app = graph()
            val api = app.store.atelier
            val snapshot = api.refresh(draft)
            val pages = snapshot.pages.map { api.load(snapshot, it.pageId) }
            val saved = api.save(snapshot, pages, app.renderer)
            current.value.editor?.close()
            current.value = AtelierCaptureState(saved = saved.document)
            sendCommittedOcrHint(saved.document, saved.pageIds) { album, pages -> onPagesSaved(app.activation, album, pages) }
        }
    }

    private fun afterCheckpoint(action: suspend () -> Unit) {
        val editor = current.value.editor ?: return
        run { if (editor.checkpointForNavigation()) action() }
    }

    fun leave(abandon: Boolean) {
        if (current.value.busy || current.value.request != null || current.value.editor?.state?.value?.saving == true) return
        run {
            val draft = current.value.draft
            if (abandon && draft != null) graph().store.atelier.abandon(draft)
            current.value.editor?.close()
            current.value = AtelierCaptureState()
        }
    }

    private suspend fun show(view: AtelierDraftView) {
        val selected = view.selected
        val editor = if (view.editing && selected != null) {
            val app = graph()
            val api = app.store.atelier
            val page = api.load(view, selected)
            EditorSession(page.documentId.value, selected.value, object : EditorActions {
                override suspend fun load(documentId: DocumentId, pageId: PageId) = api.load(view, pageId)
                override suspend fun save(pageId: PageId, expectedRevision: Long, recipe: app.pristinepage.core.pipeline.Recipe) =
                    api.checkpoint(view, pageId, expectedRevision, recipe, app.renderer)
            }, app.renderer, viewModelScope, RenderedPreviewBitmapLoader(app.budget), app.budget).also { it.setActive(false) }
        } else null
        val old = current.value.editor
        current.value = current.value.copy(draft = view, editor = editor)
        old?.close()
    }

    private fun run(action: suspend () -> Unit) {
        if (current.value.busy) return
        val departing = current.value.editor
        departing?.setAnalysisTransition(true)
        current.value = current.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action() }
            catch (canceled: CancellationException) { throw canceled }
            catch (failure: RepositoryFailure) { current.value = current.value.copy(error = failure.error.copy(affectedId = null)) }
            catch (_: Exception) { current.value = current.value.copy(error = OperationError(OperationErrorCode.STORAGE_FAILED, true, "draft_storage_failed", null)) }
            finally {
                departing?.setAnalysisTransition(false)
                current.value = current.value.copy(busy = false)
            }
        }
    }

    override fun onCleared() { current.value.editor?.close() }
}

/** A later page can fail after an earlier private reset has committed. Reload before returning the original failure. */
internal suspend fun withSkipRecovery(recover: suspend () -> Unit, reset: suspend () -> Unit) {
    try { reset() }
    catch (canceled: CancellationException) { throw canceled }
    catch (failure: Exception) {
        try { recover() }
        catch (canceled: CancellationException) { throw canceled }
        catch (recovery: Exception) { if (recovery !== failure) failure.addSuppressed(recovery) }
        throw failure
    }
}
