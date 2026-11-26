package app.pristinepage.data.library

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Revoke a detached Room view before draining already-admitted reads. A captured port never acquires another lease. */
internal class LibraryWorkingCatalogAccess(private val confirmCurrent: suspend () -> Unit = {}) {
    private val monitor = Any()
    private val drained = CompletableDeferred<Unit>()
    private val verifying = Mutex()
    private var accepting = true
    private var readers = 0
    private var lost: Exception? = null

    /** The enclosing operation checks again after Room closes; an already observed loss cannot be cleared by a later match. */
    suspend fun requireCurrent() = verifying.withLock {
        synchronized(monitor) { lost?.let { throw it } }
        try { confirmCurrent() }
        catch (canceled: CancellationException) { throw canceled }
        catch (busy: LibraryProviderBusy) { throw busy }
        catch (failure: Exception) { invalidate(failure) }
    }

    /** Recheck after preparing the file but before lending it, and before releasing any provisional result. */
    suspend fun requireLoanDelivery() {
        requireCurrent()
        synchronized(monitor) { requireOpen() }
    }

    suspend fun <T> read(block: suspend () -> T): T {
        synchronized(monitor) {
            requireOpen()
            readers++
        }
        try {
            requireCurrent()
            val result = block()
            requireLoanDelivery()
            return result
        } catch (failure: LibraryTreeUnavailable) {
            invalidate(failure)
        } finally {
            synchronized(monitor) {
                readers--
                if (!accepting && readers == 0) drained.complete(Unit)
            }
        }
    }

    private fun invalidate(failure: Exception): Nothing = synchronized(monitor) {
        throw (lost ?: failure.also { lost = it })
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
        lost?.let { throw it }
    }
}
