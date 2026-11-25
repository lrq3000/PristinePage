package app.pristinepage.data

import app.pristinepage.data.library.LibraryProviderAttempt
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class LibraryProviderAttemptTest {
    @Test fun cancellationDoesNotReleaseTheSlotWhileAnUncooperativeProviderIsStillUnwinding() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = launch {
            LibraryProviderAttempt.run {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        pending.cancel()
        try {
            assertFailsWith<IOException> { LibraryProviderAttempt.run { error("A concurrent call entered the provider") } }
        } finally { release.complete(Unit) }
        pending.join()
        assertEquals("admitted", LibraryProviderAttempt.run { "admitted" })
    }
}
