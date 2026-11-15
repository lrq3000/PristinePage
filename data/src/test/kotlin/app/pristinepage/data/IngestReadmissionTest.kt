package app.pristinepage.data

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Production canonical preparation/owner lifetime and readmission commands, substituting only Android DB storage. Local ENDED evidence is real; no process-death/cross-process proof or resource permit is fabricated by these metadata tests. */
class IngestReadmissionTest {
    private class Fixture {
        val db = IngestIntentTest.Fixture()
        val intent = db.commands.prepare(db.child, db.uri)
        val owners = LocalOperationOwners()
        var refuseWrite = false
        var storageFault = false
        val commands: IngestReadmission
        private val jobs = object : JobLeaseQueries {
            override fun job(id: String) = db.job(id)
            override fun updateLease(row: JobRow, expectedGeneration: Long): Int {
                if (storageFault) throw IOException("synthetic private readmission storage fault")
                return if (refuseWrite) 0 else db.updateLease(row, expectedGeneration)
            }
        }
        init {
            val type = try { Class.forName("app.pristinepage.data.repository.RoomIngestReadmission") }
            catch (error: ClassNotFoundException) { throw AssertionError("Positively ended ingest attempt readmission is absent", error) }
            commands = try { type.constructors.single { it.parameterCount == 6 }.newInstance(db.metadata, db, db, jobs, owners, { db.now }) as IngestReadmission }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        fun record(owner: String): JobLease {
            val lease = JobLease(intent.jobId, owner, 1)
            db.jobs[intent.jobId.value] = db.jobs[intent.jobId.value]!!.copy(state = "RUNNING", leaseOwner = owner, leaseUntil = 1100, leaseGeneration = 1, attempt = 1)
            db.files[intent.transactionId.value] = db.files[intent.transactionId.value]!!.copy(state = "COPYING")
            return lease
        }
        suspend fun ended(): JobLease = owners.run { owner -> record(owner) }
    }

    @Test fun positivelyEndedAttemptReadmitsWithoutChangingCanonicalInputOrMintingARunner() = runTest {
        val f = Fixture(); val old = f.ended(); val job = f.db.jobs.values.single(); val journal = f.db.files.values.single()
        assertEquals(LeaseOwnerState.ENDED, f.owners.state(old.owner))
        assertTrue(f.commands.readmitEnded(f.intent, old))
        val readmitted = f.db.jobs.values.single()
        assertEquals("ADMITTED", readmitted.state); assertNull(readmitted.leaseOwner); assertNull(readmitted.leaseUntil)
        assertEquals(1L, readmitted.leaseGeneration); assertEquals(1L, readmitted.attempt)
        assertArrayEquals(job.input, readmitted.input); assertEquals(job.canonicalInputSha256, readmitted.canonicalInputSha256)
        assertSame(journal, f.db.files.values.single()); assertTrue(f.db.catalog.pages.isEmpty())
        assertFalse(f.commands.readmitEnded(f.intent, old))
    }

    @Test fun elapsedExpiryCannotReadmitAnAliveOwner() = runTest {
        val f = Fixture()
        f.owners.run { owner ->
            val old = f.record(owner); f.db.now = 1200
            assertFalse(f.commands.readmitEnded(f.intent, old))
            assertEquals("RUNNING", f.db.jobs.values.single().state)
            assertEquals(owner, f.db.jobs.values.single().leaseOwner)
        }
    }

    @Test fun absentRestartedRegistryOwnerCannotBeDeclaredEndedByItsOldDeadline() {
        val f = Fixture(); val old = f.record(UUID.randomUUID().toString()); f.db.now = 1000000
        assertEquals(LeaseOwnerState.UNKNOWN, f.owners.state(old.owner))
        assertFalse(f.commands.readmitEnded(f.intent, old))
        assertEquals("RUNNING", f.db.jobs.values.single().state)
    }

    @Test fun aChangedOwnerOrGenerationCannotReuseAnEarlierEndedSnapshot() = runTest {
        for (field in listOf("owner", "generation")) {
            val f = Fixture(); val old = f.ended(); val job = f.db.jobs.values.single()
            f.db.jobs[job.jobId] = if (field == "owner") job.copy(leaseOwner = UUID.randomUUID().toString()) else job.copy(leaseGeneration = 2)
            assertFalse(f.commands.readmitEnded(f.intent, old))
            assertEquals("RUNNING", f.db.jobs.values.single().state)
        }
    }

