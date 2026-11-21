package app.pristinepage.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.pristinepage.ApplicationImports
import app.pristinepage.R
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.pipeline.*
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.DocumentOverview
import app.pristinepage.data.model.PageId
import app.pristinepage.image.preview.RenderedPreviewBitmap
import app.pristinepage.image.preview.RenderedPreviewBitmapLoader
import app.pristinepage.library.errorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BatchEditScreen(host: BatchHost, owner: BatchEditViewModel, openGraph: suspend () -> ApplicationImports,
    onExport: (DocumentOverview, Set<PageId>) -> Unit = { _, _ -> }) {
    val draft = owner.draft
    val selected = draft?.selection.orEmpty()
    val recipes = draft?.recipes.orEmpty()
    val enabled = draft != null && !host.saving && !host.uncertain
    val permit = remember { Semaphore(1) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var look by rememberSaveable { mutableStateOf<BatchLook?>(null) }
    var showStrength by rememberSaveable { mutableStateOf(false) }
    val rotation = draft?.rotation()
    val strength = draft?.appearanceStrength()
    val canSetStrength = draft?.canSetAppearanceStrength == true
    fun exit() {
        if (host.saving) return
        if (draft?.dirty == true && !host.uncertain) discard = true else owner.cancel()
    }
    BackHandler { exit() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.atelier_batch_heading), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.atelier_batch_selected, selected.size), style = MaterialTheme.typography.bodyMedium)
        if (host.saving || draft == null && host.error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        host.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
        if (host.uncertain) Text(stringResource(R.string.atelier_batch_uncertain), color = MaterialTheme.colorScheme.error)
        LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f).testTag("atelier-batch-pages"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                FlowRow {
                    TextButton({ owner.edit { selectAll(); null } }, enabled = enabled) { Text(stringResource(R.string.atelier_batch_all)) }
                    TextButton({ owner.edit { clearSelection(); null } }, enabled = enabled && selected.isNotEmpty()) { Text(stringResource(R.string.atelier_batch_clear)) }
                    TextButton({ owner.edit { rotateClockwise() } }, enabled = enabled && selected.isNotEmpty()) { Text(stringResource(R.string.atelier_batch_rotate)) }
                }
                when (rotation) {
                    is BatchValue.Shared -> Text(stringResource(R.string.atelier_batch_angle, rotation.value * 90))
                    BatchValue.Mixed -> Text(stringResource(R.string.atelier_batch_rotation_mixed))
                    else -> Unit
                }
                }
            }
            itemsIndexed(host.pages, key = { _, page -> page.pageId.value }) { index, page ->
                val recipe = recipes[page.pageId] ?: page.recipe
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = if (page.pageId in selected) 6.dp else 1.dp) {
                    Column(Modifier.toggleable(page.pageId in selected, enabled = enabled, role = Role.Checkbox,
                        onValueChange = { owner.edit { toggle(page.pageId); null } }).padding(6.dp)) {
                        BatchPreview(page, recipe, draft?.previewGeneration(page.pageId) ?: 1L, openGraph, permit)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(page.pageId in selected, onCheckedChange = null)
                            Text(stringResource(R.string.page_number, index + 1))
                        }
                        Text(stringResource(R.string.atelier_batch_angle, recipe.geometry.outputQuarterTurns * 90),
                            style = MaterialTheme.typography.labelMedium)
                        recipe.appearance.classicalStrength()?.let { value ->
                            Text(stringResource(R.string.atelier_batch_strength_value, (value * 100).roundToInt()),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                FlowRow {
                    for (preset in BatchLook.entries) {
                        TextButton(onClick = { look = preset }, enabled = enabled && selected.isNotEmpty()) {
                            Text(stringResource(preset.label()))
                        }
                    }
                }
                if (selected.isNotEmpty()) {
                    val shared = (strength as? BatchValue.Shared)?.value
                    when {
                        strength == BatchValue.Mixed -> Text(stringResource(R.string.atelier_batch_strength_mixed))
                        shared != null -> Text(stringResource(R.string.atelier_batch_strength_value, (shared * 100).roundToInt()))
                    }
                    if (!canSetStrength) Text(stringResource(R.string.atelier_batch_strength_incompatible), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showStrength = true }, enabled = enabled && canSetStrength) {
                        Text(stringResource(R.string.atelier_batch_change_strength))
                    }
                }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { host.album?.let { onExport(it, selected) } },
                enabled = enabled && selected.isNotEmpty() && draft?.dirty == false,
                modifier = Modifier.heightIn(min = 48.dp).testTag("album-export-selected")) {
                Text(stringResource(R.string.album_export_selected))
            }
            TextButton({ owner.edit { undo(); null } }, enabled = enabled && draft?.canUndo == true) { Text(stringResource(R.string.atelier_batch_undo)) }
            TextButton({ owner.edit { redo(); null } }, enabled = enabled && draft?.canRedo == true) { Text(stringResource(R.string.atelier_batch_redo)) }
            TextButton(::exit, enabled = !host.saving) { Text(stringResource(if (host.uncertain) R.string.atelier_batch_close else R.string.atelier_batch_cancel)) }
            Button(owner::done, enabled = enabled, modifier = Modifier.testTag("atelier-batch-done")) { Text(stringResource(R.string.atelier_batch_done)) }
        }
        if (draft?.dirty == true) Text(stringResource(R.string.album_export_batch_unsaved), style = MaterialTheme.typography.bodySmall)
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.atelier_batch_discard_title)) },
        text = { Text(stringResource(R.string.atelier_batch_discard_body)) },
        confirmButton = { TextButton(onClick = { discard = false; owner.cancel() }) { Text(stringResource(R.string.atelier_batch_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.atelier_batch_keep)) } },
    )
    look?.let { preset -> AlertDialog(
        onDismissRequest = { look = null },
        title = { Text(stringResource(preset.label())) },
        text = { Text(stringResource(R.string.atelier_batch_confirm_look, selected.size, stringResource(preset.effects()))) },
        confirmButton = { TextButton(onClick = { look = null; owner.edit { chooseLook(preset) } }, enabled = enabled) { Text(stringResource(R.string.atelier_batch_apply)) } },
        dismissButton = { TextButton(onClick = { look = null }) { Text(stringResource(R.string.atelier_batch_cancel)) } },
    ) }
    if (showStrength) {
        var percent by rememberSaveable { mutableStateOf((strength as? BatchValue.Shared)?.value?.let { (it * 100).roundToInt() }) }
        AlertDialog(
            onDismissRequest = { showStrength = false },
            title = { Text(stringResource(R.string.atelier_batch_change_strength)) },
            text = {
                Column {
                    val value = percent
                    Text(if (value == null) stringResource(R.string.atelier_batch_strength_mixed)
                        else stringResource(R.string.atelier_batch_strength_value, value))
                    Slider(value = (value ?: 50).toFloat(), onValueChange = { percent = it.roundToInt() },
                        valueRange = 0f..100f, steps = 99)
                }
            },
            confirmButton = { TextButton(onClick = {
                percent?.let { owner.edit { setAppearanceStrength(it) } }
                showStrength = false
            }, enabled = enabled && canSetStrength && percent != null) { Text(stringResource(R.string.atelier_batch_apply)) } },
            dismissButton = { TextButton(onClick = { showStrength = false }) { Text(stringResource(R.string.atelier_batch_cancel)) } },
        )
    }
}

