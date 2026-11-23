package app.pristinepage.ocr

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import java.util.concurrent.atomic.AtomicReference
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
    private enum class Startup(val pending: Boolean = false, val invalidated: Boolean = false) {
        IDLE, RUNNING, PENDING(pending = true), COMPLETE,
        RUNNING_INVALIDATED(invalidated = true), PENDING_INVALIDATED(pending = true, invalidated = true);

        fun afterPass(completed: Boolean) = when {
            completed && !invalidated -> COMPLETE
            pending -> RUNNING
            else -> IDLE
        }
    }
    private val startup = AtomicReference(Startup.IDLE)
    private val manualRerun = MutableStateFlow(false)
    val rerunInFlight = manualRerun.asStateFlow()

    fun onStartup() {
        // A foreground event during a refused/incomplete pass must not wait for a third foreground transition.
        val previous = startup.getAndUpdate {
            when (it) {
                Startup.IDLE -> Startup.RUNNING
                Startup.RUNNING -> Startup.PENDING
                Startup.RUNNING_INVALIDATED -> Startup.PENDING_INVALIDATED
                else -> it
            }
        }
        if (previous != Startup.IDLE) return
        processScope.launch {
            try {
                do {
                    val completed = deliver { scheduling.onStartup() } == true
                    // Invalidation and pass completion are one transition: a concurrent failed save may be behind this pass's cursor.
                    val finished = startup.getAndUpdate { it.afterPass(completed) }
                } while (finished.afterPass(completed) == Startup.RUNNING)
            } catch (canceled: CancellationException) {
                startup.set(Startup.IDLE)
                throw canceled
            }
        }
    }

    fun onPagesSaved(document: DocumentId, pages: List<PageId>) {
        val committed = pages.toList()
        if (committed.isEmpty()) return
        processScope.launch {
            var completed = false
            try { completed = deliver { scheduling.onPagesSaved(document, committed) } == true }
            finally {
                if (!completed) startup.getAndUpdate {
                    when (it) {
                        Startup.COMPLETE -> Startup.IDLE
                        Startup.RUNNING -> Startup.RUNNING_INVALIDATED
                        Startup.PENDING -> Startup.PENDING_INVALIDATED
                        else -> it
                    }
                }
            }
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

    private suspend fun <T : Any> deliver(wake: suspend () -> T): T? = try {
        wake()
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (_: Exception) {
        // Room work and the accepted page remain discoverable at startup. Never reinterpret a wake failure as failed save or recognition, or log a raw cause.
        null
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