    @Test fun changedCanonicalHashOrJournalOwnershipCannotBeReadmitted() = runTest {
        for (field in listOf("hash", "journal")) {
            val f = Fixture(); val old = f.ended()
            if (field == "hash") f.db.jobs[f.intent.jobId.value] = f.db.jobs.values.single().copy(canonicalInputSha256 = "b".repeat(64))
            else f.db.files[f.intent.transactionId.value] = f.db.files.values.single().copy(ownerId = UUID.randomUUID().toString())
            val failure = assertThrows(RepositoryFailure::class.java) { f.commands.readmitEnded(f.intent, old) }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
            assertEquals("RUNNING", f.db.jobs.values.single().state)
        }
    }

    @Test fun canceledSessionOrDeletedDocumentCannotBeReadmitted() = runTest {
        for (field in listOf("session", "document", "journal")) {
            val f = Fixture(); val old = f.ended()
            when (field) {
                "session" -> f.db.sessions[f.db.child.value] = f.db.sessions[f.db.child.value]!!.copy(state = "CANCELED")
                "document" -> f.db.catalog.documents[f.db.document.value] = f.db.catalog.documents[f.db.document.value]!!.copy(lifecycle = "TOMBSTONED")
                else -> f.db.files[f.intent.transactionId.value] = f.db.files.values.single().copy(state = "CANCELED")
            }
            assertFalse(f.commands.readmitEnded(f.intent, old))
            assertEquals("RUNNING", f.db.jobs.values.single().state); assertTrue(f.db.catalog.pages.isEmpty())
        }
    }

    @Test fun refusedCasOrStorageFaultDoesNotCreateAnAdmittedAttempt() = runTest {
        for (fault in listOf(false, true)) {
            val f = Fixture(); val old = f.ended(); f.storageFault = fault; f.refuseWrite = !fault
            if (fault) {
                val failure = assertThrows(RepositoryFailure::class.java) { f.commands.readmitEnded(f.intent, old) }
                assertEquals(OperationErrorCode.STORAGE_FAILED, failure.error.code)
                assertFalse(failure.message!!.contains("private"))
            } else assertFalse(f.commands.readmitEnded(f.intent, old))
            assertEquals("RUNNING", f.db.jobs.values.single().state); assertEquals(old.owner, f.db.jobs.values.single().leaseOwner)
        }
    }

    @Test fun readmittedJobRejectsEndedRequesterButFreshLiveOwnerCanFenceTheOldLease() = runTest {
        val f = Fixture(); val old = f.ended()
        assertTrue(f.commands.readmitEnded(f.intent, old))
        val admitted = f.db.jobs.values.single()
        val leases = RoomJobLeases(f.db.metadata, f.db, f.owners, { f.db.now }, { f.db.now }, Dispatchers.Unconfined)
        val duplicate = leases.acquireAdmitted(f.intent.jobId, old.owner, 100)
        assertFalse("Readmission let an already-ENDED identity acquire a new execution", duplicate is AcquireLeaseResult.Granted)
        assertSame(admitted, f.db.jobs.values.single())
        assertEquals(LeaseOwnerState.ENDED, f.owners.state(old.owner))
        f.owners.run { fresh ->
            val acquired = leases.acquireAdmitted(f.intent.jobId, fresh, 100) as AcquireLeaseResult.Granted
            assertEquals(2L, acquired.lease.generation); assertEquals(fresh, acquired.lease.owner)
            assertFalse(leases.checkpoint(old, byteArrayOf(9))); assertFalse(leases.complete(old))
            assertTrue(leases.checkpoint(acquired.lease, byteArrayOf(7)))
            assertTrue(leases.complete(acquired.lease))
        }
        assertEquals("SUCCEEDED", f.db.jobs.values.single().state)
    }

    @Test fun readmittedJobCannotStartWithAnUnknownRequesterIdentity() = runTest {
        val f = Fixture(); val old = f.ended(); assertTrue(f.commands.readmitEnded(f.intent, old))
        val admitted = f.db.jobs.values.single()
        val leases = RoomJobLeases(f.db.metadata, f.db, f.owners, { f.db.now }, { f.db.now }, Dispatchers.Unconfined)
        assertFalse(leases.acquireAdmitted(f.intent.jobId, UUID.randomUUID().toString(), 100) is AcquireLeaseResult.Granted)
        assertSame(admitted, f.db.jobs.values.single()); assertTrue(f.db.catalog.pages.isEmpty())
    }
}
