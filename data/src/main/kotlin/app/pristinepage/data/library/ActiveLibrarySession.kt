package app.pristinepage.data.library

import app.pristinepage.data.library.ActiveLibraryFence.Identity
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import app.pristinepage.data.library.ActiveLibraryFence.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Pairs one verified graph with the activation fence. The graph is borrowed only inside a structured operation; this class never deletes a library. */
internal class ActiveLibrarySession<G : AutoCloseable>(initial: Selection, graph: G) {
    private data class Bound<G>(val selection: Selection, val graph: G)

    private val fence = ActiveLibraryFence(initial)
    private val transition = Mutex()
    @Volatile private var active = Bound(initial, graph)
    @Volatile private var unclosed: G? = null

    fun state(): State = fence.state()
    fun unavailable(identity: Identity): Boolean = fence.unavailable(identity)
    fun hasPendingGraphClose(): Boolean = unclosed != null

    /** Return only detached data. No caller may leak the graph, lazy graph-backed data or use by a child outside this structured scope. */
    suspend fun <T : Any> use(expected: Identity, operation: suspend CoroutineScope.(G) -> T): T? =
        fence.use(expected) { selection ->
            val bound = active
            check(bound.selection == selection)
            operation(bound.graph).also { check(it !== bound.graph) }
        }

    suspend fun <T : Any> exclusiveCutover(expected: Identity, operation: suspend () -> T): T? =
        fence.exclusiveCutover(expected, operation)

    /** Open an independent candidate before publishing. Hand off the graph synchronously at allocation, before dispatcher return can lose it to cancellation, and only return normally once verified. */
    suspend fun change(expected: Identity, next: Selection, openVerified: suspend (Selection, (G) -> Unit) -> Unit,
        publishVerified: suspend (Selection, Selection) -> Boolean,
    ): Boolean = exchange { previous, stage ->
        var candidate: Bound<G>? = null
        fence.change(expected, next, prepare = {
            openVerified(next) { candidate = stage(next, it) }
            candidate != null
        }) {
            if (!publishVerified(previous.selection, next)) return@change false
            active = checkNotNull(candidate)
            true
        }
    }

    /** The pointer and candidate graph must both be verified under the same authority before admission resumes. */
    suspend fun reconcile(readVerified: suspend () -> Selection?, openVerified: suspend (Selection, (G) -> Unit) -> Unit): Boolean =
        exchange { _, stage ->
            fence.reconcile {
                val selection = readVerified() ?: return@reconcile null
                val permitted = when (val state = fence.state()) {
                    is State.Uncertain -> selection == state.before || selection == state.after
                    is State.Reconnect -> selection == state.selection
                    else -> false
                }
                if (!permitted) return@reconcile null
                var candidate: Bound<G>? = null
                openVerified(selection) { candidate = stage(selection, it) }
                currentCoroutineContext().ensureActive()
                active = candidate ?: return@reconcile null
                selection
            }
        }

    private suspend fun exchange(run: suspend (Bound<G>, (Selection, G) -> Bound<G>) -> Boolean): Boolean {
        if (!transition.tryLock()) return false
        try {
            // Unknown graph teardown is not permission to compound ownership by retiring another graph.
            if (unclosed != null) return false
            val previous = active
            var candidate: Bound<G>? = null
            var applied = false
            try {
                applied = run(previous) { selection, graph ->
                    check(candidate == null && graph !== previous.graph)
                    Bound(selection, graph).also { candidate = it }
                }
                return applied
            } finally {
                if (applied) closeGraph(previous.graph)
                else {
                    // The fence is still old, disconnected or uncertain; never leave a staged graph behind its admission token.
                    active = previous
                    candidate?.let { closeGraph(it.graph) }
                }
            }
        } finally {
            transition.unlock()
        }
    }

    private fun closeGraph(graph: G) {
        try { graph.close() }
        catch (_: Exception) {
            // Teardown cannot roll back the already durable selection. Retain the exact graph and expose cleanup refusal separately.
            unclosed = graph
        }
    }
}

/** Structured operation boundary for the initialized default graph. Opening and validating the durable default selection remain the caller's responsibility. */
class DefaultLibraryOperations<G : AutoCloseable>(activation: LibraryActivation, private val graph: G) {
    private val session = ActiveLibrarySession(Selection(ActiveLibraryFence.Location.Default, activation.identity()), graph)
    @Volatile private var retired = false
    @Volatile private var closePending = false

    fun isRetired(): Boolean = retired
    fun hasPendingGraphClose(): Boolean = closePending

    /** Return detached data only; the graph and its ports are lent for this structured callback, not returned to another owner. */
    suspend fun <T : Any> use(
        expected: LibraryActivation, operation: suspend CoroutineScope.(G) -> T,
    ): T? = if (retired) null else session.use(expected.identity()) { borrowed ->
        Value(if (retired) null else operation(borrowed).also { check(it !== borrowed) })
    }?.value

    /** Refuse a busy cutover without invoking its mutation. No new default borrow may cross the confirmed switch. */
    suspend fun <T : Any> exclusiveCutover(expected: LibraryActivation, operation: suspend () -> T): T? =
        if (retired) null else session.exclusiveCutover(expected.identity()) {
            Value(if (retired) null else operation())
        }?.value

    /** After the pointer leaves this graph, close it before releasing admission even when publication acknowledgment was canceled. */
    suspend fun <T : Any> cutoverAndRetire(
        expected: LibraryActivation, hasLeft: suspend () -> Boolean, operation: suspend () -> T,
    ): T? = exclusiveCutover(expected) {
        var primary: Throwable? = null
        try { operation() }
        catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val proofFailure = try {
                    if (hasLeft()) retired = true
                    null
                } catch (failure: Throwable) {
                    // Unknown durable state is not permission to keep lending the old graph.
                    retired = true
                    failure
                }
                var terminal = proofFailure
                if (retired) {
                    closePending = true
                    try { graph.close(); closePending = false }
                    catch (failure: Throwable) {
                        if (terminal == null) terminal = failure
                        else if (terminal !== failure) terminal.addSuppressed(failure)
                    }
                }
                terminal?.let { failure ->
                    val original = primary
                    if (original == null) throw failure
                    if (original !== failure) original.addSuppressed(failure)
                }
            }
        }
    }

    private data class Value<T>(val value: T?)
    private fun LibraryActivation.identity() = Identity(libraryId, number)
}
