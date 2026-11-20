package app.pristinepage

import app.pristinepage.capture.withSkipRecovery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AtelierSkipRecoveryTest {
    @Test fun aLaterSkipFailureReconcilesTheFirstPrivateRevisionBeforeReturningTheError() = runTest {
        var displayed = 4L
        var stored = 4L
        val expected = IllegalStateException("Second page failed")
        val failure = runCatching {
            withSkipRecovery(recover = { displayed = stored }) {
                stored++
                throw expected
            }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(5L, displayed)
    }

    @Test fun failedRecoveryClosesTheStaleEditorAndKeepsTheOriginalFailure() = runTest {
        var hasEditor = true
        val first = IllegalArgumentException("Checkpoint failed")
        val failure = runCatching {
            withSkipRecovery(recover = {
                try { error("Reload failed") } finally { hasEditor = false }
            }) { throw first }
        }.exceptionOrNull()
        assertSame(first, failure)
        assertFalse(hasEditor)
    }
}
