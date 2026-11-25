package app.pristinepage.data.library

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Revoke a detached Room view before draining already-admitted reads. A captured port never acquires another lease. */
internal class LibraryWorkingCatalogAccess(private val confirmCurrent: suspend () -> Unit = {}) {
    private val monitor = Any()
    private val drained = CompletableDeferred<Unit>()
    private var accepting = true
    private var readers = 0

    suspend fun <T> read(block: suspend () -> T): T {
        synchronized(monitor) {
            requireOpen()
            readers++
        }
        try {
            confirmCurrent()
            val result = block()
            confirmCurrent()
            synchronized(monitor) { requireOpen() }
            return result
        } finally {
            synchronized(monitor) {
                readers--
                if (!accepting && readers == 0) drained.complete(Unit)
            }
        }
    }

    suspend fun retire(close: () -> Unit) {
        synchronized(monitor) {
            check(accepting)
            accepting = false
            if (readers == 0) drained.complete(Unit)
        }
        withContext(NonCancellable) {
            drained.await()
            close()
        }
    }

    private fun requireOpen() {
        if (!accepting) throw IOException("The working catalog is closed")
    }
}
