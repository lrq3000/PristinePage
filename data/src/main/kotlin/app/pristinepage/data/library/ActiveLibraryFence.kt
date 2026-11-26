package app.pristinepage.data.library

import app.pristinepage.data.model.requireOpaqueId
import app.pristinepage.core.pipeline.requireSha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Process-local admission for an exact activation, not a durable pointer or authority to open, publish or retire a root. */
internal class ActiveLibraryFence(initial: Selection) {
    sealed interface Location {
        data object Default : Location
        data class Folder(val grantId: String, val admittedGenerationId: String, val verificationSha256: String? = null) : Location {
            init {
                requireOpaqueId(grantId)
                requireOpaqueId(admittedGenerationId)
                verificationSha256?.let(::requireSha256)
            }
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
    private val revokedDuringProof = HashSet<Identity>(2)

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

    /** False means the exact old selection remains authoritative. An exception is uncertain and requires durable reconciliation. */
    suspend fun change(
        expected: Identity,
        next: Selection,
        prepare: suspend CoroutineScope.() -> Boolean = { true },
        publishVerified: suspend CoroutineScope.() -> Boolean,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val before = synchronized(monitor) {
            val ready = status as? State.Ready ?: return false
            if (consumers != 0 || ready.selection.identity != expected || next.identity.activation != expected.activation + 1) return false
            revokedDuringProof.clear()
            status = State.Switching(ready.selection, next)
            ready.selection
        }
        var publicationStarted = false
        fun disarm(selection: Selection) {
            synchronized(monitor) {
                status = if (selection.identity in revokedDuringProof) State.Reconnect(selection) else State.Ready(selection)
                revokedDuringProof.clear()
            }
        }
        try {
            val prepared = coroutineScope { ensureActive(); prepare() }
            currentCoroutineContext().ensureActive()
            // This is the publication boundary: a later revocation is reconciled with the result, not mistaken for a refused write.
            publicationStarted = synchronized(monitor) { prepared && next.identity !in revokedDuringProof }
            val published = publicationStarted && coroutineScope { ensureActive(); publishVerified() }
            disarm(if (published) next else before)
            return published
        } catch (failure: Throwable) {
            if (!publicationStarted) disarm(before)
            else synchronized(monitor) {
                status = State.Uncertain(before, next)
                revokedDuringProof.clear()
            }
            throw failure
        }
    }

    /** Revocation closes new admissions immediately. Already running operations must still observe actual provider loss. */
    fun unavailable(expected: Identity): Boolean = synchronized(monitor) {
        fun matches(selection: Selection) = selection.identity == expected && selection.location is Location.Folder
        when (val current = status) {
            is State.Ready -> if (matches(current.selection)) status = State.Reconnect(current.selection) else return false
            is State.Switching -> if (matches(current.before) || matches(current.after)) revokedDuringProof += expected else return false
            is State.Reconnect -> if (matches(current.selection)) {
                if (reconciling) revokedDuringProof += expected
            } else return false
            is State.Uncertain -> if (matches(current.before) || matches(current.after)) {
                if (reconciling) revokedDuringProof += expected
            } else return false
        }
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
            revokedDuringProof.clear()
            reconciling = true
            permitted
        }
        val selected = try {
            coroutineScope { ensureActive(); readVerified() }
        } catch (failure: Throwable) {
            synchronized(monitor) { reconciling = false }
            throw failure
        }
        return synchronized(monitor) {
            reconciling = false
            if (selected == null || selected !in allowed || selected.identity in revokedDuringProof) return@synchronized false
            status = State.Ready(selected)
            true
        }
    }
}
