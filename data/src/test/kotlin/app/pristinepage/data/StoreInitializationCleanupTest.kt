package app.pristinepage.data

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Actual cleanup dispatcher/lifetime policy with tiny owned resources; Android Room failure/retry composition is exercised separately. No fake Room result or physical IO/performance claim. */
class StoreInitializationCleanupTest {
    private suspend fun close(resource: AutoCloseable) {
        val type = try { Class.forName("app.pristinepage.data.repository.ImportInitializationCleanup") }
        catch (_: ClassNotFoundException) { throw AssertionError("Off-caller failure cleanup is absent") }
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            try { type.getMethod("close", AutoCloseable::class.java, kotlin.coroutines.Continuation::class.java).invoke(type.getField("INSTANCE").get(null), resource, continuation) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }

    @Test fun concurrentFailuresCloseTheirOwnResourcesExactlyOnce() = runBlocking {
        val caller = Thread.currentThread(); val calls = AtomicIntegerArray(12)
        val jobs = List(12) { index -> launch { close(AutoCloseable { assertNotSame(caller, Thread.currentThread()); calls.incrementAndGet(index) }) } }
        jobs.forEach { it.join() }
        for (index in 0..11) assertEquals(1, calls.get(index))
    }

    @Test fun cancellationAfterCloseStartsCannotAbandonTheOwnedResource() = runBlocking {
        val caller = Thread.currentThread(); val closed = AtomicReference<Thread>()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val child = launch {
            close(AutoCloseable { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); closed.set(Thread.currentThread()) })
        }
        try {
            assertTrue(withContext(Dispatchers.Default) { entered.await(5, TimeUnit.SECONDS) })
            child.cancel(); assertFalse(child.isCompleted)
        } finally { release.countDown() }
        child.join()
        assertTrue(child.isCancelled); assertNotNull(closed.get()); assertNotSame(caller, closed.get())
    }

    @Test fun closeFailureCannotMaskTheOriginalInitializationFailure() = runBlocking {
        val original = IllegalStateException("synthetic initialization failure")
        var result: Throwable? = null
        try {
            try { throw original }
            catch (failure: Throwable) { close(AutoCloseable { throw IOException("synthetic close failure") }); throw failure }
        } catch (failure: Throwable) { result = failure }
        assertSame(original, result)
    }
}
