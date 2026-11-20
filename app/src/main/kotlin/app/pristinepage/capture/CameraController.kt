package app.pristinepage.capture

import android.content.Context
import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import app.pristinepage.data.model.OperationError
import app.pristinepage.ApplicationImageBudget
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.library.LibraryPhotoResult

/** One destination-owned camera binding and at most one attempted still. All admission and callbacks run on Main. */
internal class CameraController(
    context: Context,
    private val owner: LifecycleOwner,
    private val view: PreviewView,
    private val defaultStage: CaptureStage?,
    private val ready: (Boolean) -> Unit,
    private val result: (LibraryPhotoResult) -> Boolean,
) : AutoCloseable {
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val preview = Preview.Builder().build()
    private val capacity = ApplicationImageBudget.create(checkNotNull(context.getSystemService(ActivityManager::class.java)).memoryClass).capacityBytes
    private val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(ResolutionSelector.Builder().setResolutionFilter { sizes, _ ->
            sizes.filter { CameraStillSizing.fits(it.width, it.height, capacity) }
        }.build()).build()
    private var cameraControl: CameraControl? = null
    private var illumination = StillIllumination(flash = false)
    private var torchRequested = false
    private val flashTimeout = Runnable { if (!closed && !finished && torchRequested) fail(OperationErrorCode.PROVIDER_UNAVAILABLE, "camera_flash_unavailable") }
    private val flashCutoff = Runnable { illumination.expire(); stopTorch() }
    private var provider: ProcessCameraProvider? = null
    private var closed = false
    private var shooting = false
    private var captureSubmitted = false
    private var finished = false
    private var ownsStage = false
    private var activeStage: CaptureStage? = null
    var hasFlash = false
        private set
    private var cameraState: LiveData<CameraState>? = null
    private val streamObserver = Observer<PreviewView.StreamState> { ready(isReady()) }
    private val stateObserver = Observer<CameraState> { state ->
        if (torchRequested && shooting && (state.error != null || state.type != CameraState.Type.OPEN)) {
            if (!captureSubmitted) fail(OperationErrorCode.PROVIDER_UNAVAILABLE, "camera_flash_unavailable")
            else {
                illumination.expire()
                stopTorch()
            }
        }
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
                val camera = ownerProvider.bindToLifecycle(owner, lens, preview, capture)
                cameraControl = camera.cameraControl
                val info = camera.cameraInfo
                hasFlash = info.hasFlashUnit()
                cameraState = info.cameraState
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

    fun takePhoto(stage: CaptureStage = checkNotNull(defaultStage), flash: Boolean = false): Boolean {
        if (!isReady()) return false
        activeStage = stage
        shooting = true
        illumination = StillIllumination(flash)
        ready(false)
        if (flash) {
            val control = cameraControl
            if (control == null || !hasFlash) {
                fail(OperationErrorCode.PROVIDER_UNAVAILABLE, "camera_flash_unavailable")
            } else {
                // Wait for the hardware-control acknowledgement before asking for exposure. Using Torch with
                // still flash OFF keeps the light active for the whole shot without a competing AE flash sequence.
                try {
                    torchRequested = true
                    val result = control.enableTorch(true)
                    handler.postDelayed(flashTimeout, 2_500)
                    result.addListener({
                        handler.removeCallbacks(flashTimeout)
                        if (!closed && !finished) {
                            try {
                                result.get()
                                if (!illumination.acknowledge()) {
                                    fail(OperationErrorCode.PROVIDER_UNAVAILABLE, "camera_flash_unavailable")
                                    return@addListener
                                }
                                // Cap illumination independently of how long the camera takes to encode or deliver the JPEG.
                                handler.postDelayed(flashCutoff, 4_000)
                                captureStill(stage)
                            } catch (_: Exception) {
                                fail(OperationErrorCode.PROVIDER_UNAVAILABLE, "camera_flash_unavailable")
                            }
                        }
                    }, main)
                } catch (_: Exception) {
                    fail(OperationErrorCode.PROVIDER_UNAVAILABLE, "camera_flash_unavailable")
                }
            }
        } else captureStill(stage)
        return true
    }

    private fun captureStill(stage: CaptureStage) {
        try {
            capture.flashMode = ImageCapture.FLASH_MODE_OFF
            capture.targetRotation = view.display?.rotation ?: Surface.ROTATION_0
            val file = stage.create()
            ownsStage = true
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            captureSubmitted = true
            capture.takePicture(options, main, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (!closed && !illumination.acceptDelivery) {
                        fail(OperationErrorCode.PROVIDER_UNAVAILABLE, "camera_flash_unavailable")
                        return
                    }
                    finished = true
                    stopTorch()
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
    }

    private fun fail(code: OperationErrorCode, key: String) {
        finished = true
        illumination.expire()
        stopTorch()
        discardOwnedStage()
        result(LibraryPhotoResult.Rejected(OperationError(code, false, key, null)))
    }

    private fun stopTorch() {
        handler.removeCallbacks(flashTimeout)
        handler.removeCallbacks(flashCutoff)
        if (torchRequested) {
            torchRequested = false
            // Unbinding also releases this use case if the camera lost its control connection.
            runCatching { cameraControl?.enableTorch(false) }
        }
    }

    private fun discardOwnedStage() {
        if (ownsStage) {
            ownsStage = false
            activeStage?.discard()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        illumination.expire()
        stopTorch()
        view.previewStreamState.removeObserver(streamObserver)
        cameraState?.removeObserver(stateObserver)
        provider?.unbind(preview, capture)
        // Recreating the preview while a shot is pending abandons that shot, but never cancels a still already delivered to the retained consumer.
        if (shooting && !finished) result(LibraryPhotoResult.Canceled)
    }
}
