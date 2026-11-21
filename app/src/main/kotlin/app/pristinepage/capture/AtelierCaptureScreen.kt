package app.pristinepage.capture

import android.Manifest
import android.content.pm.PackageManager
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pristinepage.R
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.design.CarnetViewfinderTheme
import app.pristinepage.editor.PageEditorScreen
import app.pristinepage.editor.shapeBlocksApply
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.library.LibraryPhotoResult
import app.pristinepage.library.acquisitionError
import app.pristinepage.library.errorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AtelierCaptureScreen(
    state: AtelierCaptureState, owner: AtelierCaptureViewModel, registry: ActivityResultRegistry, grid: Boolean,
    window: Window,
    loadOriginal: suspend (String) -> OriginalPreviewBitmap,
    loadQueueTile: suspend (String) -> OriginalPreviewBitmap,
) {
    val draft = state.draft
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    var review by rememberSaveable { mutableStateOf(false) }
    var confirmSkip by rememberSaveable { mutableStateOf(false) }
    val editor = state.editor
    val edit = editor?.state?.collectAsStateWithLifecycle()?.value
    val preview = editor?.preview?.collectAsStateWithLifecycle()?.value
    val canLeave = !state.busy && state.request == null && edit?.saving != true
    fun askBeforeLeaving() {
        editor?.cancelCurveAnalysis()
        confirmExit = true
    }
    fun back() {
        if (!canLeave) return
        if (review && draft?.editing == false) { review = false; return }
        if (draft?.pages.isNullOrEmpty() && draft?.pending == null) owner.leave(abandon = true)
        else askBeforeLeaving()
    }
    BackHandler { back() }
    if (state.resumeChoice) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.atelier_import_resume_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(if (draft?.target == null) R.string.atelier_import_resume_new else R.string.atelier_import_resume_append))
            state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            Button(owner::resumeChoice, enabled = !state.busy) { Text(stringResource(R.string.atelier_import_resume)) }
            TextButton({ owner.leave(false) }, enabled = !state.busy) { Text(stringResource(R.string.atelier_back)) }
            TextButton({ confirmExit = true }, enabled = !state.busy && draft?.pending == null) { Text(stringResource(R.string.atelier_import_abandon)) }
        }
    } else
    if (draft?.editing == true && editor != null && edit != null && preview != null) {
        val selected = draft.selected
        val move = !state.busy && edit.page != null && !edit.saving && !edit.loading && !edit.shapeBlocksApply(preview)
        val step = listOf(R.string.atelier_wizard_frame, R.string.atelier_rotation, R.string.editor_looks)[draft.step]
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Text(stringResource(R.string.atelier_wizard_progress, draft.step + 1, stringResource(step)),
                Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                draft.pages.forEachIndexed { index, page ->
                    FilterChip(selected == page.pageId, { owner.navigate(page.pageId, draft.step) },
                        { Text(stringResource(R.string.page_number, index + 1)) }, enabled = move)
                }
            }
            state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            Box(Modifier.weight(1f)) {
                key(editor, draft.step) {
                    PageEditorScreen(loadOriginal = loadOriginal, guidedStep = draft.step, guidedBusy = state.busy || confirmExit || confirmSkip)
                        .Content(editor, ::askBeforeLeaving)
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (draft.step == 0) owner.addMore() else selected?.let { owner.navigate(it, draft.step - 1) }
                }, enabled = move) { Text(stringResource(if (draft.step == 0) R.string.atelier_add_pages else R.string.atelier_back)) }
                if (draft.step == 0) TextButton({ editor.cancelCurveAnalysis(); confirmSkip = true }, enabled = canLeave,
                    modifier = Modifier.testTag("atelier-wizard-skip-frame")) { Text(stringResource(R.string.atelier_wizard_skip_frame)) }
                Button(onClick = {
                    if (draft.step == 2) owner.save() else selected?.let { owner.navigate(it, draft.step + 1) }
                }, enabled = move, modifier = Modifier.testTag("atelier-wizard-next")) {
                    Text(stringResource(if (draft.step == 2) R.string.atelier_wizard_save else R.string.atelier_wizard_next))
                }
            }
        }
    } else if (review && draft != null) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.atelier_review_import), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.atelier_import_count, draft.pages.size))
            AtelierImportStrip(draft.pages, loadQueueTile)
            state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            AtelierImportFeedback(state.importIssues)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton({ review = false }, enabled = !state.busy) { Text(stringResource(R.string.atelier_review_add_more)) }
            Button(owner::finish, enabled = !state.busy && draft.pending == null && draft.pages.isNotEmpty(),
                modifier = Modifier.testTag("atelier-finish-import")) { Text(stringResource(R.string.atelier_finish_import)) }
        }
    } else {
        DisposableEffect(window) {
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            val lightStatus = bars.isAppearanceLightStatusBars
            val lightNavigation = bars.isAppearanceLightNavigationBars
            bars.isAppearanceLightStatusBars = false
            bars.isAppearanceLightNavigationBars = false
            onDispose {
                bars.isAppearanceLightStatusBars = lightStatus
                bars.isAppearanceLightNavigationBars = lightNavigation
            }
        }
        CarnetViewfinderTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AtelierViewfinder(state, owner, registry, grid, ::back, { review = true }, loadQueueTile)
            }
        }
    }
    LaunchedEffect(draft?.editing) { if (draft?.editing == true) review = false }
    if (confirmSkip && draft?.editing == true && draft.step == 0) AlertDialog(
        onDismissRequest = { confirmSkip = false },
        title = { Text(stringResource(R.string.atelier_wizard_skip_frame_title)) },
        text = { Text(stringResource(R.string.atelier_wizard_skip_frame_body)) },
        confirmButton = { TextButton({ confirmSkip = false; owner.skipPerspective() },
            enabled = canLeave, modifier = Modifier.testTag("atelier-wizard-confirm-skip")) { Text(stringResource(R.string.atelier_wizard_skip_frame)) } },
        dismissButton = { TextButton({ confirmSkip = false }) { Text(stringResource(R.string.atelier_batch_keep)) } },
    )
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
        title = { Text(stringResource(R.string.atelier_import_pause)) },
        text = { Text(stringResource(if (edit?.dirty == true) R.string.atelier_import_pause_dirty_body else R.string.atelier_import_pause_body)) },
        confirmButton = { TextButton({ confirmExit = false; owner.leave(false) }, enabled = canLeave) {
            Text(stringResource(R.string.atelier_import_keep_draft))
        } },
        dismissButton = {
            TextButton({ confirmExit = false; owner.leave(true) }, enabled = canLeave && draft?.pending == null) {
                Text(stringResource(R.string.atelier_import_abandon))
            }
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AtelierViewfinder(state: AtelierCaptureState, owner: AtelierCaptureViewModel, registry: ActivityResultRegistry,
    grid: Boolean, back: () -> Unit, review: () -> Unit, loadOriginal: suspend (String) -> OriginalPreviewBitmap) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val draft = state.draft
    var sources by rememberSaveable { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var cameraError by remember { mutableStateOf<OperationErrorCode?>(null) }
    var ready by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<CameraController?>(null) }
    var activeShot by remember { mutableStateOf<PhotoImportRequest?>(null) }
    var cameraEpoch by remember { mutableIntStateOf(0) }
    var flash by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val request = state.request
    if (request != null && request.kind != PhotoImportKind.CAMERA) {
        DisposableEffect(registry, request) {
            val registration = PhotoPickerRegistration(registry, request) { origin, result ->
                val handoff = when (result) {
                    is PhotoSelection.Selected -> LibraryPhotoResult.Selected(result.uris.map { it.toString() }, result.offeredReadFlags)
                    is PhotoSelection.Rejected -> LibraryPhotoResult.Rejected(result.error)
                    PhotoSelection.Canceled -> LibraryPhotoResult.Canceled
                }
                owner.delivered(origin, handoff)
            }
            if (owner.claim(request)) try { registration.launch() } catch (_: RuntimeException) {
                owner.delivered(request, LibraryPhotoResult.Rejected(OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "picker_unavailable", null)))
            }
            onDispose { registration.close() }
        }
    }
    LaunchedEffect(request) {
        if (request?.kind == PhotoImportKind.CAMERA) {
            // The durable reservation may finish after CameraX loses its stream. Never hold the draft hostage to an unstarted still.
            withTimeoutOrNull(6_000) { snapshotFlow { ready || !granted || cameraError != null }.first { it } }
            if (!owner.claim(request)) return@LaunchedEffect
            if (!ready || !granted) {
                owner.delivered(request, LibraryPhotoResult.Rejected(OperationError(
                    if (granted) OperationErrorCode.PROVIDER_UNAVAILABLE else OperationErrorCode.PERMISSION_LOST,
                    false, "camera_not_ready", null)))
                return@LaunchedEffect
            }
            activeShot = request
            if (camera?.takePhoto(CaptureStage(context, request.batchId), flash) != true) {
                activeShot = null
                owner.delivered(request, LibraryPhotoResult.Rejected(OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "camera_not_ready", null)))
            }
        }
    }
    val idle = draft != null && !state.busy && request == null && draft.pending == null
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(back, enabled = !state.busy) { Text(stringResource(R.string.atelier_back)) }
            Text(stringResource(R.string.take_photo), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.atelier_import_count, draft?.pages?.size ?: 0))
        }
        state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
        AtelierImportFeedback(state.importIssues)
        cameraError?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
        if (granted && cameraError != null && !ready && idle) {
            TextButton({ cameraError = null; cameraEpoch++ }) { Text(stringResource(R.string.atelier_camera_retry)) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (draft?.pending != null && request == null && !state.busy) {
            Text(stringResource(R.string.atelier_import_interrupted))
            TextButton(owner::cancelPending) { Text(stringResource(R.string.atelier_import_clear_pending)) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            if (granted && draft != null) {
                val preview = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize().testTag("camera-preview"))
                DisposableEffect(lifecycle, preview, draft.id, cameraEpoch) {
                    val controller = CameraController(context, lifecycle, preview, null, { ready = it }) { result ->
                        val shot = activeShot
                        activeShot = null
                        if (shot != null) {
                            cameraEpoch++
                            owner.delivered(shot, result)
                        }
                        else { cameraError = (result as? LibraryPhotoResult.Rejected)?.error?.code; false }
                    }
                    camera = controller
                    onDispose { controller.close(); if (camera === controller) { camera = null; ready = false } }
                }
                if (ready && grid) Canvas(Modifier.fillMaxSize().testTag("atelier-camera-grid")) {
                    for (i in 1..2) {
                        val x = size.width * i / 3; val y = size.height * i / 3
                        val line = Color.White.copy(alpha = .45f)
                        drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        drawLine(line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                }
                if (idle && ready && camera?.hasFlash == true) {
                    FilterChip(flash, { flash = !flash }, { Text(stringResource(if (flash) R.string.atelier_flash_on else R.string.atelier_flash_off)) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).heightIn(min = 48.dp).testTag("atelier-camera-flash"))
                }
            } else if (draft != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.atelier_import_without_camera))
                TextButton({ permission.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.atelier_import_retry_camera)) }
            }
        }
        if (draft?.pages?.isNotEmpty() == true) AtelierImportStrip(draft.pages, loadOriginal)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton({ sources = true }, enabled = idle, modifier = Modifier.testTag("atelier-camera-import")) {
                Icon(painterResource(R.drawable.ic_atelier_import_grid), stringResource(R.string.atelier_import_sources))
            }
            Button({ owner.reserve(PhotoImportKind.CAMERA) }, enabled = idle && ready, modifier = Modifier.testTag("camera-shutter")) {
                Text(stringResource(R.string.camera_shutter))
            }
            TextButton(review, enabled = idle && draft.pages.isNotEmpty(), modifier = Modifier.testTag("atelier-review-import")) {
                Text(stringResource(R.string.atelier_review_import))
            }
        }
    }
    if (sources) ModalBottomSheet(onDismissRequest = { sources = false }) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.atelier_import_sources), style = MaterialTheme.typography.titleMedium)
            TextButton({ sources = false; owner.reserve(PhotoImportKind.GALLERY) }, modifier = Modifier.testTag("atelier-import-gallery")) {
                Text(stringResource(R.string.atelier_import_gallery))
            }
            TextButton({ sources = false; owner.reserve(PhotoImportKind.FILES) }, modifier = Modifier.testTag("atelier-import-files")) {
                Text(stringResource(R.string.atelier_import_files))
            }
        }
    }
}

