package app.pristinepage.ocr

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pristinepage.R
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.SavedOcrWork.TextSaveOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class OcrTextSaveViewModel(
    context: Context,
    private val open: suspend () -> SavedOcrWork,
) : ViewModel() {
    private val app = context.applicationContext
    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()
    private var request: Pending? = null
    private var retry: SavedOcrWork.PreparedTextExport? = null

    internal data class State(
        val busy: Boolean = false,
        val consent: String? = null,
        val launchRequested: Boolean = false,
        val notice: Int? = null,
        val pastWriteNeedsVerification: Boolean = false,
        val destination: PrivateDestination? = null,
        val mayRetry: Boolean = false,
    )

    /** Private on-screen label only, with no data-class toString carrying the URI. */
    internal class PrivateDestination(val label: String)
    private class Pending(val id: String, val result: CompletableDeferred<Destination?> = CompletableDeferred())
    private class Destination(val uri: Uri, val read: Boolean)

    init {
        viewModelScope.launch {
            try {
                val recovered = open().recoverTextPublications()
                if (recovered.needsVerification && !current.value.busy && current.value.notice == null) {
                    retry = recovered.retry
                    current.value = current.value.copy(pastWriteNeedsVerification = true,
                        destination = destinationLabel(recovered.priorDestination), mayRetry = retry != null)
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { /* Text-publication recovery must not prevent library or OCR use. */ }
        }
    }

    @MainThread fun save(observed: SavedOcrWork.TextExportObservation): Boolean =
        submit { it.prepareTextExport(observed) }

    @MainThread fun retry() {
        val reviewed = retry ?: return
        submit { reviewed }
    }

    private fun submit(freeze: suspend (SavedOcrWork) -> SavedOcrWork.PreparedTextExport?): Boolean {
        if (current.value.busy) return false
        retry = null
        current.value = State(busy = true)
        viewModelScope.launch {
            var destination: Destination? = null
            var prepared: SavedOcrWork.PreparedTextExport? = null
            try {
                val work = open()
                val frozen = freeze(work)
                prepared = frozen
                if (frozen == null) {
                    notify(R.string.ocr_save_text_changed)
                    return@launch
                }
                val result = work.publishPreparedText(frozen, choose = { id ->
                    val pending = Pending(id)
                    request = pending
                    current.value = current.value.copy(consent = id, launchRequested = true)
                    try { destination = pending.result.await() }
                    finally {
                        if (request === pending) { request = null; current.value = current.value.copy(consent = null, launchRequested = false) }
                    }
                    destination?.uri?.toString()
                }) { target, bytes ->
                    val selected = checkNotNull(destination)
                    check(selected.uri.toString() == target)
                    val resolver = app.contentResolver
                    writeOcrTextDocument(bytes, frozen.byteSha256,
                        open = { resolver.openFileDescriptor(selected.uri, "rw") },
                        reopen = if (selected.read) ({ resolver.openInputStream(selected.uri) }) else null)
                }
                when (result) {
                    TextSaveOutcome.CANCELED -> if (current.value.notice != null) offerRetry(frozen, null) else Unit
                    TextSaveOutcome.READBACK_VERIFIED -> notify(R.string.ocr_save_text_verified)
                    TextSaveOutcome.OUTPUT_COMPLETED -> notify(R.string.ocr_save_text_unchecked)
                    TextSaveOutcome.PARTIAL_EXTERNAL -> notifyRetry(R.string.ocr_save_text_partial, frozen, destination)
                    TextSaveOutcome.NEEDS_VERIFICATION -> notifyRetry(R.string.ocr_save_text_verify, frozen, destination)
                    TextSaveOutcome.DESTINATION_REFUSED -> notifyRetry(R.string.ocr_save_text_refused, frozen, destination)
                    TextSaveOutcome.PERMISSION_LOST -> notifyRetry(R.string.ocr_save_text_permission, frozen, destination)
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                notify(if (destination == null) R.string.ocr_save_text_failed else R.string.ocr_save_text_verify)
                prepared?.let { offerRetry(it, destination) }
            } finally {
                current.value = current.value.copy(busy = false)
            }
        }
        return true
    }

    @MainThread fun consumeLaunch(consent: String): Intent? {
        if (request?.id != consent || !current.value.launchRequested) return null
        current.value = current.value.copy(launchRequested = false)
        return localPicker()
    }

    @MainThread fun accept(consent: String, code: Int, data: Intent?) {
        val pending = request?.takeIf { it.id == consent && !it.result.isCompleted && !current.value.launchRequested } ?: return
        val selected = if (code == Activity.RESULT_OK && data != null) {
            try { localDestination(data) } catch (_: RuntimeException) { null }
        } else null
        if (code == Activity.RESULT_OK && selected == null) notify(R.string.ocr_save_text_local_only)
        pending.result.complete(selected)
    }

    @MainThread fun launchFailed(consent: String) {
        val pending = request?.takeIf { it.id == consent && !it.result.isCompleted } ?: return
        notify(R.string.ocr_save_text_chooser_failed)
        pending.result.complete(null)
    }

    @MainThread fun dismissNotice() { retry = null; current.value = State() }
    private fun notify(label: Int) { current.value = current.value.copy(notice = label) }

    private fun notifyRetry(label: Int, frozen: SavedOcrWork.PreparedTextExport, destination: Destination?) {
        notify(label)
        offerRetry(frozen, destination)
    }

    private fun offerRetry(frozen: SavedOcrWork.PreparedTextExport, destination: Destination?) {
        retry = frozen
        current.value = current.value.copy(mayRetry = true, destination = destinationLabel(destination?.uri?.toString()))
    }

    private fun destinationLabel(value: String?): PrivateDestination? {
        val uri = value?.let(Uri::parse)?.takeIf { it.scheme == "content" && it.authority in localAuthorities } ?: return null
        val id = try { DocumentsContract.getDocumentId(uri) } catch (_: IllegalArgumentException) { return null }
        val visible = if (id.startsWith("raw:")) id.removePrefix("raw:") else uri.toString()
        return PrivateDestination(visible.filterNot(Char::isISOControl))
    }

    private fun localPicker(): Intent? {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "recognized-text.txt").putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        for (packageName in localPickers) {
            val resolved = app.packageManager.resolveActivity(intent.setPackage(packageName), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                ?: continue
            if (resolved.packageName == packageName && resolved.exported && resolved.enabled &&
                resolved.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                return intent.setComponent(ComponentName(packageName, resolved.name))
            }
        }
        return null
    }

    private fun localDestination(data: Intent): Destination? {
        val uri = data.data ?: return null
        val authority = uri.authority ?: return null
        if (authority !in localAuthorities || uri.scheme != "content" || uri.toString().encodeToByteArray().size > 4096 ||
            data.clipData != null && data.clipData?.itemCount != 1 || !DocumentsContract.isDocumentUri(app, uri) ||
            DocumentsContract.isTreeUri(uri)) return null
        // EXTRA_LOCAL_ONLY is a chooser hint. Do not hand verbatim text to an arbitrary provider that ignores it.
        val provider = app.packageManager.resolveContentProvider(authority, 0) ?: return null
        if (provider.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) return null
        fun grants(flag: Int) = data.flags and flag != 0 &&
            app.checkUriPermission(uri, Process.myPid(), Process.myUid(), flag) == PackageManager.PERMISSION_GRANTED
        if (!grants(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) return null
        return Destination(uri, grants(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private companion object {
        val localPickers = listOf("com.android.documentsui", "com.google.android.documentsui")
        val localAuthorities = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents")
    }
}
