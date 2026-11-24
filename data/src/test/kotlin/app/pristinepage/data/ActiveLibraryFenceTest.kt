package app.pristinepage.data

import app.pristinepage.data.library.ActiveLibraryFence
import app.pristinepage.data.library.ActiveLibraryFence.Identity
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import app.pristinepage.data.library.ActiveLibraryFence.State
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ActiveLibraryFenceTest {
    private val default = Selection(Location.Default, Identity(id(1), 1))
    private val selected = Selection(Location.Folder(id(3)), Identity(id(4), 2))

    @Test fun aDelayedJobCannotEnterAfterASelectionChangeEvenWhenItsLibraryWasReselected() = runTest {
        val fence = ActiveLibraryFence(default)
        assertTrue(fence.change(default.identity, selected) { true })
        val selectedAgain = selected.copy(identity = selected.identity.copy(activation = 3))
        assertTrue(fence.change(selected.identity, selectedAgain) { true })
        assertFalse(fence.change(selectedAgain.identity, default) { fail("A retired activation was reused"); true })
        assertNull(fence.use(selected.identity) { fail("Previous activation reached the new graph"); "stale" })
        assertEquals(selectedAgain, fence.use(selectedAgain.identity) { it })
    }

    @Test fun anInFlightConsumerPreventsCutoverAndCutoverExcludesNewConsumers() = runTest {
        val fence = ActiveLibraryFence(default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val user = async {
            fence.use(default.identity) {
                entered.complete(Unit)
                release.await()
                it
            }
        }
        entered.await()
        assertFalse(fence.change(default.identity, selected) { fail("Publication began while a graph was retained"); true })
        release.complete(Unit)
        assertEquals(default, user.await())
        assertTrue(fence.change(default.identity, selected) {
            assertNull(fence.use(default.identity) { fail("Old graph admitted during cutover"); Unit })
            assertNull(fence.use(selected.identity) { fail("New graph admitted before durable cutover"); Unit })
            true
        })
    }

    @Test fun definiteFailureKeepsTheOldGraphButUncertainPublicationRequiresReconciliation() = runTest {
        val fence = ActiveLibraryFence(default)
        assertFalse(fence.change(default.identity, selected) { false })
        assertEquals(State.Ready(default), fence.state())
        val error = runCatching { fence.change(default.identity, selected) { throw IOException("Indeterminate sync") } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(State.Uncertain(default, selected), fence.state())
        assertNull(fence.use(default.identity) { it })
        assertNull(fence.use(selected.identity) { it })
        assertFalse(fence.reconcile { default.copy(identity = default.identity.copy(activation = 9)) })
        assertTrue(fence.reconcile { selected })
        assertEquals(State.Ready(selected), fence.state())
    }

    @Test fun aMissingSelectedFolderNeverSubstitutesDefaultAndReconnectIsExact() = runTest {
        val fence = ActiveLibraryFence(default)
        assertTrue(fence.change(default.identity, selected) { true })
        assertTrue(fence.unavailable(selected.identity))
        assertEquals(State.Reconnect(selected), fence.state())
        assertNull(fence.use(default.identity) { it })
        assertNull(fence.use(selected.identity) { it })
        assertFalse(fence.reconcile { default })
        assertTrue(fence.reconcile { selected })
        assertEquals(State.Ready(selected), fence.state())
    }

    private fun id(value: Int) = UUID(0L, value.toLong()).toString()
}
