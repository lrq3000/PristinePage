package app.pristinepage.ocr

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Best-effort wake signals live with the application process, not the view model, screen or save transaction. */
internal class SavedOcrAppSignals(
    private val processScope: CoroutineScope,
    private val scheduling: SavedOcrScheduling,
) {
    private val startupStarted = AtomicBoolean(false)
    private val manualRerun = MutableStateFlow(false)
    val rerunInFlight = manualRerun.asStateFlow()

    fun onStartup() {
        if (!startupStarted.compareAndSet(false, true)) return
        processScope.launch {
            var completed = false
            try { completed = deliver { scheduling.onStartup() } }
            finally { if (!completed) startupStarted.set(false) }
        }
    }

    fun onPagesSaved(document: DocumentId, pages: List<PageId>) {
        val committed = pages.toList()
        if (committed.isEmpty()) return
        processScope.launch {
            for (page in committed) deliver { scheduling.onPageSaved(document, page) }
        }
    }

    /** A deliberate command may finish after its dialog closes. Only the observation-bound Room job and its opaque wake can survive the dialog. */
    suspend fun onRerun(
        qualify: suspend () -> OcrConfiguration?,
        commit: suspend (OcrConfiguration) -> SavedOcrWork.Target,
    ): SavedOcrScheduling.RerunOutcome {
        currentCoroutineContext().ensureActive()
        return processScope.async {
            // Keep the gate with the command even when the original UI waiter is canceled or a new panel is opened.
            if (!manualRerun.compareAndSet(expect = false, update = true)) return@async SavedOcrScheduling.RerunOutcome.BUSY
            try { scheduling.requestRerun(qualify, commit) } finally { manualRerun.value = false }
        }.await()
    }

    private suspend fun deliver(wake: suspend () -> Unit): Boolean = try {
        wake()
        true
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (_: Exception) {
        // Room work and the accepted page remain discoverable at startup. Never reinterpret a wake failure as failed save or recognition, or log a raw cause.
        false
    }
}

/** A notification dispatch failure must not enter the foreground error handler for a save that already committed. */
internal fun sendCommittedOcrHint(document: DocumentId, pages: List<PageId>, send: (DocumentId, List<PageId>) -> Unit) {
    try { send(document, pages) }
    catch (canceled: CancellationException) { throw canceled }
    catch (_: Exception) {
        // The matching page remains discoverable from Room on a later startup; do not log the delegate's error.
    }
}
