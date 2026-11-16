package app.pristinepage.data

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual dispatcher/cancellation behavior without opening Android Room on the host. Native initialization failure/retry checks separately exercise the consuming application graph; no ANR duration is inferred. */
class ImportInitializationCleanupTest {
    private suspend fun close(resource: AutoCloseable) = suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
        val type = try { Class.forName("app.pristinepage.data.repository.ImportInitializationCleanup") }
        catch (_: ClassNotFoundException) { throw AssertionError("Off-thread failed-import initialization cleanup is absent") }
        try { type.getMethod("close", AutoCloseable::class.java, kotlin.coroutines.Continuation::class.java).invoke(type.getField("INSTANCE").get(null), resource, continuation) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    @Test fun failureCloseLeavesTheCallerThreadAndWaitsForTheResource() = runBlocking {
        val caller = Thread.currentThread(); val closedOn = AtomicReference<Thread>()
        close(AutoCloseable { closedOn.set(Thread.currentThread()) })
        assertNotNull(closedOn.get()); assertNotSame(caller, closedOn.get())
    }

    @Test fun alreadyCanceledCallerStillAwaitsExactlyOneOffThreadClose() = runBlocking {
        val caller = Thread.currentThread(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val closedOn = AtomicReference<Thread>(); val calls = AtomicInteger()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            close(AutoCloseable {
                closedOn.set(Thread.currentThread()); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)); calls.incrementAndGet()
            })
        }
        try {
            assertTrue(withContext(Dispatchers.Default) { entered.await(5, TimeUnit.SECONDS) })
            assertFalse("Cleanup was abandoned while its resource remained open", job.isCompleted)
            assertNotSame(caller, closedOn.get())
        } finally { release.countDown() }
        job.join(); assertEquals(1, calls.get())
    }

    @Test fun secondaryCloseFailureCannotReplaceTheInitiatingInitializationOutcome() = runBlocking {
        val calls = AtomicInteger()
        close(AutoCloseable { calls.incrementAndGet(); throw IOException("Synthetic close fault") })
        assertEquals(1, calls.get())
    }
}
