package app.pristinepage.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pristinepage.R
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.design.AtelierStyle
import app.pristinepage.library.errorText
import app.pristinepage.ocr.SavedPageOcrDetails
import app.pristinepage.ocr.SavedPageOcrPanel
import app.pristinepage.ocr.SavedPageOcrReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** A view of one retained session, not a second processing/store owner. The viewport is finite even inside a scroll container and never derives an unbounded height from a narrow source aspect. */
class PageEditorScreen(
    private val originalOnLoadFailure: (@Composable () -> Unit)? = null,
    private val loadOriginal: (suspend (String) -> OriginalPreviewBitmap)? = null,
    private val guidedStep: Int? = null,
    private val guidedBusy: Boolean = false,
    private val pageNumber: Int? = null,
    private val albumPageCount: Int? = null,
    private val previousPage: (() -> Unit)? = null,
    private val nextPage: (() -> Unit)? = null,
    private val savedOcr: SavedPageOcrReader? = null,
    private val showOcrDiagnostics: Boolean = false,
    private val allowSave: Boolean = true,
) : EditorContent {
    @Composable override fun Content(session: EditorSession, onExit: () -> Unit) {
        val state by session.state.collectAsStateWithLifecycle()
        val preview by session.preview.collectAsStateWithLifecycle()
        var chosenPanel by rememberSaveable { mutableStateOf("") }
        var applyRevision by rememberSaveable { mutableStateOf<Long?>(null) }
        val panel = when (guidedStep) { 0 -> "shape"; 1 -> "rotate"; 2 -> "looks"; else -> chosenPanel }
        var information by rememberSaveable { mutableStateOf(false) }
        var ocrPanel by remember(session, state.page?.recipeRevision) { mutableStateOf<SavedPageOcrPanel?>(null) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val exit by rememberUpdatedState(onExit)
        DisposableEffect(session, lifecycle) {
            fun synchronize() { session.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
            val observer = LifecycleEventObserver { _, _ -> synchronize() }
            lifecycle.addObserver(observer)
            synchronize()
            onDispose { lifecycle.removeObserver(observer); session.setActive(false) }
        }
        LaunchedEffect(session, state.exitReady) { if (state.exitReady) exit() }
        LaunchedEffect(session, applyRevision, state.page?.recipeRevision, state.error, state.saving, state.dirty) {
            val submitted = applyRevision ?: return@LaunchedEffect
            if (state.saving) return@LaunchedEffect
            if (state.error != null) applyRevision = null
            else if (!state.dirty && (state.page?.recipeRevision ?: 0L) > submitted) {
                chosenPanel = ""
                applyRevision = null
            }
        }
        BackHandler(enabled = guidedStep == null && !state.exitReady) { session.requestBack() }
        val editable = state.page != null && !state.loading && !state.saving && !state.exitReady && !guidedBusy
        var seedAttempted by rememberSaveable(state.page?.pageId?.value) { mutableStateOf(false) }
        var seedFeedback by rememberSaveable(state.page?.pageId?.value) { mutableStateOf<Int?>(null) }
        var bezierSelection by remember(session) { mutableStateOf<BezierControl?>(null) }
        var placeBezierPoint by remember(session, panel, state.draft?.geometry is GeometryRecipe.Bezier) { mutableStateOf(false) }
        val mayBrowse = editable && !state.dirty && !state.confirmExit && panel.isEmpty() && !information && ocrPanel == null
        val swipePrevious by rememberUpdatedState(previousPage)
        val swipeNext by rememberUpdatedState(nextPage)
        val shapeBlocksApply = state.shapeBlocksApply(preview)
        val expected = state.draft?.let { EditorViews.recipe(it,state.view) }
        val showingCurrent = !preview.previous && preview.view == state.view && preview.display?.request == preview.request && preview.display?.request?.recipe == expected
        val viewLabel = when (state.view) {
            EditorView.RESULT -> R.string.editor_view_result
            EditorView.GEOMETRY_INPUT -> R.string.editor_view_input
            EditorView.ORIGINAL -> R.string.editor_view_original
        }
        val selectedLook = state.draft?.matchingLook()
        var correctionZoom by remember(session, state.page?.pageId, panel) { mutableStateOf(CorrectionPreviewZoom()) }
        val displayPreview: @Composable (Modifier) -> Unit = { height ->
            val requestedLabel = if (state.view == EditorView.RESULT && selectedLook != null) {
                stringResource(R.string.editor_requested_look, stringResource(selectedLook.label))
            } else {
                stringResource(R.string.editor_requested_view, stringResource(viewLabel))
            }
            Text(requestedLabel)
            val galleryGesture = if (mayBrowse && (previousPage != null || nextPage != null)) Modifier.pointerInput(session) {
                var distance = 0f
                detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                    if (!session.state.value.dirty && !session.state.value.saving) {
                        if (distance >= 56.dp.toPx()) swipePrevious?.invoke()
                        if (distance <= -56.dp.toPx()) swipeNext?.invoke()
                    }
                }) { change, delta -> change.consume(); distance += delta }
            } else Modifier
            Box(Modifier.fillMaxWidth().then(height)
                .clip(RoundedCornerShape(AtelierStyle.surfaceRadius)).background(AtelierStyle.canvas)
                .then(galleryGesture).testTag("editor-viewport"),
                contentAlignment = Alignment.Center) {
                // A BoxWithConstraints subcomposition can run with its previous collected snapshot after the owning
                // lifecycle has retired it. Do not dereference a retired bitmap lease; the next emission redraws it.
                preview.display?.takeIf { session.preview.value.display === it }?.let { display ->
                    val description = if (!showingCurrent) R.string.editor_previous_description else when (state.view) {
                        EditorView.RESULT -> R.string.editor_preview_description
                        EditorView.GEOMETRY_INPUT -> R.string.editor_input_description
                        EditorView.ORIGINAL -> R.string.editor_original_description
                    }
                    val bitmap = display.bitmap.asImageBitmap()
                    val zooming = panel == "looks" && editable
                    Box(Modifier.fillMaxSize().padding(6.dp).clipToBounds()) {
                        var viewport by remember { mutableStateOf(IntSize.Zero) }
                        fun updateZoom(factor: Float, dx: Float, dy: Float) {
                            correctionZoom = correctionZoom.transform(factor, dx, dy, viewport.width, viewport.height, bitmap.width, bitmap.height)
                        }
                        val gestures = if (zooming) {
                            val transform = rememberTransformableState { zoom, pan, _ -> updateZoom(zoom, pan.x, pan.y) }
                            Modifier.transformable(transform, canPan = { correctionZoom.scale > 1f })
                        } else Modifier
                        val shown = if (zooming) correctionZoom.transform(1f, 0f, 0f, viewport.width, viewport.height, bitmap.width, bitmap.height)
                        else CorrectionPreviewZoom()
                        Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }.then(gestures)) {
                            Image(bitmap, stringResource(description), modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = shown.scale
                                scaleY = shown.scale
                                translationX = shown.x
                                translationY = shown.y
                            }.testTag("editor-preview"), contentScale = ContentScale.Fit)
                        }
                        if (zooming) Row(Modifier.align(Alignment.TopEnd)) {
                            TextButton(onClick = { updateZoom(1.5f, 0f, 0f) }, enabled = shown.scale < CorrectionPreviewZoom.MAX_SCALE,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("editor-preview-zoom-in")) { Text(stringResource(R.string.editor_zoom_in)) }
                            if (shown.scale > 1f) TextButton(onClick = { correctionZoom = CorrectionPreviewZoom() },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("editor-preview-zoom-fit")) { Text(stringResource(R.string.editor_zoom_fit)) }
                        }
                    }
                }
                if (preview.processing) {
                    Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .34f)))
                    CircularProgressIndicator(Modifier.testTag("editor-processing"))
                }
                if (preview.display != null && !showingCurrent) Surface(Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.editor_previous), Modifier.padding(6.dp).testTag("editor-previous"))
                }
                if (shapeBlocksApply && preview.failure == null) Surface(Modifier.align(Alignment.BottomStart).padding(8.dp),
                    shape = MaterialTheme.shapes.small) {
                    Box(Modifier.padding(6.dp)) { ShapeSaveNotice(state, preview) }
                }
                preview.failure?.let { status ->
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(when (status) {
                                RenderStatus.RESOURCE_LIMIT -> R.string.error_resource_limit
                                RenderStatus.STORAGE_FAILED -> R.string.error_storage_failed
                                RenderStatus.UNAVAILABLE -> R.string.error_provider_unavailable
                                RenderStatus.INVALID_INPUT -> if (preview.request == null) R.string.editor_shape_invalid_input else R.string.error_invalid_input
                                RenderStatus.INVALID_MAP -> R.string.editor_shape_invalid_map
                                RenderStatus.CANCELED -> R.string.error_canceled
                                else -> R.string.error_processing
                            }), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = session::retryPreview, enabled = editable, modifier = control("editor-retry-preview")) {
                                Text(stringResource(R.string.editor_retry_preview))
                            }
                        }
                    }
                }
            }
        }
        val history: @Composable () -> Unit = {
            if (state.page != null && (panel == "shape" || state.canUndo || state.canRedo || state.dirty)) {
                Row(Modifier.fillMaxWidth().testTag("editor-history"),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    HistoryButton("editor-undo", R.drawable.ic_carnet_undo, R.string.editor_undo, editable && state.canUndo, session::undo)
                    HistoryButton("editor-redo", R.drawable.ic_carnet_redo, R.string.editor_redo, editable && state.canRedo, session::redo)
                    HistoryButton("editor-discard", R.drawable.ic_carnet_discard, R.string.editor_discard, editable && state.dirty, session::discard)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val pinCropHistory = panel == "shape" && maxHeight >= 440.dp
        val cropPreview = (maxHeight * .25f).coerceIn(88.dp, 220.dp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (guidedStep == null) TextButton(onClick = session::requestBack, enabled = !state.saving,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("editor-back")) { Text(stringResource(R.string.atelier_back_to_album)) }
                Text(stringResource(R.string.editor_heading), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    if (state.dirty) {
                        if (!allowSave || state.saveNeedsReopen) return@Button
                        if (guidedStep == null) applyRevision = state.page?.recipeRevision
                        session.apply()
                    } else chosenPanel = ""
                }, enabled = editable && !shapeBlocksApply && (if (state.dirty) allowSave && !state.saveNeedsReopen else guidedStep == null && panel.isNotEmpty()),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("editor-apply")) { Text(stringResource(R.string.editor_apply)) }
            }
            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
            if (pinCropHistory) {
                // Pin the result and its history row, leaving the source canvas and advanced controls independently scrollable.
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    displayPreview(Modifier.height(cropPreview))
                    history()
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loading) CircularProgressIndicator()
            if (!allowSave) Text(stringResource(R.string.editor_selected_preview_only), style = MaterialTheme.typography.bodySmall)
            state.error?.let {
                Text(if (it.safeMessageKey == "editor_save_delivery_unconfirmed") stringResource(R.string.editor_save_delivery_unconfirmed)
                    else errorText(it.code), color = MaterialTheme.colorScheme.error)
                if (state.page == null) Button(onClick = session::retryLoad, modifier = control("editor-retry-load")) { Text(stringResource(R.string.editor_retry_load)) }
            }
            // When the catalog admits the page, an independent original read need not execute its accepted recipe.
            if (state.page == null && state.error != null) originalOnLoadFailure?.invoke()
            if (!pinCropHistory) {
                val height = if (panel == "shape") cropPreview else (screenHeight * .51f).coerceIn(220f, 440f).dp
                displayPreview(Modifier.height(height))
                history()
            }
            if (panel == "shape") state.draft?.let {
                seedFeedback?.let { label -> Text(stringResource(label)) }
                val page = state.page
                val frame = it.geometry
                val seed = guidedStep == 0 && !seedAttempted && page?.recipeRevision == 1L && frame is GeometryRecipe.None
                if (page != null && loadOriginal != null &&
                    (frame is GeometryRecipe.Perspective || frame is GeometryRecipe.ManualCurve || frame is GeometryRecipe.ManualCurveFourSides ||
                        frame is GeometryRecipe.Bezier || seed)) {
                    AtelierFrameCanvas(page.sourceId.value, frame, editable, loadOriginal, session, bezierSelection,
                        onSelectBezier = { bezierSelection = it },
                        placeBezierPoint = placeBezierPoint,
                        onPointPlaced = { placeBezierPoint = false },
                        curveRequest = preview.request?.takeIf {
                            preview.view == EditorView.RESULT && !preview.processing && !preview.previous &&
                                preview.failure == null && preview.preview?.request == it && it.recipe == state.draft
                        },
                        onSourceReady = if (!seed || !editable) null else { bitmap ->
                            val analysis = session.beginFrameAnalysis(frame, currentCoroutineContext()[Job])
                            if (analysis != null) {
                                try {
                                    val quad = session.reserveFrameAnalysis(bitmap.allocationByteCount).use { detectAtelierFrame(bitmap) }
                                    if (session.state.value.draft?.geometry == frame) {
                                        seedFeedback = if (quad != null && session.useDetectedFrame(analysis, quad)) null else R.string.atelier_frame_no_edges
                                        // Switching the seed branch off before suspension completes would dispose its own canvas.
                                        seedAttempted = true
                                    }
                                } catch (canceled: CancellationException) { throw canceled }
                                catch (failure: RepositoryFailure) {
                                    seedFeedback = if (failure.error.code == OperationErrorCode.RESOURCE_LIMIT)
                                        R.string.atelier_frame_resources else R.string.atelier_frame_no_edges
                                    seedAttempted = true
                                }
                                catch (_: Exception) { seedFeedback = R.string.atelier_frame_no_edges; seedAttempted = true }
                                finally { session.endFrameAnalysis(analysis) }
                            }
                        })
                    if (frame is GeometryRecipe.Bezier) {
                        Text(stringResource(R.string.carnet_bezier_touch_help))
                        FilterChip(
                            selected = placeBezierPoint, onClick = { placeBezierPoint = !placeBezierPoint }, enabled = editable,
                            label = { Text(stringResource(if (placeBezierPoint) R.string.carnet_bezier_choose_spot else R.string.carnet_bezier_place_point)) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("carnet-bezier-place"),
                        )
                        AtelierBezierControls(frame, editable, session, bezierSelection) { bezierSelection = it }
                    }
                    if (frame is GeometryRecipe.ManualCurve || frame is GeometryRecipe.ManualCurveFourSides) {
                        Text(stringResource(R.string.carnet_manual_touch_help))
                        if (frame is GeometryRecipe.ManualCurve && frame.depth == 0.0 &&
                            frame != GeometryRecipe.ManualCurve(frame.source, frame.quad, targetRule = frame.targetRule, outputQuarterTurns = frame.outputQuarterTurns)) {
                            Text(stringResource(R.string.carnet_manual_side_upgrade), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                EditorShapeControls(frame, editable, session)
            }
            if (state.dirty) Text(stringResource(R.string.editor_unsaved), modifier = Modifier.testTag("editor-unsaved"))
            if (state.saving) Text(stringResource(R.string.editor_saving))
            if (panel.isEmpty() && pageNumber != null && albumPageCount != null && albumPageCount > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { previousPage?.invoke() }, enabled = mayBrowse && previousPage != null,
                        modifier = Modifier.size(48.dp).testTag("carnet-previous-page")) {
                        Icon(painterResource(R.drawable.ic_atelier_back), stringResource(R.string.carnet_previous_page))
                    }
                    Text(stringResource(R.string.carnet_album_position, pageNumber, albumPageCount))
                    IconButton(onClick = { nextPage?.invoke() }, enabled = mayBrowse && nextPage != null,
                        modifier = Modifier.size(48.dp).testTag("carnet-next-page")) {
                        Icon(painterResource(R.drawable.ic_atelier_next), stringResource(R.string.carnet_next_page))
                    }
                }
            }
            if (panel == "looks") {
                Text(stringResource(R.string.editor_looks_sample_notice), style = MaterialTheme.typography.bodySmall)
                EditorLookCarousel(selectedLook, editable, session::chooseLook)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("result" to R.string.editor_view_result, "input" to R.string.editor_view_input, "original" to R.string.editor_view_original).forEach { (id, label) ->
                        FilterChip(
                            selected = state.view == EditorViews.parse(id), onClick = { session.showView(id) },
                            enabled = editable, label = { Text(stringResource(label)) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("editor-view-$id"),
                        )
                    }
                }
                Text(stringResource(R.string.editor_preset_notice), style = MaterialTheme.typography.bodySmall)
            }
            if (panel == "rotate") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedIconButton(onClick = session::rotateCounterclockwise, enabled = editable, modifier = Modifier.size(48.dp).testTag("editor-rotate-left")) {
                        Icon(painterResource(R.drawable.ic_carnet_rotate_left), stringResource(R.string.atelier_left))
                    }
                    state.draft?.let {
                        val degrees = it.geometry.outputQuarterTurns * 90
                        val description = stringResource(R.string.editor_turns, degrees)
                        Text(stringResource(R.string.carnet_rotation_angle, degrees), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = description })
                    }
                    OutlinedIconButton(onClick = session::rotateClockwise, enabled = editable, modifier = Modifier.size(48.dp).testTag("editor-rotate")) {
                        Icon(painterResource(R.drawable.ic_carnet_rotate_right), stringResource(R.string.atelier_right))
                    }
                }
            }
            }
            if (guidedStep == null) {
            HorizontalDivider(color = AtelierStyle.divider)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                RibbonTool("shape", "⌗", R.string.editor_shape, panel == "shape", editable) {
                    chosenPanel = if (panel == "shape") "" else "shape"
                    if (chosenPanel == "shape") session.showView("result")
                }
                RibbonTool("rotate", "↻", R.string.atelier_rotation, panel == "rotate", editable) { chosenPanel = if (panel == "rotate") "" else "rotate" }
                RibbonTool("looks", "✦", R.string.editor_looks, panel == "looks", editable) { chosenPanel = if (panel == "looks") "" else "looks" }
                RibbonTool("original", "◉", R.string.atelier_original, state.view == EditorView.ORIGINAL, editable) {
                    session.showView(if (state.view == EditorView.ORIGINAL) "result" else "original")
                }
                RibbonTool("info", "ⓘ", R.string.atelier_info, information, editable) { information = true }
                if (savedOcr != null) {
                    // A saved editor can still explain unavailability when the recipe cannot be opened.
                    RibbonTool("processing-details", "ⓘ", R.string.ocr_processing_details, ocrPanel == SavedPageOcrPanel.PROCESSING, !state.saving && !state.exitReady) {
                        ocrPanel = SavedPageOcrPanel.PROCESSING
                    }
                    RibbonTool("ocr-text", "≡", R.string.ocr_text, ocrPanel == SavedPageOcrPanel.TEXT, editable) {
                        ocrPanel = SavedPageOcrPanel.TEXT
                    }
                }
            }
            }
        }
        }
        if (guidedStep == null && savedOcr != null) {
            val shown = ocrPanel
            if (shown != null) SavedPageOcrDetails(shown, state.page, savedOcr, showOcrDiagnostics) { ocrPanel = null }
        }
        if (information) AlertDialog(onDismissRequest = { information = false },
            title = { Text(stringResource(R.string.atelier_info)) },
            text = {
                Column {
                    pageNumber?.let { Text(stringResource(R.string.page_number, it)) }
                    val dimensions = state.draft?.geometry?.source?.sourceSize
                    if (dimensions != null) Text(stringResource(R.string.atelier_source_size, dimensions.width, dimensions.height))
                    state.draft?.let { Text(stringResource(R.string.editor_turns, it.geometry.outputQuarterTurns * 90)) }
                    Text(stringResource(R.string.editor_requested_view, stringResource(viewLabel)))
                    when {
                        showingCurrent -> {
                            Text(stringResource(R.string.atelier_info_ready))
                            preview.preview?.let { render ->
                                val size = render.request.output.size
                                Text(stringResource(R.string.atelier_info_preview_size, size.width, size.height))
                                 render.stages.mapNotNull { stage ->
                                     if ("MANUAL_STRAIGHT_PERSPECTIVE" in stage.warningCodes) R.string.carnet_manual_straight_execution
                                     else executedLabel(stage.stageId)
                                 }.distinct().forEach { label ->
                                    Text(stringResource(R.string.atelier_info_executed, stringResource(label)))
                                }
                            }
                        }
                        preview.processing -> Text(stringResource(R.string.atelier_info_running))
                        else -> Text(stringResource(R.string.atelier_info_no_result))
                    }
                    Text(stringResource(R.string.editor_scope_notice))
                }
            },
            confirmButton = { TextButton(onClick = { information = false }) { Text(stringResource(R.string.atelier_close)) } })
        if (state.confirmExit) AlertDialog(
            modifier = Modifier.testTag("editor-exit-choice"),
            onDismissRequest = session::keepEditing,
            title = { Text(stringResource(R.string.editor_exit_title)) },
            text = { if (shapeBlocksApply) ShapeSaveNotice(state, preview) else Text(stringResource(R.string.editor_exit_notice)) },
            confirmButton = { if (allowSave && !state.saveNeedsReopen) TextButton(onClick = session::applyAndBack, enabled = editable && !shapeBlocksApply, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-apply-exit")) { Text(stringResource(R.string.editor_apply)) } },
            dismissButton = { Column {
                TextButton(onClick = session::keepEditing, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-keep-editing")) { Text(stringResource(R.string.editor_keep_editing)) }
                TextButton(onClick = session::discardAndBack, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-discard-exit")) { Text(stringResource(R.string.editor_discard_exit)) }
            } },
        )
    }

    private fun control(tag: String) = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
}

@Composable private fun HistoryButton(tag: String, icon: Int, label: Int, enabled: Boolean, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag)) {
        Icon(painterResource(icon), stringResource(label))
    }
}

private fun executedLabel(id: String): Int? = when (id) {
    "perspective_v1" -> R.string.atelier_wizard_frame
    "manual_ruled_v1" -> R.string.carnet_manual_legacy_execution
    "manual_four_sides_v1" -> R.string.carnet_manual_four_sides_execution
    "atelier_bezier_v1" -> R.string.atelier_bezier_mode
    "paper_gain_v1" -> R.string.editor_look_paper
    "natural_l_v1" -> R.string.editor_look_natural
    "document_color_v1" -> R.string.editor_look_document_color
    "paper_shadow_v1" -> R.string.editor_look_paper_shadow
    "grayscale_v1" -> R.string.editor_look_gray
    "sauvola_v1" -> R.string.editor_look_binary
    else -> null
}

@Composable private fun RibbonTool(id: String, glyph: String, label: Int, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.widthIn(min = 60.dp).heightIn(min = 56.dp).testTag("editor-tool-$id").semantics { selected = active },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun ShapeSaveNotice(state: EditorState, preview: PreviewState) {
    val message = when {
        state.view != EditorView.RESULT -> R.string.editor_shape_need_result
        preview.failure != null -> R.string.editor_shape_unverified
        else -> R.string.editor_shape_wait
    }
    Text(stringResource(message), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
