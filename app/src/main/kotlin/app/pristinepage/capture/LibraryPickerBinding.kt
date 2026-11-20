package app.pristinepage.capture

import android.content.ActivityNotFoundException
import androidx.activity.result.ActivityResultRegistry
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.library.LibraryEvent
import app.pristinepage.library.LibraryPhotoResult
import app.pristinepage.library.LibrarySession

/** Main-thread Activity owner of exactly one current request registration. Restore/synchronize before STARTED; close on destruction, while the retained session/structured IO outlives recreation. No saved URI or second import engine lives here. */
class LibraryPickerBinding(private val registry: ActivityResultRegistry, private val session: LibrarySession) : AutoCloseable {
    private var registeredId: String? = null
    private var registration: PhotoPickerRegistration? = null

    fun synchronize() {
        val request = session.state.value.request?.takeUnless { it.kind == PhotoImportKind.CAMERA }
        if (registeredId != request?.batchId) {
            close()
            if (request != null) {
                registeredId = request.batchId
                // register may synchronously consume a queued raw result before returning its launcher. The callback closes over immutable identity/session only, not registration or a constructor-local lateinit variable.
                registration = PhotoPickerRegistration(registry, request) { owner, result ->
                    val handoff = when (result) {
                        is PhotoSelection.Selected -> LibraryPhotoResult.Selected(result.uris.map { it.toString() }, result.offeredReadFlags)
                        PhotoSelection.Canceled -> LibraryPhotoResult.Canceled
                        is PhotoSelection.Rejected -> LibraryPhotoResult.Rejected(result.error)
                    }
                    session.accept(LibraryEvent.PhotoResult(owner, handoff))
                }
            }
        }
        val active = session.state.value.request
        val launcher = registration
        if (active != null && active.batchId == registeredId && launcher != null && session.claimPickerLaunch(ImportSessionId(active.batchId))) {
            try { launcher.launch() }
            catch (_: ActivityNotFoundException) { failed(active) }
            catch (_: SecurityException) { failed(active) }
            catch (_: IllegalStateException) { failed(active) }
        }
    }

    private fun failed(request: PhotoImportRequest) { session.accept(LibraryEvent.PickerFailed(request)) }
    override fun close() { registration?.close(); registration = null; registeredId = null }
}
