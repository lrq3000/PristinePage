package app.pristinepage.editor

import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.model.*
import app.pristinepage.image.preview.RenderedPreviewBitmaps
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-confined retained editor state. Only immutable recipes enter history; every image request starts from the same retained source via the Application renderer. UI recreation may retain this session, but close is terminal and fences privately finishing IO. */
class EditorSession @JvmOverloads constructor(documentId: String, pageId: String, private val actions: EditorActions, renderer: RenderCoordinator, private val scope: CoroutineScope, converter: RenderedPreviewBitmaps? = null) : AutoCloseable {
    private val document = DocumentId(documentId)
    private val selected = PageId(pageId)
    private val current = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = current.asStateFlow()
    private val scheduler = PreviewScheduler(renderer, scope, converter)
    val preview: StateFlow<PreviewState> = scheduler.state
    private var history: RecipeHistory? = null
    private var loading: Job? = null
    private var saving: Job? = null
    private var generation = 0L
    private var active = true
    private var closed = false

    init { retryLoad() }

    fun retryLoad() {
        if (closed || current.value.page != null || loading?.isActive == true) return
        current.value = current.value.copy(loading = true, error = null)
        loading = scope.launch {
            try {
                val page = actions.load(document, selected)
                currentCoroutineContext().ensureActive()
                if (closed) return@launch
                if (page.documentId != document || page.pageId != selected || RecipeCodec.hash(page.recipe) != page.recipeHash) {
                    rejectLoad(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "editor_page_identity_mismatch", selected.value)); return@launch
                }
                history = RecipeHistory(page.recipe)
                current.value = EditorState(page = page, draft = page.recipe, loading = false)
                renderDraft()
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: RepositoryFailure) { rejectLoad(failure.error) }
            catch (_: Exception) { rejectLoad(OperationError(OperationErrorCode.STORAGE_FAILED, true, "editor_load_failed", selected.value)) }
        }
    }

    private fun rejectLoad(error: OperationError) { if (!closed) current.value = current.value.copy(loading = false, error = error) }
    private fun editable(): RecipeHistory? = if (closed || current.value.loading || current.value.saving || current.value.exitReady) null else history

    fun edit(recipe: Recipe) {
        val edits = editable() ?: return
        if (!edits.replace(recipe)) {
            // Choosing an already-selected recipe while looking at a reference still returns to its Result, without inventing another history entry or a dirty draft.
            if (current.value.view != EditorView.RESULT) { current.value = current.value.copy(view = EditorView.RESULT); renderDraft() }
            return
        }
        updateDraft(); renderDraft()
    }

    fun showView(id: String) {
        if (editable() == null) return
        val view = EditorViews.parse(id)
        if (current.value.view == view) return
        current.value = current.value.copy(view = view)
        renderDraft()
    }

    fun rotateClockwise() { editable()?.current?.let { edit(it.copy(geometry = it.geometry.withOutputQuarterTurns((it.geometry.outputQuarterTurns + 1) % 4))) } }
    fun chooseLook(id: String) { editable()?.current?.let { edit(EditorLooks.select(it, id)) } }
    fun undo() { val edits = editable() ?: return; if (!edits.canUndo) return; edits.undo(); updateDraft(); renderDraft() }
    fun redo() { val edits = editable() ?: return; if (!edits.canRedo) return; edits.redo(); updateDraft(); renderDraft() }
    fun discard() { val edits = editable() ?: return; edits.discard(); updateDraft(); renderDraft() }

    private fun updateDraft() {
        val edits = checkNotNull(history)
        current.value = current.value.copy(draft = edits.current, dirty = edits.dirty, canUndo = edits.canUndo, canRedo = edits.canRedo, error = null, confirmExit = false, view = EditorView.RESULT)
    }

    private fun renderDraft() {
        if (closed || !active) return
        val page = current.value.page ?: return
        val draft = current.value.draft ?: return
        val recipe = EditorViews.recipe(draft, current.value.view)
        try {
            generation = Math.addExact(generation, 1)
            val output = GeometrySizing.output(recipe.geometry, RenderPurpose.PREVIEW, 1024)
            scheduler.schedule(RenderRequest(page.sourceId.value, recipe.geometry.source.sourcePixelHash, page.pageId.value, page.recipeRevision, recipe, output, generation, null, FallbackPolicy.FAIL))
        } catch (_: IllegalArgumentException) {
            scheduler.clear()
            current.value = current.value.copy(error = OperationError(OperationErrorCode.INVALID_INPUT, false, "editor_geometry_invalid", selected.value))
        } catch (_: ArithmeticException) {
            scheduler.clear()
            current.value = current.value.copy(error = OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "editor_generation_limit", selected.value))
        }
    }

    fun retryPreview() { if (editable() != null) renderDraft() }
    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        if (active) renderDraft() else scheduler.clear()
    }

    fun apply() = save(andBack = false)
    fun applyAndBack() = save(andBack = true)
    private fun save(andBack: Boolean) {
        val edits = editable() ?: return
        if (!edits.dirty) { if (andBack) exit(); return }
        val page = checkNotNull(current.value.page)
        val draft = edits.current
        current.value = current.value.copy(saving = true, error = null, confirmExit = false)
        saving = scope.launch {
            try {
                val result = actions.save(page.pageId, page.recipeRevision, draft)
                currentCoroutineContext().ensureActive()
                if (closed) return@launch
                when (result) {
                    is SaveRecipeResult.Saved -> {
                        if (result.pageId != page.pageId || result.recipeRevision <= page.recipeRevision || result.documentRevision <= page.documentRevision || result.recipeHash != RecipeCodec.hash(draft)) {
                            saveFailed(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "editor_save_identity_mismatch", selected.value)); return@launch
                        }
                        edits.accept()
                        current.value = current.value.copy(page = page.copy(documentRevision = result.documentRevision, recipeRevision = result.recipeRevision, recipeHash = result.recipeHash, recipe = draft), saving = false)
                        updateDraft()
                        if (andBack) exit() else renderDraft()
                    }
                    is SaveRecipeResult.Conflict -> saveFailed(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "editor_revision_conflict", selected.value))
                    is SaveRecipeResult.Rejected -> saveFailed(result.error)
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (failure: RepositoryFailure) { saveFailed(failure.error) }
            catch (_: Exception) { saveFailed(OperationError(OperationErrorCode.STORAGE_FAILED, true, "editor_save_failed", selected.value)) }
        }
    }

    private fun saveFailed(error: OperationError) { if (!closed) current.value = current.value.copy(saving = false, error = error) }
    fun requestBack() {
        if (closed || current.value.saving) return
        if (current.value.dirty) current.value = current.value.copy(confirmExit = true) else exit()
    }
    fun keepEditing() { if (!closed) current.value = current.value.copy(confirmExit = false) }
    fun discardAndBack() { val edits = editable() ?: return; edits.discard(); updateDraft(); exit() }
    private fun exit() { current.value = current.value.copy(exitReady = true, confirmExit = false); close() }

    override fun close() {
        if (closed) return
        closed = true
        loading?.cancel(); saving?.cancel()
        scheduler.close()
    }
}
