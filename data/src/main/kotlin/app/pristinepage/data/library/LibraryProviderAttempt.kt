package app.pristinepage.data.library

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** Bound actual in-process provider work, not just callers still waiting for a response. Close all borrowed handles within the attempt. */
internal object LibraryProviderAttempt {
    private val lane = Mutex()

    suspend fun <T> run(block: suspend () -> T): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (!lane.tryLock()) throw LibraryProviderBusy()
        try {
            context.ensureActive()
            return block().also { context.ensureActive() }
        } finally {
            lane.unlock()
        }
    }
}

internal class LibraryProviderBusy : IOException("Another library folder operation is still in progress")
