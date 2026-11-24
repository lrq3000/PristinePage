package app.pristinepage.data.library

import app.pristinepage.data.model.requireOpaqueId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Process-local admission for an exact activation, not a durable pointer or authority to open, publish or retire a root. */
internal class ActiveLibraryFence(initial: Selection) {
    sealed interface Location {
        data object Default : Location
        data class Folder(val grantId: String) : Location {
            init { requireOpaqueId(grantId) }
        }
    }

    data class Identity(val libraryId: String, val activation: Long) {
        init {
            requireOpaqueId(libraryId)
            require(activation > 0)
        }
    }

    data class Selection(val location: Location, val identity: Identity)

    sealed interface State {
        data class Ready(val selection: Selection) : State
        data class Reconnect(val selection: Selection) : State
        data class Switching(val before: Selection, val after: Selection) : State
        data class Uncertain(val before: Selection, val after: Selection) : State
    }

    private val monitor = Any()
    private var status: State = State.Ready(initial)
    private var consumers = 0
    private var reconciling = false

    fun state(): State = synchronized(monitor) { status }

    /** Hold admission until the callback and its structured children have ended; a copied token alone cannot retain a graph. */
    suspend fun <T : Any> use(expected: Identity, block: suspend CoroutineScope.(Selection) -> T): T? {
        currentCoroutineContext().ensureActive()
        val selected = synchronized(monitor) {
            val ready = status as? State.Ready ?: return null
            if (ready.selection.identity != expected) return null
            consumers++
            ready.selection
        }
        try {
            return coroutineScope { ensureActive(); block(selected) }
        } finally {
            synchronized(monitor) { consumers-- }
        }
    }

    /** A false callback result proves no publication; any exception after exclusive admission requires durable reconciliation. */
    suspend fun change(expected: Identity, next: Selection, publishVerified: suspend CoroutineScope.() -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val before = synchronized(monitor) {
            val ready = status as? State.Ready ?: return false
            if (consumers != 0 || ready.selection.identity != expected || next.identity.activation != expected.activation + 1) return false
            status = State.Switching(ready.selection, next)
            ready.selection
        }
        try {
            val published = coroutineScope { ensureActive(); publishVerified() }
            synchronized(monitor) { status = State.Ready(if (published) next else before) }
            return published
        } catch (failure: Throwable) {
            synchronized(monitor) { status = State.Uncertain(before, next) }
            throw failure
        }
    }

    /** Revocation closes new admissions immediately. Already running operations must still observe actual provider loss. */
    fun unavailable(expected: Identity): Boolean = synchronized(monitor) {
        val ready = status as? State.Ready ?: return false
        if (ready.selection.identity != expected || ready.selection.location !is Location.Folder) return false
        status = State.Reconnect(ready.selection)
        true
    }

    /** Reopen and confirm an exact permitted pointer and its graph before admitting any consumer again. Null preserves the unavailable state. */
    suspend fun reconcile(readVerified: suspend CoroutineScope.() -> Selection?): Boolean {
        currentCoroutineContext().ensureActive()
        val allowed = synchronized(monitor) {
            if (consumers != 0 || reconciling) return false
            val permitted = when (val current = status) {
                is State.Reconnect -> setOf(current.selection)
                is State.Uncertain -> setOf(current.before, current.after)
                else -> return false
            }
            reconciling = true
            permitted
        }
        try {
            val selected = coroutineScope { ensureActive(); readVerified() }
            if (selected == null || selected !in allowed) return false
            synchronized(monitor) { status = State.Ready(selected) }
            return true
        } finally {
            synchronized(monitor) { reconciling = false }
        }
    }
}
