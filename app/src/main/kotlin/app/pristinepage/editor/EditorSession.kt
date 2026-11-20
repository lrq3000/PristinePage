package app.pristinepage.editor

import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.geometry.QuadValidation
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
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
    private var shapeDrag: ShapeDrag? = null
    private var frameDrag: ShapePoint? = null
    private var bezierDrag: BezierDrag? = null
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
        endShapeDrag()
        endFrameDrag()
        endBezierDrag()
        replaceDraft(edits, recipe)
    }

    private fun replaceDraft(edits: RecipeHistory, recipe: Recipe) {
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
        endShapeDrag()
        endFrameDrag()
        endBezierDrag()
        current.value = current.value.copy(view = view)
        renderDraft()
    }

    fun rotateClockwise() = rotate(1)
    fun rotateCounterclockwise() = rotate(-1)
    private fun rotate(quarters: Int) {
        val draft = editable()?.current ?: return
        edit(draft.copy(geometry = draft.geometry.withOutputQuarterTurns(Math.floorMod(draft.geometry.outputQuarterTurns + quarters, 4))))
    }
    fun chooseLook(id: String) { editable()?.current?.let { edit(EditorLooks.select(it, id)) } }
    internal fun resetShape() = editShape(EditorShapes::reset)
    internal fun selectPerspective() = editShape(EditorShapes::perspective)
    internal fun selectManualCurve() = editShape(EditorShapes::manualCurve)
    internal fun selectBezier() = editShape(EditorShapes::bezier)
    internal class BezierDrag(val control: BezierControl)
    internal fun beginBezierDrag(control: BezierControl): BezierDrag? {
        val edits = editable() ?: return null
        val contour = (edits.current.geometry as? GeometryRecipe.Bezier)?.contour ?: return null
        if (!active || current.value.confirmExit || control.read(contour) == null) return null
        endShapeDrag()
        endFrameDrag()
        endBezierDrag()
        edits.beginGesture()
        return BezierDrag(control).also { bezierDrag = it }
    }

    internal fun endBezierDrag(drag: BezierDrag? = bezierDrag, cancel: Boolean = false) {
        if (drag == null || drag !== bezierDrag) return
        bezierDrag = null
        val edits = checkNotNull(history)
        if (!cancel) edits.endGesture()
        else if (edits.cancelGesture()) { updateDraft(); renderDraft() }
    }

    internal fun moveBezier(control: BezierControl, point: PointPx, drag: BezierDrag? = null) {
        if (drag != null && (drag !== bezierDrag || control != drag.control)) return
        if (drag == null) endBezierDrag()
        changeBezier { if (control.read(it) != null) control.move(it, point) else it }
    }

    internal fun insertBezier(side: app.pristinepage.core.recipe.ContourSide, segment: Int, t: Double = .5) {
        endBezierDrag()
        changeBezier { it.insert(side, segment, t) }
    }

    internal fun removeBezier(side: app.pristinepage.core.recipe.ContourSide, index: Int) {
        endBezierDrag()
        changeBezier { it.removeNode(side, index) }
    }

    private fun changeBezier(change: (app.pristinepage.core.recipe.BezierContour) -> app.pristinepage.core.recipe.BezierContour) {
        val edits = editable() ?: return
        if (!active || current.value.confirmExit) return
        val draft = edits.current
        val geometry = draft.geometry as? GeometryRecipe.Bezier ?: return
        val next = try { change(geometry.contour) } catch (_: IllegalArgumentException) { return }
        catch (_: IndexOutOfBoundsException) { return }
        if (next.quad != geometry.contour.quad && !validFrame(EditorShapes.perspective(geometry.copy(contour = next)) as GeometryRecipe.Perspective)) return
        replaceDraft(edits, draft.copy(geometry = geometry.copy(contour = next)))
    }
    internal class ShapeDrag(val label: ShapePoint, val horizontal: Boolean)

    fun dragFrameCorner(id: String, x: Double, y: Double) {
        val point = ShapePoint.entries.firstOrNull { it.name == id } ?: return
        if (frameDrag != null && frameDrag != point) return
        if (!active || current.value.confirmExit || !x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return
        val edits = editable() ?: return
        val draft = edits.current
        val geometry = draft.geometry
        if (point !in EditorShapes.points(geometry)) return
        val candidate = EditorShapes.setPoint(geometry, point, PointPx(x, y))
        val frame = EditorShapes.perspective(candidate) as? GeometryRecipe.Perspective ?: return
        if (!validFrame(frame)) return
        endShapeDrag()
        replaceDraft(edits, draft.copy(geometry = candidate))
    }

    internal fun beginFrameDrag(point: ShapePoint): Boolean {
        val edits = editable() ?: return false
        if (!active || current.value.confirmExit || point !in EditorShapes.points(edits.current.geometry)) return false
        endShapeDrag()
        endFrameDrag()
        endBezierDrag()
        edits.beginGesture()
        frameDrag = point
        return true
    }

    internal fun endFrameDrag(cancel: Boolean = false) {
        if (frameDrag == null) return
        frameDrag = null
        val edits = checkNotNull(history)
        if (!cancel) edits.endGesture()
        else if (edits.cancelGesture()) { updateDraft(); renderDraft() }
    }

    internal fun useDetectedFrame(expected: GeometryRecipe, quad: QuadPx): Boolean {
        val edits = editable() ?: return false
        val original = edits.current.geometry
        if (!active || current.value.confirmExit || original != expected || frameDrag != null || shapeDrag != null || bezierDrag != null) return false
        if (original !is GeometryRecipe.None && original !is GeometryRecipe.Perspective) return false
        val geometry = EditorShapes.perspective(original) as? GeometryRecipe.Perspective ?: return false
        val candidate = geometry.copy(quad = quad)
        if (!validFrame(candidate)) return false
        edit(edits.current.copy(geometry = candidate))
        return true
    }

    private fun validFrame(geometry: GeometryRecipe.Perspective): Boolean {
        val size = geometry.source.sourceSize
        fun expand(p: PointPx) = PointPx(p.x * (size.width - 1), p.y * (size.height - 1))
        val q = geometry.quad
        return try {
            QuadValidation.validate(QuadPx(expand(q.tl), expand(q.tr), expand(q.br), expand(q.bl)), size)
            true
        } catch (_: IllegalArgumentException) { false }
    }

    internal fun beginShapeDrag(label: ShapePoint, horizontal: Boolean): ShapeDrag? {
        val edits = editable() ?: return null
        if (!active || current.value.confirmExit || label !in EditorShapes.points(edits.current.geometry)) return null
        endShapeDrag()
        endFrameDrag()
        edits.beginGesture()
        return ShapeDrag(label, horizontal).also { shapeDrag = it }
    }

    internal fun endShapeDrag(drag: ShapeDrag? = shapeDrag) {
        if (drag == null || drag !== shapeDrag) return
        shapeDrag = null
        val edits = checkNotNull(history)
        edits.endGesture()
    }

    internal fun setShapeCoordinate(label: ShapePoint, horizontal: Boolean, value: Double, drag: ShapeDrag? = null) {
        val edits = editable() ?: return
        if (!active || current.value.confirmExit) return
        if (drag == null) endShapeDrag()
        else if (drag !== shapeDrag || drag.label != label || drag.horizontal != horizontal) return
        // Read the other axis from the current draft rather than a potentially not-yet-recomposed slider lambda.
        val draft = edits.current
        val point = EditorShapes.points(draft.geometry)[label] ?: return
        val moved = if (horizontal) point.copy(x = value) else point.copy(y = value)
        replaceDraft(edits, draft.copy(geometry = EditorShapes.setPoint(draft.geometry, label, moved)))
    }

    private fun editShape(change: (GeometryRecipe) -> GeometryRecipe) {
        val draft = editable()?.current ?: return
        edit(draft.copy(geometry = change(draft.geometry)))
    }

    fun undo() { val edits = editable() ?: return; endShapeDrag(); endFrameDrag(); endBezierDrag(); if (!edits.canUndo) return; edits.undo(); updateDraft(); renderDraft() }
    fun redo() { val edits = editable() ?: return; endShapeDrag(); endFrameDrag(); endBezierDrag(); if (!edits.canRedo) return; edits.redo(); updateDraft(); renderDraft() }
    fun discard() { val edits = editable() ?: return; endShapeDrag(); endFrameDrag(); endBezierDrag(); edits.discard(); updateDraft(); renderDraft() }

    private fun updateDraft() {
        val edits = checkNotNull(history)
        current.value = current.value.copy(draft = edits.current, dirty = edits.dirty, canUndo = edits.canUndo, canRedo = edits.canRedo, error = null, confirmExit = false, view = EditorView.RESULT)
    }

    private fun renderDraft() {
        if (closed || !active) return
        val page = current.value.page ?: return
        val draft = current.value.draft ?: return
        val view = current.value.view
        val recipe = EditorViews.recipe(draft, view)
        try {
            generation = Math.addExact(generation, 1)
            val output = GeometrySizing.output(recipe.geometry, RenderPurpose.PREVIEW, 1024)
            scheduler.schedule(RenderRequest(page.sourceId.value, recipe.geometry.source.sourcePixelHash, page.pageId.value, page.recipeRevision, recipe, output, generation, null, FallbackPolicy.FAIL), view,
                interactive = frameDrag != null || bezierDrag != null || shapeDrag != null)
        } catch (_: IllegalArgumentException) {
            scheduler.rejectSizing(page.pageId.value, page.sourceId.value, recipe.geometry.source, RenderStatus.INVALID_INPUT)
        } catch (_: ArithmeticException) {
            scheduler.rejectSizing(page.pageId.value, page.sourceId.value, recipe.geometry.source, RenderStatus.RESOURCE_LIMIT)
        }
    }

    fun retryPreview() { if (editable() != null) renderDraft() }
    fun setActive(value: Boolean) {
        if (closed || active == value) return
        active = value
        if (active) renderDraft() else { endShapeDrag(); endFrameDrag(cancel = true); endBezierDrag(cancel = true); scheduler.clear() }
    }

    fun apply() = save(andBack = false)
    fun applyAndBack() = save(andBack = true)

    /** A wizard move can consume only the exact draft accepted by the ordinary save path, never a pending or failed write. */
    internal suspend fun checkpointForNavigation(): Boolean {
        val edits = editable() ?: return false
        if (!edits.dirty) return true
        val expected = edits.current
        if (current.value.shapeBlocksApply(preview.value)) return false
        save(andBack = false)
        saving?.join()
        val state = current.value
        return !closed && !state.saving && !state.dirty && state.error == null && state.page?.recipe == expected
    }

    private fun save(andBack: Boolean) {
        val edits = editable() ?: return
        if (!edits.dirty) { if (andBack) exit(); return }
        // Both Apply paths pass this guard before enqueueing the repository CAS, not just when the buttons recompose.
        if (current.value.shapeBlocksApply(preview.value)) return
        endShapeDrag()
        endFrameDrag()
        endBezierDrag()
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
        endShapeDrag()
        endFrameDrag(cancel = true)
        endBezierDrag(cancel = true)
        if (current.value.dirty) current.value = current.value.copy(confirmExit = true) else exit()
    }
    fun keepEditing() { if (!closed) current.value = current.value.copy(confirmExit = false) }
    fun discardAndBack() { val edits = editable() ?: return; edits.discard(); updateDraft(); exit() }
    private fun exit() { endShapeDrag(); endFrameDrag(cancel = true); endBezierDrag(cancel = true); current.value = current.value.copy(exitReady = true, confirmExit = false); close() }

    override fun close() {
        if (closed) return
        closed = true
        endShapeDrag()
        endFrameDrag(cancel = true)
        endBezierDrag(cancel = true)
        loading?.cancel(); saving?.cancel()
        scheduler.close()
    }
}
