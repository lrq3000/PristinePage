package app.pristinepage.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pristinepage.R
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.library.LibraryPhotoResult

@Composable internal fun CaptureScreen(request: PhotoImportRequest, cancel: () -> Unit, showGrid: Boolean = true, onResult: (LibraryPhotoResult) -> Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentResult by rememberUpdatedState(onResult)
    fun permissionGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(permissionGranted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var shooting by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<CameraController?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) currentResult(LibraryPhotoResult.Rejected(OperationError(OperationErrorCode.PERMISSION_LOST, false, "camera_permission_denied", null)))
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    BackHandler(onBack = cancel)
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.take_photo))
        if (granted) {
            val preview = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER } }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize().testTag("camera-preview"))
                // The grid decorates the live surface only; the CameraX still and original contain no overlay.
                if (showGrid && ready) Canvas(Modifier.fillMaxSize().testTag("atelier-camera-grid")) {
                    for (third in 1..2) {
                        val x = size.width * third / 3
                        val y = size.height * third / 3
                        val line = Color.White.copy(alpha = .45f)
                        drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        drawLine(line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                }
            }
            DisposableEffect(owner, preview, request.batchId) {
                val active = CameraController(context, owner, preview, CaptureStage(context, request.batchId), { ready = it }, { currentResult(it) })
                controller = active
                onDispose { active.close(); controller = null; ready = false }
            }
        }
        if (!ready) Text(stringResource(if (shooting) R.string.camera_taking_photo else R.string.camera_waiting))
        Button(onClick = { controller?.let { active -> if (active.takePhoto()) shooting = true } }, enabled = ready, modifier = Modifier.fillMaxWidth().testTag("camera-shutter")) { Text(stringResource(R.string.camera_shutter)) }
        Button(onClick = cancel, modifier = Modifier.fillMaxWidth().testTag("camera-cancel")) { Text(stringResource(R.string.cancel_import)) }
    }
}
