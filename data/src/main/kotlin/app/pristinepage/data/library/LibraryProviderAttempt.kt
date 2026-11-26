package app.pristinepage.data.library

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** Bound actual in-process provider work, not just callers still waiting for a response. Close all borrowed handles within the attempt. */
internal object LibraryProviderAttempt {
    private val lane = Mutex()

    /** An exact in-process slot, not a grant, a generation check, or permission to publish. */
    class Ownership internal constructor(private val key: Any, private val context: CoroutineContext) {
        fun requireCurrent() {
            if (!lane.holdsLock(key)) throw IOException("The library provider operation has ended")
            context.ensureActive()
        }
    }

    suspend fun <T> run(block: suspend () -> T): T = runOwned { block() }

    suspend fun <T> runOwned(block: suspend CoroutineScope.(Ownership) -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val key = Any()
        if (!lane.tryLock(key)) throw LibraryProviderBusy()
        try {
            // Observe cancellation in the issuing scope even if a provider is unwinding inside NonCancellable.
            return coroutineScope {
                val owner = Ownership(key, coroutineContext)
                owner.requireCurrent()
                block(owner)
            }.also { caller.ensureActive() }
        } finally {
            lane.unlock(key)
        }
    }
}

internal class LibraryProviderBusy : IOException("Another library folder operation is still in progress")
