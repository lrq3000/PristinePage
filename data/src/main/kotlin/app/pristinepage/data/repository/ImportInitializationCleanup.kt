package app.pristinepage.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Failure-only disposal of an unpublished store/database, not file cleanup. Cancellation cannot abandon an acquired resource; closing must leave even a Main caller and must not mask the initiating failure that the caller rethrows. */
object ImportInitializationCleanup {
    suspend fun close(resource: AutoCloseable?) {
        try { withContext(NonCancellable + Dispatchers.IO) { resource?.close() } }
        catch (_: Exception) { /* Preserve the initiating initialization failure/cancellation, including result-dispatch cancellation after close. */ }
    }
}
