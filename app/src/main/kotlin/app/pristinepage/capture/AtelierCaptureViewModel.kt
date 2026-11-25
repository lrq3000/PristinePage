package app.pristinepage.capture

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pristinepage.ApplicationServices
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.AtelierDraftView
import app.pristinepage.data.repository.AtelierSavedPages
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
    val activation: LibraryActivation? = null,
    val draft: AtelierDraftView? = null,
    val busy: Boolean = false,
    val request: PhotoImportRequest? = null,
    val error: OperationError? = null,
    val saved: DocumentId? = null,
    val editor: EditorSession? = null,
    val resumeChoice: Boolean = false,
    val photoNeedsRefresh: Boolean = false,
    val saveNeedsCheck: Boolean = false,
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
    private val services: ApplicationServices,
    private val onPagesSaved: (LibraryActivation, DocumentId, List<PageId>) -> Unit,
) : ViewModel() {
    private val imports = ApplicationLibraryActions(context.applicationContext, null, services)
    private val current = MutableStateFlow(AtelierCaptureState())
    val state = current.asStateFlow()
    private var dispatched: PhotoImportRequest? = null
    private var unconfirmedSave: ApplicationServices.AtelierSave? = null
    fun acknowledgeSave() { if (!current.value.open) current.value = AtelierCaptureState() }

    fun open(issuedAt: LibraryActivation, title: String, target: DocumentSummary?) {
        if (current.value.open) return
        current.value = AtelierCaptureState(open = true, activation = issuedAt)
        run {
            val opened = services.openDefaultAtelier(issuedAt, title, target)
            if (opened.needsResume) current.value = current.value.copy(draft = opened.draft, resumeChoice = true)
            else show(issuedAt, opened.draft)
        }
    }

    fun resumeChoice() {
        val existing = current.value.draft ?: return
        run { issuedAt ->
            show(issuedAt, services.refreshDefaultAtelier(issuedAt, existing))
            current.value = current.value.copy(resumeChoice = false)
        }
    }

    fun reserve(kind: PhotoImportKind) {
        val draft = current.value.draft ?: return
        if (current.value.request != null || draft.pending != null || draft.editing || current.value.photoNeedsRefresh) return
        run { issuedAt ->
            current.value = current.value.copy(importIssues = emptyList())
            withSkipRecovery(recover = { reconcilePhoto(issuedAt, draft) }) {
                val request = services.reserveDefaultAtelier(issuedAt, draft, kind)
                val observed = services.refreshDefaultAtelier(issuedAt, draft)
                if (observed.generation != draft.generation || observed.pending != request.session) photoChanged()
                dispatched = null
                current.value = current.value.copy(draft = observed, request = request)
            }
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
        run { issuedAt ->
            withSkipRecovery(recover = { reconcilePhoto(issuedAt, draft) }) {
                val delivered = imports.deliverAtelier(issuedAt, draft, request, result)
                current.value = current.value.withImportResults(request.session, delivered.results).copy(draft = delivered.draft)
            }
        }
        return true
    }

    fun cancelPending() {
        val draft = current.value.draft ?: return
        if (current.value.request != null || current.value.photoNeedsRefresh) return
        val batch = draft.pending ?: return
        run { issuedAt ->
            val request = dispatched?.takeIf { it.issuedAt == issuedAt && it.session == batch }
                ?: PhotoImportRequest.abandonmentOnly(batch, issuedAt)
            withSkipRecovery(recover = { reconcilePhoto(issuedAt, draft) }) {
                current.value = current.value.copy(draft = imports.cancelAtelier(issuedAt, draft, request))
                dispatched = null
            }
        }
    }

    fun refreshPhoto() {
        val draft = current.value.draft ?: return
        if (current.value.photoNeedsRefresh) run { issuedAt -> reconcilePhoto(issuedAt, draft) }
    }

    private suspend fun reconcilePhoto(issuedAt: LibraryActivation, draft: AtelierDraftView) {
        current.value = current.value.copy(photoNeedsRefresh = true)
        val latest = services.refreshDefaultAtelier(issuedAt, draft)
        if (latest.generation != draft.generation) photoChanged()
        if (latest.pending != dispatched?.session) dispatched = null
        current.value = current.value.copy(draft = latest, photoNeedsRefresh = false)
    }

    private fun photoChanged(): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.SESSION_EXPIRED, false, "photo_request_expired", null))

    fun finish() {
        val draft = current.value.draft ?: return
        if (current.value.photoNeedsRefresh) return
        run { issuedAt -> show(issuedAt, services.finishDefaultAtelier(issuedAt, draft)) }
    }

    fun skipPerspective() {
        val draft = current.value.draft ?: return
        val selected = draft.selected ?: return
        if (!draft.editing || draft.step != 0 || current.value.editor?.state?.value?.saving == true) return
        run { issuedAt ->
            withSkipRecovery(recover = {
                var restored = false
                try {
                    val latest = services.refreshDefaultAtelier(issuedAt, draft)
                    val editorPage = current.value.editor?.state?.value?.page
                    val livePage = latest.pages.singleOrNull { it.pageId == latest.selected }
                    if (latest.generation != draft.generation || latest.step != draft.step ||
                        livePage == null || editorPage?.pageId != livePage.pageId || editorPage.recipeRevision != livePage.recipeRevision) {
                        show(issuedAt, latest)
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
                    val page = services.loadDefaultAtelierPage(issuedAt, draft, item.pageId)
                    val shape = page.recipe.geometry
                    val whole = GeometryRecipe.None(shape.source, outputQuarterTurns = shape.outputQuarterTurns)
                    if (whole == shape) continue
                    val recipe = page.recipe.copy(geometry = whole)
                    when (val result = services.checkpointDefaultAtelier(issuedAt, draft, page.pageId, page.recipeRevision, recipe)) {
                        is SaveRecipeResult.Saved -> Unit
                        is SaveRecipeResult.Conflict -> throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT,
                            false, "draft_checkpoint_conflict", page.pageId.value))
                        is SaveRecipeResult.Rejected -> throw RepositoryFailure(result.error)
                    }
                }
                show(issuedAt, services.navigateDefaultAtelier(issuedAt, draft, selected, 1))
            }
        }
    }

    fun navigate(page: PageId, step: Int) {
        val draft = current.value.draft ?: return
        if (!draft.editing || page == draft.selected && step == draft.step) return
        afterCheckpoint { issuedAt -> show(issuedAt, services.navigateDefaultAtelier(issuedAt, draft, page, step)) }
    }

    fun addMore() {
        val draft = current.value.draft ?: return
        afterCheckpoint { issuedAt -> show(issuedAt, services.addToDefaultAtelier(issuedAt, draft)) }
    }

    fun save() {
        val draft = current.value.draft ?: return
        val editor = current.value.editor ?: return
        afterCheckpoint { issuedAt ->
            val input = services.freezeDefaultAtelierSave(issuedAt, draft, checkNotNull(editor.state.value.page))
            unconfirmedSave = input
            current.value = current.value.copy(saveNeedsCheck = true)
            val saved = try { services.saveDefaultAtelier(input) }
            catch (failure: RepositoryFailure) {
                // The repository CAS returns these errors before publication. Other failures need receipt inspection before retrying.
                if (failure.error.code == OperationErrorCode.REVISION_CONFLICT) clearSaveCheck()
                throw failure
            }
            acceptSave(issuedAt, saved)
        }
    }

    fun checkSave() {
        val input = unconfirmedSave ?: return
        run {
            val saved = services.recoverDefaultAtelierSave(input)
            if (saved != null) acceptSave(input.issuedAt, saved)
            else {
                clearSaveCheck()
                current.value = current.value.copy(error = OperationError(OperationErrorCode.STORAGE_FAILED, false, "atelier_save_not_found", null))
            }
        }
    }

    private fun clearSaveCheck() {
        unconfirmedSave = null
        current.value = current.value.copy(saveNeedsCheck = false)
    }

    private fun acceptSave(issuedAt: LibraryActivation, saved: AtelierSavedPages) {
        unconfirmedSave = null
        current.value.editor?.close()
        current.value = AtelierCaptureState(activation = issuedAt, saved = saved.document)
        sendCommittedOcrHint(saved.document, saved.pageIds) { album, pages -> onPagesSaved(issuedAt, album, pages) }
    }

    private fun afterCheckpoint(action: suspend (LibraryActivation) -> Unit) {
        if (current.value.saveNeedsCheck) return
        val editor = current.value.editor ?: return
        run { issuedAt -> if (editor.checkpointForNavigation()) action(issuedAt) }
    }

    fun leave(abandon: Boolean) {
        if (current.value.busy || current.value.request != null || current.value.editor?.state?.value?.saving == true) return
        if (abandon && (current.value.saveNeedsCheck || current.value.photoNeedsRefresh)) return
        run { issuedAt ->
            val draft = current.value.draft
            if (abandon && draft != null) services.abandonDefaultAtelier(issuedAt, draft)
            current.value.editor?.close()
            unconfirmedSave = null
            dispatched = null
            current.value = AtelierCaptureState()
        }
    }

    private suspend fun show(issuedAt: LibraryActivation, view: AtelierDraftView) {
        val selected = view.selected
        val editor = if (view.editing && selected != null) {
            val page = services.loadDefaultAtelierPage(issuedAt, view, selected)
            val renderer = object : RenderCoordinator {
                override suspend fun render(request: RenderRequest) = services.renderDefaultAtelier(issuedAt, view, request)
            }
            EditorSession(page.documentId.value, selected.value, object : EditorActions {
                override suspend fun load(documentId: DocumentId, pageId: PageId) = services.loadDefaultAtelierPage(issuedAt, view, pageId)
                override suspend fun save(pageId: PageId, expectedRevision: Long, recipe: app.pristinepage.core.pipeline.Recipe) =
                    services.checkpointDefaultAtelier(issuedAt, view, pageId, expectedRevision, recipe)
            }, renderer, viewModelScope, RenderedPreviewBitmapLoader(services.budget), services.budget).also { it.setActive(false) }
        } else null
        val old = current.value.editor
        current.value = current.value.copy(draft = view, editor = editor)
        old?.close()
    }

    private fun run(action: suspend (LibraryActivation) -> Unit) {
        if (current.value.busy) return
        val issuedAt = current.value.activation ?: return
        val departing = current.value.editor
        departing?.setAnalysisTransition(true)
        current.value = current.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action(issuedAt) }
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
