package app.pristinepage.data

import app.pristinepage.data.library.ActiveLibraryFence.Identity
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import app.pristinepage.data.library.ActiveLibrarySession
import app.pristinepage.data.library.DefaultLibraryOperations
import app.pristinepage.data.library.LibraryActivation
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ActiveLibrarySessionTest {
    private val default = Selection(Location.Default, Identity(id(1), 1))
    private val selected = Selection(Location.Folder(id(2), id(3)), Identity(id(4), 2))

    @Test fun switchOpensTheCandidateBeforePublicationAndKeepsTheOldGraphOnDefiniteRefusal() = runTest {
        val old = Graph("old")
        val candidate = Graph("new")
        val session = ActiveLibrarySession(default, old)
        assertFalse(session.change(default.identity, selected, { _, own -> own(candidate) }) { before, next ->
            assertEquals(default, before)
            assertEquals(selected, next)
            assertFalse(old.closed)
            assertFalse(candidate.closed)
            false
        })
        assertEquals(1, candidate.closes)
        assertFalse(old.closed)
        assertEquals("old", session.use(default.identity) { it.name })
    }

    @Test fun cutoverWaitsForStructuredChildrenAndNeverHandsADelayedTokenTheNewGraph() = runTest {
        val old = Graph("old")
        val candidate = Graph("new")
        val session = ActiveLibrarySession(default, old)
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldUse = async {
            session.use(default.identity) { graph ->
                launch { ready.complete(Unit); release.await(); assertFalse(graph.closed) }
                graph.name
            }
        }
        ready.await()
        assertFalse(session.change(default.identity, selected, { _, _ -> error("Candidate opened during an old use") }) { _, _ -> true })
        release.complete(Unit)
        assertEquals("old", oldUse.await())
        assertTrue(session.change(default.identity, selected, { _, own -> own(candidate) }) { _, _ -> true })
        assertNull(session.use(default.identity) { fail("Old token opened the new library"); it.name })
        assertEquals("new", session.use(selected.identity) { it.name })
        assertEquals(1, old.closes)
    }

    @Test fun folderCutoverCannotStartUntilTheDefaultGraphDrainsAndBlocksNewBorrowsDuringTheSwap() = runTest {
        val activation = LibraryActivation(default.identity.libraryId, default.identity.activation)
        val graph = Graph("default")
        val owner = DefaultLibraryOperations(activation, graph)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val borrow = async { owner.use(activation) { entered.complete(Unit); release.await(); it.name } }
        entered.await()
        assertNull(owner.exclusiveCutover(activation) { error("An old borrower still owns the graph") })
        release.complete(Unit)
        assertEquals("default", borrow.await())
        assertEquals("verified", owner.exclusiveCutover(activation) {
            assertNull(owner.use(activation) { error("An old token reopened the graph during cutover") })
            assertNull(owner.exclusiveCutover(activation) { error("Two switch operations entered") })
            "verified"
        })
        assertEquals("default", owner.use(activation) { it.name })
    }

    @Test fun uncertainPointerNeverExposesEitherGraphBeforeAnExactReopen() = runTest {
        val old = Graph("old")
        val candidate = Graph("candidate")
        val session = ActiveLibrarySession(default, old)
        val error = runCatching { session.change(default.identity, selected, { _, own -> own(candidate) }) { _, _ ->
            throw IOException("Synthetic failed durability acknowledgment")
        } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertNull(session.use(default.identity) { it.name })
        assertNull(session.use(selected.identity) { it.name })
        assertEquals(1, candidate.closes)
        val reopened = Graph("reopened")
        assertTrue(session.reconcile({ selected }) { _, own -> own(reopened) })
        assertEquals("reopened", session.use(selected.identity) { it.name })
    }

    @Test fun reconnectCannotSubstituteDefaultOrOpenAfterASecondFailure() = runTest {
        val session = ActiveLibrarySession(selected, Graph("initial"))
        assertTrue(session.unavailable(selected.identity))
        assertFalse(session.reconcile({ default }) { _, _ -> fail("Reconnect tried to open default") })
        val candidate = Graph("revoked")
        assertFalse(session.reconcile({ selected }) { _, own ->
            assertTrue(session.unavailable(selected.identity))
            own(candidate)
        })
        assertTrue(candidate.closed)
        assertNull(session.use(selected.identity) { it.name })
    }

    @Test fun cancellationAfterCandidateOwnershipDoesNotLeakAGraphOrPublishAPointer() = runTest {
        for (reconnect in listOf(false, true)) {
            val start = if (reconnect) selected else default
            val previous = Graph("previous")
            val candidate = Graph("canceled")
            val session = ActiveLibrarySession(start, previous)
            if (reconnect) assertTrue(session.unavailable(selected.identity))
            val owned = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val open: suspend (Selection, (Graph) -> Unit) -> Unit = { _, own ->
                withContext(NonCancellable) {
                    own(candidate)
                    owned.complete(Unit)
                    release.await()
                }
            }
            val attempt = async {
                if (reconnect) session.reconcile({ selected }, open)
                else session.change(default.identity, selected, open) { _, _ -> error("Canceled candidate reached pointer publication") }
            }
            owned.await()
            attempt.cancel()
            release.complete(Unit)
            attempt.cancelAndJoin()
            assertEquals(1, candidate.closes)
            assertEquals(0, previous.closes)
            if (reconnect) assertNull(session.use(selected.identity) { it.name })
            else assertEquals("previous", session.use(default.identity) { it.name })
        }
    }

    @Test fun aCandidateRevokedBeforePublicationLeavesTheOldGraphAuthoritative() = runTest {
        val candidate = Graph("revoked")
        val session = ActiveLibrarySession(default, Graph("old"))
        assertFalse(session.change(default.identity, selected, { _, own ->
            own(candidate)
            assertTrue(session.unavailable(selected.identity))
        }) { _, _ -> error("Already revoked candidate reached pointer publication") })
        assertEquals(1, candidate.closes)
        assertEquals("old", session.use(default.identity) { it.name })
    }

    @Test fun teardownFailureDoesNotRollbackAnAlreadyPublishedSelection() = runTest {
        val old = Graph("old", false)
        val candidate = Graph("new")
        val session = ActiveLibrarySession(default, old)
        assertTrue(session.change(default.identity, selected, { _, own -> own(candidate) }) { _, _ -> true })
        assertEquals(1, old.closes)
        assertTrue(session.hasPendingGraphClose())
        assertEquals("new", session.use(selected.identity) { it.name })
        assertFalse(session.change(selected.identity, default.copy(identity = default.identity.copy(activation = 3)),
            { _, _ -> error("Unresolved close must fence another replacement") }) { _, _ -> true })
    }

    private class Graph(val name: String, private val canClose: Boolean = true) : AutoCloseable {
        var closes = 0
            private set
        val closed get() = closes != 0
        override fun close() {
            closes++
            if (!canClose) throw IOException("Synthetic teardown failure")
        }
    }

    private fun id(value: Int) = UUID(0, value.toLong()).toString()
}
