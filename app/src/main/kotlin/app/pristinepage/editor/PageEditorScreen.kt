package app.pristinepage.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pristinepage.R
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.library.errorText

/** A view of one retained session, not a second processing/store owner. The viewport is finite even inside a scroll container and never derives an unbounded height from a narrow source aspect. */
class PageEditorScreen : EditorContent {
    @Composable override fun Content(session: EditorSession, onExit: () -> Unit) {
        val state by session.state.collectAsStateWithLifecycle()
        val preview by session.preview.collectAsStateWithLifecycle()
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
        BackHandler(enabled = !state.exitReady) { session.requestBack() }
        val editable = state.page != null && !state.loading && !state.saving && !state.exitReady
        val expected = state.draft?.let { EditorViews.recipe(it,state.view) }
        val showingCurrent = !preview.previous && preview.display?.request == preview.request && preview.display?.request?.recipe == expected
        val viewLabel = when (state.view) {
            EditorView.RESULT -> R.string.editor_view_result
            EditorView.GEOMETRY_INPUT -> R.string.editor_view_input
            EditorView.ORIGINAL -> R.string.editor_view_original
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = session::requestBack, enabled = !state.saving, modifier = control("editor-back")) { Text(stringResource(R.string.editor_back)) }
            Text(stringResource(R.string.editor_heading), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.editor_scope_notice))
            if (state.loading) CircularProgressIndicator()
            state.error?.let {
                Text(errorText(it.code), color = MaterialTheme.colorScheme.error)
                if (state.page == null) Button(onClick = session::retryLoad, modifier = control("editor-retry-load")) { Text(stringResource(R.string.editor_retry_load)) }
            }
            if (state.dirty) Text(stringResource(R.string.editor_unsaved), modifier = Modifier.testTag("editor-unsaved"))
            if (state.saving) Text(stringResource(R.string.editor_saving))
            val selectedLook = state.draft?.matchingLook()
            Text(stringResource(R.string.editor_looks), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.editor_looks_sample_notice), style = MaterialTheme.typography.bodySmall)
            EditorLookCarousel(selectedLook, editable, session::chooseLook)

            val requestedLabel = if (state.view == EditorView.RESULT && selectedLook != null) {
                stringResource(R.string.editor_requested_look, stringResource(selectedLook.label))
            } else {
                stringResource(R.string.editor_requested_view, stringResource(viewLabel))
            }
            Text(requestedLabel)
            if (preview.display != null && !showingCurrent) Text(stringResource(R.string.editor_previous), modifier = Modifier.testTag("editor-previous"))
            preview.display?.let { display ->
                val description = if (!showingCurrent) R.string.editor_previous_description else when (state.view) {
                    EditorView.RESULT -> R.string.editor_preview_description
                    EditorView.GEOMETRY_INPUT -> R.string.editor_input_description
                    EditorView.ORIGINAL -> R.string.editor_original_description
                }
                Image(display.bitmap.asImageBitmap(), stringResource(description), modifier = Modifier.fillMaxWidth().height(300.dp).testTag("editor-preview"), contentScale = ContentScale.Fit)
            }
            if (preview.processing) CircularProgressIndicator(Modifier.testTag("editor-processing"))
            preview.failure?.let { status ->
                Text(stringResource(when (status) {
                    RenderStatus.RESOURCE_LIMIT -> R.string.error_resource_limit
                    RenderStatus.STORAGE_FAILED -> R.string.error_storage_failed
                    RenderStatus.UNAVAILABLE -> R.string.error_provider_unavailable
                    RenderStatus.INVALID_INPUT -> R.string.error_invalid_input
                    RenderStatus.CANCELED -> R.string.error_canceled
                    else -> R.string.error_processing
                }), color = MaterialTheme.colorScheme.error)
                Button(onClick = session::retryPreview, enabled = editable, modifier = control("editor-retry-preview")) { Text(stringResource(R.string.editor_retry_preview)) }
            }
            listOf("result" to R.string.editor_view_result, "input" to R.string.editor_view_input, "original" to R.string.editor_view_original).forEach { (id, label) ->
                OutlinedButton(onClick = { session.showView(id) }, enabled = editable, modifier = control("editor-view-$id").semantics { selected = state.view == EditorViews.parse(id) }) { Text(stringResource(label)) }
            }
            Text(stringResource(R.string.editor_preset_notice))
            Button(onClick = session::rotateClockwise, enabled = editable, modifier = control("editor-rotate")) { Text(stringResource(R.string.editor_rotate)) }
            state.draft?.let { Text(stringResource(R.string.editor_turns, it.geometry.outputQuarterTurns * 90)) }
            Button(onClick = session::undo, enabled = editable && state.canUndo, modifier = control("editor-undo")) { Text(stringResource(R.string.editor_undo)) }
            Button(onClick = session::redo, enabled = editable && state.canRedo, modifier = control("editor-redo")) { Text(stringResource(R.string.editor_redo)) }
            OutlinedButton(onClick = session::discard, enabled = editable && state.dirty, modifier = control("editor-discard")) { Text(stringResource(R.string.editor_discard)) }
            Button(onClick = session::apply, enabled = editable && state.dirty, modifier = control("editor-apply")) { Text(stringResource(R.string.editor_apply)) }
        }
        if (state.confirmExit) AlertDialog(
            modifier = Modifier.testTag("editor-exit-choice"),
            onDismissRequest = session::keepEditing,
            title = { Text(stringResource(R.string.editor_exit_title)) },
            text = { Text(stringResource(R.string.editor_exit_notice)) },
            confirmButton = { TextButton(onClick = session::applyAndBack, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-apply-exit")) { Text(stringResource(R.string.editor_apply)) } },
            dismissButton = { Column {
                TextButton(onClick = session::keepEditing, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-keep-editing")) { Text(stringResource(R.string.editor_keep_editing)) }
                TextButton(onClick = session::discardAndBack, modifier = Modifier.heightIn(min = 48.dp).testTag("editor-discard-exit")) { Text(stringResource(R.string.editor_discard_exit)) }
            } },
        )
    }

    private fun control(tag: String) = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
}
