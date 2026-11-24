package app.pristinepage

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Waiting for optional provider metadata must not block ingest. A provider that ignores cancellation can occupy at most one read and cannot queue another. */
internal class OptionalSourceDisplayName(private val executor: Executor, private val timeoutMillis: Long) {
    private val reading = AtomicBoolean()

    suspend fun read(query: () -> String?): String? = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            if (!reading.compareAndSet(false, true)) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            try {
                executor.execute {
                    val result = try {
                        if (continuation.isActive) query() else null
                    } catch (canceled: CancellationException) {
                        continuation.cancel(canceled)
                        return@execute
                    } catch (_: Exception) {
                        // Optional metadata cannot turn admitted scan bytes into an import failure.
                        null
                    } finally {
                        reading.set(false)
                    }
                    continuation.resume(result)
                }
            } catch (canceled: CancellationException) {
                reading.set(false)
                continuation.cancel(canceled)
            } catch (_: Exception) {
                reading.set(false)
                continuation.resume(null)
            }
        }
    }
}
