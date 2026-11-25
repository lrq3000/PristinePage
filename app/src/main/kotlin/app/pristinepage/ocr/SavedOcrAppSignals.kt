package app.pristinepage.ocr

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.data.library.LibraryActivation
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
    private data class Pass(val activation: LibraryActivation, val state: Startup)
    private val startup = AtomicReference<Pass?>(null)
    private val manualRerun = MutableStateFlow(false)
    val rerunInFlight = manualRerun.asStateFlow()

    /** Completion describes this exact foreground pass, not recognition completion for every page. */
    fun isStartupComplete(activation: LibraryActivation): Boolean = startup.get() == Pass(activation, Startup.COMPLETE)

    fun onStartup(activation: LibraryActivation) {
        // A foreground event during a refused/incomplete pass must not wait for a third foreground transition.
        val previous = startup.getAndUpdate { current ->
            if (current == null || activation.number > current.activation.number) Pass(activation, Startup.RUNNING)
            else if (current.activation != activation) current
            else when (current.state) {
                Startup.IDLE -> current.copy(state = Startup.RUNNING)
                Startup.RUNNING -> current.copy(state = Startup.PENDING)
                Startup.RUNNING_INVALIDATED -> current.copy(state = Startup.PENDING_INVALIDATED)
                else -> current
            }
        }
        if (previous != null && activation.number <= previous.activation.number &&
            (previous.activation != activation || previous.state != Startup.IDLE)) return
        processScope.launch {
            try {
                while (startup.get()?.activation == activation) {
                    val completed = try { deliver { scheduling.onStartup(activation) } == true }
                    catch (_: CancellationException) {
                        // A canceled attempt is incomplete while the process job remains live. Honor only the already coalesced visit, never poll.
                        currentCoroutineContext().ensureActive()
                        false
                    }
                    // Invalidation and pass completion are one transition: a concurrent failed save may be behind this pass's cursor.
                    val finished = startup.getAndUpdate { pass ->
                        if (pass?.activation == activation) pass.copy(state = pass.state.afterPass(completed)) else pass
                    }
                    if (finished?.activation != activation || finished.state.afterPass(completed) != Startup.RUNNING) break
                }
            } catch (canceled: CancellationException) {
                startup.getAndUpdate { pass -> if (pass?.activation == activation) pass.copy(state = Startup.IDLE) else pass }
                throw canceled
            }
        }
    }

    fun onPagesSaved(activation: LibraryActivation, document: DocumentId, pages: List<PageId>) {
        val committed = pages.toList()
        if (committed.isEmpty()) return
        processScope.launch {
            var completed = false
            try { completed = deliver { scheduling.onPagesSaved(activation, document, committed) } == true }
            finally {
                if (!completed) requireStartupReconciliation(activation)
            }
        }
    }

    /** A lost mutation receipt gives no page hint, but may invalidate a completed pass in its issuing library. */
    fun requireStartupReconciliation(activation: LibraryActivation) {
        startup.getAndUpdate { pass ->
            if (pass?.activation != activation) return@getAndUpdate pass
            pass.copy(state = when (pass.state) {
                Startup.COMPLETE -> Startup.IDLE
                Startup.RUNNING -> Startup.RUNNING_INVALIDATED
                Startup.PENDING -> Startup.PENDING_INVALIDATED
                else -> pass.state
            })
        }
    }

    /** A deliberate command may finish after its dialog closes. Only the observation-bound Room job and its opaque wake can survive the dialog. */
    suspend fun onRerun(
        qualify: suspend () -> OcrConfiguration?,
        activation: LibraryActivation,
        commit: suspend (OcrConfiguration) -> SavedOcrWork.Target,
    ): SavedOcrScheduling.RerunOutcome {
        currentCoroutineContext().ensureActive()
        return processScope.async {
            // Keep the gate with the command even when the original UI waiter is canceled or a new panel is opened.
            if (!manualRerun.compareAndSet(expect = false, update = true)) return@async SavedOcrScheduling.RerunOutcome.BUSY
            try { scheduling.requestRerun(qualify, activation, commit) } finally { manualRerun.value = false }
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