@Composable private fun AtelierImportFeedback(issues: List<AtelierImportIssue>) {
    var details by remember(issues) { mutableStateOf(false) }
    issues.firstOrNull { it.position == null }?.error?.let {
        Text(acquisitionError(it), color = MaterialTheme.colorScheme.error)
    }
    val items = issues.filter { it.position != null }
    if (items.isEmpty()) return
    TextButton({ details = true }, modifier = Modifier.testTag("atelier-import-issues")) {
        Text(stringResource(R.string.atelier_import_issues, items.size))
    }
    if (details) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.atelier_import_issue_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in items) {
                    val position = checkNotNull(item.position)
                    val error = item.error
                    Text(if (error == null) stringResource(R.string.atelier_import_item_pending, position)
                        else stringResource(R.string.item_rejected, position, acquisitionError(error)))
                }
            }
        },
        confirmButton = { TextButton({ details = false }) { Text(stringResource(R.string.atelier_close)) } },
    )
}

@Composable private fun AtelierImportStrip(pages: List<DocumentPage>, load: suspend (String) -> OriginalPreviewBitmap) {
    // Only visible queue tiles retain a small display; originals still go through the verified full-source decoder.
    LazyRow(Modifier.fillMaxWidth().testTag("atelier-import-queue"),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(pages, key = { it.pageId.value }) { page ->
            var thumbnail by remember(page.sourceId) { mutableStateOf<OriginalPreviewBitmap?>(null) }
            var failed by remember(page.sourceId) { mutableStateOf(false) }
            LaunchedEffect(page.sourceId) {
                var owned: OriginalPreviewBitmap? = null
                try {
                    owned = load(page.sourceId.value)
                    thumbnail = owned
                    awaitCancellation()
                } catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) { failed = true }
                finally { thumbnail = null; owned?.close() }
            }
            Surface(shape = MaterialTheme.shapes.small, tonalElevation = 2.dp) {
                Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                    val preview = thumbnail
                    if (preview != null) Image(preview.bitmap.asImageBitmap(), stringResource(R.string.page_number, page.ordinal + 1),
                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else if (failed) Text(stringResource(R.string.page_number, page.ordinal + 1))
                    else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
