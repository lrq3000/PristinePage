package app.pristinepage

import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptionalSourceDisplayNameTest {
    @Test fun aBusyProviderCannotDelayTheImportOrQueueAnotherQuery() = runTest {
        val dispatch = ArrayDeque<Runnable>()
        val names = OptionalSourceDisplayName(Executor { dispatch.addLast(it) }, timeoutMillis = 100)
        val late = async { names.read { error("Expired request must not enter the provider") } }
        runCurrent()
        assertEquals(1, dispatch.size)
        advanceTimeBy(101)
        assertNull(late.await())
        assertNull(names.read { error("No new query until the prior request has actually returned") })
        assertEquals(1, dispatch.size)
        dispatch.removeFirst().run()
        val next = async { names.read { "Report.png" } }
        runCurrent()
        dispatch.removeFirst().run()
        assertEquals("Report.png", next.await())
    }
}
