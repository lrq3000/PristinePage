package app.pristinepage.capture

import android.content.Context
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.library.LibraryPhotoResult

/** One destination-owned camera binding and at most one attempted still. All admission and callbacks run on Main. */
internal class CameraController(
    context: Context,
    private val owner: LifecycleOwner,
    private val view: PreviewView,
    private val stage: CaptureStage,
    private val ready: (Boolean) -> Unit,
    private val result: (LibraryPhotoResult) -> Boolean,
) : AutoCloseable {
    private val main = ContextCompat.getMainExecutor(context)
    private val preview = Preview.Builder().build()
    private val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    private var provider: ProcessCameraProvider? = null
    private var closed = false
    private var shooting = false
    private var finished = false
    private var ownsStage = false
    private var cameraState: LiveData<CameraState>? = null
    private val streamObserver = Observer<PreviewView.StreamState> { ready(isReady()) }
    private val stateObserver = Observer<CameraState> { state ->
        if (state.error != null && !closed && !finished && !shooting) fail(OperationErrorCode.PROVIDER_UNAVAILABLE, "camera_unavailable")
        else ready(isReady())
    }

    init {
        val future = ProcessCameraProvider.getInstance(context.applicationContext)
        future.addListener({
            if (!closed) try {
                val ownerProvider = future.get()
                val lens = if (ownerProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                provider = ownerProvider
                preview.surfaceProvider = view.surfaceProvider
                cameraState = ownerProvider.bindToLifecycle(owner, lens, preview, capture).cameraInfo.cameraState
                cameraState?.observe(owner, stateObserver)
                view.previewStreamState.observe(owner, streamObserver)
            } catch (_: SecurityException) {
                fail(OperationErrorCode.PERMISSION_LOST, "camera_permission_denied")
            } catch (_: Exception) {
                fail(OperationErrorCode.PROVIDER_UNAVAILABLE, "camera_unavailable")
            }
        }, main)
    }

    private fun isReady() = !closed && !shooting && !finished &&
        cameraState?.value?.let { it.type == CameraState.Type.OPEN && it.error == null } == true &&
        view.previewStreamState.value == PreviewView.StreamState.STREAMING

    fun takePhoto(): Boolean {
        if (!isReady()) return false
        shooting = true
        ready(false)
        try {
            capture.targetRotation = view.display?.rotation ?: Surface.ROTATION_0
            val file = stage.create()
            ownsStage = true
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            capture.takePicture(options, main, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    finished = true
                    // Cancel may have won the durable session fence even if Compose has not disposed of this controller yet.
                    if (closed || !result(LibraryPhotoResult.Selected(listOf(stage.uri.toString()), 0))) discardOwnedStage()
                    else ownsStage = false
                }
                override fun onError(exception: ImageCaptureException) {
                    if (closed) discardOwnedStage() else fail(OperationErrorCode.PROCESSING_FAILED, "camera_capture_failed")
                }
            })
        } catch (_: SecurityException) {
            fail(OperationErrorCode.PERMISSION_LOST, "camera_permission_denied")
        } catch (_: Exception) {
            fail(OperationErrorCode.STORAGE_FAILED, "camera_capture_failed")
        }
        return true
    }

    private fun fail(code: OperationErrorCode, key: String) {
        finished = true
        discardOwnedStage()
        result(LibraryPhotoResult.Rejected(OperationError(code, false, key, null)))
    }

    private fun discardOwnedStage() {
        if (ownsStage) {
            ownsStage = false
            stage.discard()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        view.previewStreamState.removeObserver(streamObserver)
        cameraState?.removeObserver(stateObserver)
        provider?.unbind(preview, capture)
        // Recreating the preview while a shot is pending abandons that shot, but never cancels a still already delivered to the retained consumer.
        if (shooting && !finished) result(LibraryPhotoResult.Canceled)
    }
}
