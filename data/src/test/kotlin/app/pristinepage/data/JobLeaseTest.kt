package app.pristinepage.data

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.JobId
import app.pristinepage.data.repository.*
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Fences real job-row state transitions; the Android metadata transaction alone is substituted. This is not worker scheduling, file completion or process-death recovery evidence. */
class JobLeaseTest {
    private class Fixture : JobLeaseQueries {
        val catalog = MemoryMetadata()
        val id = JobId(UUID.randomUUID().toString())
        var row = JobRow(id.value, "RECONCILE", "reconcile-v1:${id.value}", "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", null, null, byteArrayOf(1, 2, 3, 4), "ADMITTED", 0, null, null, 0, null, null, 0, 0)
        var now = 1000L
        var liveness = LeaseOwnerState.UNKNOWN
        var failWrite = false
        val metadata = object : MetadataAccess {
            override val queries: CatalogQueries get() = catalog
            override fun <T> transaction(action: () -> T): T {
                val before = row
                return try { catalog.transaction(action) } catch (error: Throwable) { row = before; throw error }
            }
        }
        val commands: JobLeaseCommands
        init {
            val type = try { Class.forName("app.pristinepage.data.repository.RoomJobLeases") }
            catch (error: ClassNotFoundException) { throw AssertionError("Fenced persistent job lease commands are absent", error) }
            val gate = LeaseOwnerLiveness { check(catalog.inTransaction); liveness }
            commands = try { type.constructors.single { it.parameterCount == 5 }.newInstance(metadata, this, gate, { now }, Dispatchers.Unconfined) as JobLeaseCommands }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        override fun job(id: String) = row.takeIf { it.jobId == id }
        override fun updateLease(row: JobRow, expectedGeneration: Long): Int {
            check(catalog.inTransaction)
            if (failWrite) throw IOException("synthetic private database failure")
            if (this.row.jobId != row.jobId || this.row.leaseGeneration != expectedGeneration) return 0
            this.row = row; return 1
        }
        fun owner() = UUID.randomUUID().toString()
    }

    @Test fun onlyOneRunnerGetsAnAdmittedJobAndGenerationAdvances() = runTest {
        val f = Fixture(); val owner = f.owner()
        val first = f.commands.acquireAdmitted(f.id, owner, 100) as AcquireLeaseResult.Granted
        assertEquals(1L, first.lease.generation); assertEquals(1L, f.row.attempt)
        assertEquals("RUNNING", f.row.state); assertEquals(1100L, f.row.leaseUntil)
        assertTrue(f.commands.acquireAdmitted(f.id, owner, 100) is AcquireLeaseResult.Busy)
        assertTrue(f.commands.acquireAdmitted(f.id, f.owner(), 100) is AcquireLeaseResult.Busy)
        assertEquals(1L, f.row.leaseGeneration)
    }

    @Test fun expiredClockCannotReassignALiveOrUnknownOwner() = runTest {
        for (state in listOf(LeaseOwnerState.ALIVE, LeaseOwnerState.UNKNOWN)) {
            val f = Fixture(); f.commands.acquireAdmitted(f.id, f.owner(), 100)
            f.now = 1101; f.liveness = state
            assertTrue(f.commands.acquireAdmitted(f.id, f.owner(), 100) is AcquireLeaseResult.Busy)
            assertEquals(1L, f.row.leaseGeneration); assertEquals(1L, f.row.attempt)
        }
    }

    @Test fun expiredEndedRunningOwnerCannotReacquireItsOwnAttemptIdentity() = runTest {
        rejectEndedDuplicateOwner(checkpoint = false, now = 1100)
    }

    @Test fun expiredEndedCheckpointedOwnerCannotReacquireItsOwnAttemptIdentity() = runTest {
        rejectEndedDuplicateOwner(checkpoint = true, now = 1101)
    }

    private suspend fun rejectEndedDuplicateOwner(checkpoint: Boolean, now: Long) {
        val f = Fixture(); val owner = f.owner()
        val lease = (f.commands.acquireAdmitted(f.id, owner, 100) as AcquireLeaseResult.Granted).lease
        if (checkpoint) assertTrue(f.commands.checkpoint(lease, byteArrayOf(9, 8)))
        val before = f.row
        f.now = now; f.liveness = LeaseOwnerState.ENDED
        assertTrue("A new execution attempt requires a fresh owner identity", f.commands.acquireAdmitted(f.id, owner, 100) is AcquireLeaseResult.Busy)
        assertEquals(before, f.row)
    }