@Composable private fun BatchPreview(page: EditablePage, recipe: Recipe, generation: Long,
    openGraph: suspend () -> ApplicationImports, permit: Semaphore) {
    val lifecycle = LocalLifecycleOwner.current
    var image by remember(page, recipe, generation) { mutableStateOf<RenderedPreviewBitmap?>(null) }
    var failed by remember(page, recipe, generation) { mutableStateOf(false) }
    LaunchedEffect(page, recipe, generation, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var owned: RenderedPreviewBitmap? = null
            try {
                failed = false
                permit.withPermit {
                    val graph = openGraph()
                    val output = GeometrySizing.output(recipe.geometry, RenderPurpose.PREVIEW, 192)
                    val request = RenderRequest(page.sourceId.value, recipe.geometry.source.sourcePixelHash, page.pageId.value,
                        page.recipeRevision, recipe, output, generation, null, FallbackPolicy.FAIL)
                    graph.renderer.render(request).use { result ->
                        if (result.request != request || result.status != RenderStatus.OK) {
                            failed = true
                        } else {
                            owned = RenderedPreviewBitmapLoader(graph.budget).convert(result)
                            coroutineContext.ensureActive()
                            image = owned
                        }
                    }
                }
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { failed = true }
            finally { image = null; owned?.close() }
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1.18f), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (failed) Text(stringResource(R.string.atelier_batch_preview_failed), style = MaterialTheme.typography.labelMedium)
        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

private fun BatchLook.label(): Int = when (this) {
    BatchLook.NONE -> R.string.atelier_batch_none
    BatchLook.PAPER -> R.string.atelier_batch_paper
    BatchLook.NATURAL -> R.string.atelier_batch_natural
    BatchLook.GRAY -> R.string.atelier_batch_gray
    BatchLook.BINARY -> R.string.atelier_batch_binary
    BatchLook.DOCUMENT_COLOR -> R.string.editor_look_document_color
}

private fun BatchLook.effects(): Int = when (this) {
    BatchLook.NONE -> R.string.atelier_batch_none_effect
    BatchLook.PAPER, BatchLook.NATURAL, BatchLook.DOCUMENT_COLOR -> R.string.atelier_batch_color_effect
    BatchLook.GRAY -> R.string.atelier_batch_gray_effect
    BatchLook.BINARY -> R.string.atelier_batch_binary_effect
}
