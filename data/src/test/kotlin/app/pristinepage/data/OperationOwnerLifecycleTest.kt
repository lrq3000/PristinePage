package app.pristinepage.data

import app.pristinepage.data.model.requireOpaqueId
import app.pristinepage.data.repository.*
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Real structured coroutine lifetimes and cleanup, not a clock-based fake owner. Cross-process death needs its own ownership proof; forgetting a registry entry is deliberately UNKNOWN. */
class OperationOwnerLifecycleTest {
    private fun registry(retained: Int = 4): OperationOwners {
        val type = try { Class.forName("app.pristinepage.data.repository.LocalOperationOwners") }
        catch (error: ClassNotFoundException) { throw AssertionError("Structured local operation owner lifecycle is absent", error) }
        return try { type.getConstructor(Int::class.javaPrimitiveType).newInstance(retained) as OperationOwners }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    @Test fun freshOwnerIsAliveWithinItsOperationAndEndedOnlyAfterReturn() = runTest {
        val owners = registry()
        assertEquals(LeaseOwnerState.UNKNOWN, owners.state(UUID.randomUUID().toString()))
        val id = owners.run { owner ->
            requireOpaqueId(owner)
            assertEquals(LeaseOwnerState.ALIVE, owners.state(owner))
            owner
        }
        assertEquals(LeaseOwnerState.ENDED, owners.state(id))
        owners.run { owner -> assertNotEquals(id, owner); assertEquals(LeaseOwnerState.ENDED, owners.state(id)) }
    }

    @Test fun cancellationCannotReportEndedWhileFinallyStillOwnsResources() = runTest {
        val owners = registry(); val started = CompletableDeferred<String>()
        val cleaning = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val task = launch {
            owners.run { owner ->
                started.complete(owner)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
            }
        }
        val owner = started.await(); task.cancel(); cleaning.await()
        assertEquals(LeaseOwnerState.ALIVE, owners.state(owner))
        assertFalse(task.isCompleted)
        release.complete(Unit); task.join()
        assertEquals(LeaseOwnerState.ENDED, owners.state(owner))
    }

    @Test fun aReturnedBlockCannotEndItsOwnerBeforeStructuredChildrenFinish() = runTest {
        val owners = registry(); val started = CompletableDeferred<String>(); val release = CompletableDeferred<Unit>()
        val task = launch {
            owners.run { owner ->
                launch { release.await() }
                started.complete(owner)
            }
        }
        val owner = started.await()
        assertEquals(LeaseOwnerState.ALIVE, owners.state(owner)); assertFalse(task.isCompleted)
        release.complete(Unit); task.join()
        assertEquals(LeaseOwnerState.ENDED, owners.state(owner))
    }

    @Test fun failedOperationEndsOnlyAfterCleanupAndPreservesTheOriginalFailure() = runTest {
        val owners = registry(); lateinit var ownerId: String
        val fault = IllegalStateException("synthetic operation fault")
        try {
            owners.run { owner ->
                ownerId = owner
                try { throw fault }
                finally { assertEquals(LeaseOwnerState.ALIVE, owners.state(owner)) }
            }
            fail("Operation fault was hidden")
        } catch (failure: IllegalStateException) {
            // Coroutine stack-trace recovery may copy an exception while retaining it as the cause; object identity is not the suspend-call failure contract.
            assertEquals(fault.javaClass, failure.javaClass)
            assertTrue(generateSequence(failure as Throwable) { it.cause }.any { it === fault })
        }
        assertEquals(LeaseOwnerState.ENDED, owners.state(ownerId))
    }

    @Test fun boundedEndedHistoryFallsBackToUnknownWithoutEvictingAliveOwners() = runTest {
        val owners = registry(2); val started = CompletableDeferred<String>(); val release = CompletableDeferred<Unit>()
        val task = launch { owners.run { owner -> started.complete(owner); release.await() } }
        val live = started.await(); val ended = List(3) { owners.run { owner -> owner } }
        assertEquals(LeaseOwnerState.UNKNOWN, owners.state(ended[0]))
        for (owner in ended.drop(1)) assertEquals(LeaseOwnerState.ENDED, owners.state(owner))
        assertEquals(LeaseOwnerState.ALIVE, owners.state(live))
        release.complete(Unit); task.join()
        assertEquals(LeaseOwnerState.ENDED, owners.state(live))
    }

    @Test fun aNewRegistryCannotInferTerminationFromAnAbsentIdentity() = runTest {
        val first = registry(); val second = registry()
        first.run { owner ->
            assertEquals(LeaseOwnerState.ALIVE, first.state(owner))
            assertEquals(LeaseOwnerState.UNKNOWN, second.state(owner))
        }
    }
}