    @Test fun confirmedEndedOwnerCanBeReassignedButOldRunnerCannotCheckpointOrComplete() = runTest {
        val f = Fixture()
        val first = (f.commands.acquireAdmitted(f.id, f.owner(), 100) as AcquireLeaseResult.Granted).lease
        f.now = 1101; f.liveness = LeaseOwnerState.ENDED
        val second = (f.commands.acquireAdmitted(f.id, f.owner(), 100) as AcquireLeaseResult.Granted).lease
        assertEquals(2L, second.generation)
        assertFalse(f.commands.checkpoint(first, byteArrayOf(9))); assertFalse(f.commands.complete(first))
        assertEquals(second.owner, f.row.leaseOwner); assertEquals("RUNNING", f.row.state)
        assertTrue(f.commands.complete(second)); assertEquals("SUCCEEDED", f.row.state)
        assertFalse(f.commands.complete(second))
    }

    @Test fun checkpointOwnsItsBytesAndNeverChangesCanonicalJobInput() = runTest {
        val f = Fixture(); val before = f.row.input.copyOf()
        val lease = (f.commands.acquireAdmitted(f.id, f.owner(), 100) as AcquireLeaseResult.Granted).lease
        val progress = byteArrayOf(9, 8)
        assertTrue(f.commands.checkpoint(lease, progress)); progress.fill(0)
        assertArrayEquals(byteArrayOf(9, 8), f.row.checkpoint); assertEquals("CHECKPOINTED", f.row.state)
        assertArrayEquals(before, f.row.input)
        assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", f.row.canonicalInputSha256)
        assertTrue(f.commands.complete(lease)); assertNull(f.row.leaseOwner); assertNull(f.row.leaseUntil)
    }

    @Test fun unadmittedTerminalOrCanceledJobsCannotStart() = runTest {
        for (state in listOf("PENDING", "WAITING_RESOURCE", "SUCCEEDED", "FAILED", "CANCELED")) {
            val f = Fixture(); f.row = f.row.copy(state = state)
            assertTrue(f.commands.acquireAdmitted(f.id, f.owner(), 100) is AcquireLeaseResult.Rejected)
            assertEquals(state, f.row.state); assertEquals(0L, f.row.leaseGeneration)
        }
    }

    @Test fun invalidOrOverflowLeaseArgumentsNeverMutateTheJob() = runTest {
        for (duration in listOf(0L, -1L, Long.MAX_VALUE)) {
            val f = Fixture()
            assertTrue(f.commands.acquireAdmitted(f.id, f.owner(), duration) is AcquireLeaseResult.Rejected)
            assertEquals("ADMITTED", f.row.state); assertEquals(0L, f.row.attempt)
        }
        val f = Fixture(); f.row = f.row.copy(leaseGeneration = Long.MAX_VALUE)
        assertTrue(f.commands.acquireAdmitted(f.id, f.owner(), 100) is AcquireLeaseResult.Rejected)
        assertEquals(Long.MAX_VALUE, f.row.leaseGeneration); assertEquals("ADMITTED", f.row.state)
    }

    @Test fun checkpointOrCompletionStorageFailureRemainsStructuredAndDoesNotAdvanceState() = runTest {
        for (complete in listOf(false, true)) {
            val f = Fixture()
            val lease = (f.commands.acquireAdmitted(f.id, f.owner(), 100) as AcquireLeaseResult.Granted).lease
            f.failWrite = true
            try {
                if (complete) f.commands.complete(lease) else f.commands.checkpoint(lease, byteArrayOf(9))
                fail("Storage fault was hidden")
            } catch (failure: app.pristinepage.data.model.RepositoryFailure) {
                assertEquals(app.pristinepage.data.model.OperationErrorCode.STORAGE_FAILED, failure.error.code)
                assertTrue(failure.error.retryable)
                assertFalse(failure.message!!.contains("private"))
            }
            assertEquals("RUNNING", f.row.state); assertNull(f.row.checkpoint)
            assertEquals(1L, f.row.leaseGeneration); assertEquals(lease.owner, f.row.leaseOwner)
        }
    }
}
