package app.pristinepage.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pristinepage.R
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.design.AtelierStyle
import app.pristinepage.library.errorText

/** A view of one retained session, not a second processing/store owner. The viewport is finite even inside a scroll container and never derives an unbounded height from a narrow source aspect. */
class PageEditorScreen(
    private val leaveAfterApply: Boolean = false,
    private val originalOnLoadFailure: (@Composable () -> Unit)? = null,
    private val loadOriginal: (suspend (String) -> OriginalPreviewBitmap)? = null,
    private val guidedStep: Int? = null,
    private val guidedBusy: Boolean = false,
    private val pageNumber: Int? = null,
) : EditorContent {
    @Composable override fun Content(session: EditorSession, onExit: () -> Unit) {
        val state by session.state.collectAsStateWithLifecycle()
        val preview by session.preview.collectAsStateWithLifecycle()
        var chosenPanel by rememberSaveable { mutableStateOf("") }
        val panel = when (guidedStep) { 0 -> "shape"; 1 -> "rotate"; 2 -> "looks"; else -> chosenPanel }
        var information by rememberSaveable { mutableStateOf(false) }
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
        BackHandler(enabled = guidedStep == null && !state.exitReady) { session.requestBack() }
        val editable = state.page != null && !state.loading && !state.saving && !state.exitReady && !guidedBusy
        var seedAttempted by rememberSaveable { mutableStateOf(false) }
        var seedFeedback by rememberSaveable { mutableStateOf(false) }
        val guidePage = state.page
        LaunchedEffect(session, guidedStep, guidePage?.pageId) {
            val current = guidePage ?: return@LaunchedEffect
            val loader = loadOriginal ?: return@LaunchedEffect
            if (guidedStep != 0 || seedAttempted || current.recipeRevision != 1L || current.recipe.geometry !is GeometryRecipe.None) return@LaunchedEffect
            val expectedGeometry = current.recipe.geometry
            try {
                val quad = loader(current.sourceId.value).use { detectAtelierFrame(it.bitmap) }
                if (session.state.value.draft?.geometry != expectedGeometry) return@LaunchedEffect
                if (quad != null) {
                    session.selectPerspective()
                    val frame = session.state.value.draft?.geometry
                    if (frame != null && !session.useDetectedFrame(frame, quad)) { session.discard(); seedFeedback = true }
                } else seedFeedback = true
                seedAttempted = true
            } catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
            catch (_: Exception) { seedAttempted = true; seedFeedback = true }
        }
        val shapeBlocksApply = state.shapeBlocksApply(preview)
        val expected = state.draft?.let { EditorViews.recipe(it,state.view) }
        val showingCurrent = !preview.previous && preview.view == state.view && preview.display?.request == preview.request && preview.display?.request?.recipe == expected
        val viewLabel = when (state.view) {
            EditorView.RESULT -> R.string.editor_view_result
            EditorView.GEOMETRY_INPUT -> R.string.editor_view_input
            EditorView.ORIGINAL -> R.string.editor_view_original
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (guidedStep == null) TextButton(onClick = session::requestBack, enabled = !state.saving,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("editor-back")) { Text(stringResource(R.string.atelier_back_to_album)) }
                Text(stringResource(R.string.editor_heading), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { if (leaveAfterApply) session.applyAndBack() else session.apply() },
                    enabled = editable && (!shapeBlocksApply && (state.dirty || leaveAfterApply)),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("editor-apply")) { Text(stringResource(R.string.editor_apply)) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loading) CircularProgressIndicator()
            state.error?.let {
                Text(errorText(it.code), color = MaterialTheme.colorScheme.error)
                if (state.page == null) Button(onClick = session::retryLoad, modifier = control("editor-retry-load")) { Text(stringResource(R.string.editor_retry_load)) }
            }
            // Loading the original is independent of recipe decoding. Unsupported recipes must not make an authentic page unreadable.
            if (state.page == null && state.error != null) originalOnLoadFailure?.invoke()
            if (state.dirty) Text(stringResource(R.string.editor_unsaved), modifier = Modifier.testTag("editor-unsaved"))
            if (state.saving) Text(stringResource(R.string.editor_saving))
            val selectedLook = state.draft?.matchingLook()
            val requestedLabel = if (state.view == EditorView.RESULT && selectedLook != null) {
                stringResource(R.string.editor_requested_look, stringResource(selectedLook.label))
            } else {
                stringResource(R.string.editor_requested_view, stringResource(viewLabel))
            }
            Text(requestedLabel)
            if (preview.display != null && !showingCurrent) Text(stringResource(R.string.editor_previous), modifier = Modifier.testTag("editor-previous"))
            Box(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 440.dp)
                .height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .51f).dp)
                .clip(RoundedCornerShape(AtelierStyle.surfaceRadius)).background(AtelierStyle.canvas),
                contentAlignment = Alignment.Center) {
                preview.display?.let { display ->
                val description = if (!showingCurrent) R.string.editor_previous_description else when (state.view) {
                    EditorView.RESULT -> R.string.editor_preview_description
                    EditorView.GEOMETRY_INPUT -> R.string.editor_input_description
                    EditorView.ORIGINAL -> R.string.editor_original_description
                }
                    Image(display.bitmap.asImageBitmap(), stringResource(description), modifier = Modifier.fillMaxSize().padding(6.dp).testTag("editor-preview"), contentScale = ContentScale.Fit)
                }
            }
            if (preview.processing) CircularProgressIndicator(Modifier.testTag("editor-processing"))
            preview.failure?.let { status ->
                Text(stringResource(when (status) {
                    RenderStatus.RESOURCE_LIMIT -> R.string.error_resource_limit
                    RenderStatus.STORAGE_FAILED -> R.string.error_storage_failed
                    RenderStatus.UNAVAILABLE -> R.string.error_provider_unavailable
                    RenderStatus.INVALID_INPUT -> if (preview.request == null) R.string.editor_shape_invalid_input else R.string.error_invalid_input
                    RenderStatus.INVALID_MAP -> R.string.editor_shape_invalid_map
                    RenderStatus.CANCELED -> R.string.error_canceled
                    else -> R.string.error_processing
                }), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                Button(onClick = session::retryPreview, enabled = editable, modifier = control("editor-retry-preview")) { Text(stringResource(R.string.editor_retry_preview)) }
            }
            if (panel == "shape") state.draft?.let {
                if (seedFeedback) Text(stringResource(R.string.atelier_frame_no_edges))
                val page = state.page
                when (val frame = it.geometry) {
                    is GeometryRecipe.Perspective -> if (page != null && loadOriginal != null) {
                        AtelierFrameCanvas(page.sourceId.value, frame, editable, loadOriginal, session)
                    }
                    is GeometryRecipe.Bezier -> if (page != null && loadOriginal != null) {
                        AtelierBezierControls(frame, editable, session) { control ->
                            AtelierFrameCanvas(page.sourceId.value, frame, editable, loadOriginal, session, control)
                        }
                    }
                    else -> Unit
                }
                EditorShapeControls(it.geometry, editable, session)
            }
            if (shapeBlocksApply) ShapeSaveNotice(state, preview)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = session::rotateCounterclockwise, enabled = editable, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-rotate-left")) {
                        Text(stringResource(R.string.atelier_left))
                    }
                    TextButton(onClick = session::rotateClockwise, enabled = editable, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-rotate")) {
                        Text(stringResource(R.string.atelier_right))
                    }
                }
                state.draft?.let { Text(stringResource(R.string.editor_turns, it.geometry.outputQuarterTurns * 90)) }
            }
            if (state.canUndo || state.canRedo || state.dirty) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = session::undo, enabled = editable && state.canUndo, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-undo")) { Text(stringResource(R.string.editor_undo)) }
                    TextButton(onClick = session::redo, enabled = editable && state.canRedo, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-redo")) { Text(stringResource(R.string.editor_redo)) }
                    TextButton(onClick = session::discard, enabled = editable && state.dirty, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-discard")) { Text(stringResource(R.string.editor_discard)) }
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
            }
            }
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
                                render.stages.mapNotNull { stage -> executedLabel(stage.stageId) }.distinct().forEach { label ->
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
            confirmButton = { TextButton(onClick = session::applyAndBack, enabled = editable && !shapeBlocksApply, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-apply-exit")) { Text(stringResource(R.string.editor_apply)) } },
            dismissButton = { Column {
                TextButton(onClick = session::keepEditing, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-keep-editing")) { Text(stringResource(R.string.editor_keep_editing)) }
                TextButton(onClick = session::discardAndBack, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-discard-exit")) { Text(stringResource(R.string.editor_discard_exit)) }
            } },
        )
    }

    private fun control(tag: String) = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
}

private fun executedLabel(id: String): Int? = when (id) {
    "perspective_v1" -> R.string.atelier_wizard_frame
    "manual_ruled_v1" -> R.string.editor_shape_curve
    "atelier_bezier_v1" -> R.string.atelier_bezier_mode
    "paper_gain_v1" -> R.string.editor_look_paper
    "natural_l_v1" -> R.string.editor_look_natural
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
