package app.pristinepage.data.repository

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One application-scoped instance must be shared by workers/recovery. ENDED is positive local completion evidence, never an elapsed lease or a canceled Job flag. Missing identities after restart/retention remain UNKNOWN until a separate process-ownership recovery protocol proves termination. */
class LocalOperationOwners @JvmOverloads constructor(private val retainedEnded: Int = 1024) : OperationOwners {
    init { require(retainedEnded in 1..65536) }
    private val states = HashMap<String, LeaseOwnerState>()
    private val ended = ArrayDeque<String>()

    override suspend fun <T> run(block: suspend CoroutineScope.(owner: String) -> T): T {
        currentCoroutineContext().ensureActive()
        val owner = register()
        try {
            // coroutineScope waits for structured children and their finally blocks even when cancellation has already been requested. Native work must stay in this ownership tree until it actually returns/releases its resources.
            return coroutineScope { block(owner) }
        } finally {
            finish(owner)
        }
    }

    @Synchronized override fun state(owner: String): LeaseOwnerState = states[owner] ?: LeaseOwnerState.UNKNOWN

    @Synchronized private fun register(): String {
        var owner: String
        do { owner = UUID.randomUUID().toString() } while (states.containsKey(owner))
        states[owner] = LeaseOwnerState.ALIVE
        return owner
    }

    @Synchronized private fun finish(owner: String) {
        check(states[owner] == LeaseOwnerState.ALIVE)
        states[owner] = LeaseOwnerState.ENDED
        ended.addLast(owner)
        // Forget only completed evidence. No still-running owner is evicted, and a forgotten owner becomes UNKNOWN (blocking takeover), not ENDED by absence.
        if (ended.size > retainedEnded) states.remove(ended.removeFirst())
    }
}
