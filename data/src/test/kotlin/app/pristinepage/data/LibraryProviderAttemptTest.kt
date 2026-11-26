package app.pristinepage.data

import app.pristinepage.data.library.LibraryProviderAttempt
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class LibraryProviderAttemptTest {
    @Test fun owningOperationKeepsItsSlotThroughStructuredChildCloseAndRejectsTheEscapedOwner() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val issued = CompletableDeferred<LibraryProviderAttempt.Ownership>()
        val pending = launch {
            LibraryProviderAttempt.runOwned { owner ->
                owner.requireCurrent()
                issued.complete(owner)
                launch {
                    started.complete(Unit)
                    release.await()
                    owner.requireCurrent()
                }
            }
        }
        started.await()
        val retired = issued.await()
        try {
            assertFailsWith<IOException> { LibraryProviderAttempt.run { error("The child still owns the slot") } }
        } finally { release.complete(Unit) }
        pending.join()
        LibraryProviderAttempt.runOwned { next ->
            assertFailsWith<IOException> { retired.requireCurrent() }
            next.requireCurrent()
        }
    }

    @Test fun cancelingAnOwnedOperationRefusesFurtherStepsWithoutReleasingItsSlotEarly() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = launch {
            LibraryProviderAttempt.runOwned { owner ->
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    assertFailsWith<CancellationException> { owner.requireCurrent() }
                }
            }
        }
        entered.await()
        pending.cancel()
        try {
            assertFailsWith<IOException> { LibraryProviderAttempt.runOwned { error("The old operation is still unwinding") } }
        } finally { release.complete(Unit) }
        pending.join()
        LibraryProviderAttempt.runOwned { it.requireCurrent() }
    }

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
