package app.pristinepage.data

import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Direct counterexamples for the real phase transaction participant. Only Android row storage is substituted; SourceStoreTest separately exercises its actual filesystem consumer. */
class IngestPhaseTest {
    private class Fixture {
        val db = IngestIntentTest.Fixture()
        val intent = db.commands.prepare(db.child, db.uri)
        val lease = JobLease(intent.jobId, UUID.randomUUID().toString(), 1)
        var elapsed = 1000L
        var refuseJobWrite = false
        var expireAfterPhaseWrite = false
        private val jobs = object : JobLeaseQueries {
            override fun job(id: String) = db.job(id)
            override fun updateLease(row: JobRow, expectedGeneration: Long): Int = if (refuseJobWrite) 0 else db.updateLease(row, expectedGeneration)
        }
        private val writes = object : IngestPhaseQueries {
            override fun updateIngestPhase(row: FileTransactionRow, expectedState: String): Int {
                val updated = db.updateIngestPhase(row, expectedState)
                if (expireAfterPhaseWrite) elapsed = 1100
                return updated
            }
        }
        val phases = RoomIngestPhases(db.metadata, db, db, jobs, writes, { db.now }, { elapsed })
        fun running() {
            assertTrue(phases.admit(intent))
            db.jobs[intent.jobId.value] = db.jobs[intent.jobId.value]!!.copy(state = "RUNNING", leaseOwner = lease.owner, leaseUntil = 1100, leaseGeneration = 1, attempt = 1)
        }
    }

    @Test fun admissionDoesNotInventAnOwnerOrMutateCanonicalJobInput() {
        val f = Fixture(); val before = f.db.jobs[f.intent.jobId.value]!!
        assertTrue(f.phases.admit(f.intent))
        val admitted = f.db.jobs[f.intent.jobId.value]!!
        assertEquals("ADMITTED", admitted.state); assertNull(admitted.leaseOwner); assertNull(admitted.leaseUntil)
        assertEquals(0L, admitted.attempt); assertEquals(0L, admitted.leaseGeneration)
        assertArrayEquals(before.input, admitted.input); assertEquals(before.idempotenceKey, admitted.idempotenceKey)
        assertEquals("INTENT", f.db.files.values.single().state); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun staleOwnerGenerationExpiryOrTerminalJobCannotAdvanceCopying() {
        for (change in listOf<(JobRow) -> JobRow>(
            { it.copy(leaseOwner = UUID.randomUUID().toString()) }, { it.copy(leaseGeneration = 2) },
            { it.copy(leaseUntil = 1000) }, { it.copy(state = "CANCELED") },
        )) {
            val f = Fixture(); f.running(); f.db.jobs[f.intent.jobId.value] = change(f.db.jobs[f.intent.jobId.value]!!)
            assertFalse(f.phases.copying(f.intent, f.lease))
            assertEquals("INTENT", f.db.files.values.single().state)
        }
    }

    @Test fun canceledSessionDeletedDocumentOrReboundReservationCannotStartCopying() {
        for (mutation in listOf("child", "parent", "document", "page")) {
            val f = Fixture(); f.running()
            when (mutation) {
                "child" -> f.db.sessions[f.db.child.value] = f.db.sessions[f.db.child.value]!!.copy(state = "CANCELED")
                "parent" -> f.db.sessions[f.db.parent.value] = f.db.sessions[f.db.parent.value]!!.copy(state = "CANCELED")
                "document" -> f.db.catalog.documents[f.db.document.value] = f.db.catalog.documents[f.db.document.value]!!.copy(lifecycle = "TOMBSTONED")
                else -> f.db.sessions[f.db.child.value] = f.db.sessions[f.db.child.value]!!.copy(plannedPageId = UUID.randomUUID().toString())
            }
            assertFalse(f.phases.copying(f.intent, f.lease))
            assertEquals("INTENT", f.db.files.values.single().state)
        }
    }

    @Test fun changedCanonicalInputCannotBeAdmittedOrAdvanced() {
        val f = Fixture()
        f.db.jobs[f.intent.jobId.value] = f.db.jobs[f.intent.jobId.value]!!.copy(canonicalInputSha256 = "b".repeat(64))
        val failure = assertThrows(RepositoryFailure::class.java) { f.phases.admit(f.intent) }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
        assertEquals("PENDING", f.db.jobs.values.single().state); assertEquals("INTENT", f.db.files.values.single().state)
    }

    @Test fun failureJournalAndJobRollbackTogetherIfFinalCasFails() {
        val f = Fixture(); f.running(); assertTrue(f.phases.copying(f.intent, f.lease)); f.refuseJobWrite = true
        val failure = assertThrows(RepositoryFailure::class.java) { f.phases.failed(f.intent, f.lease, OperationError(OperationErrorCode.CANCELED, false, "synthetic_cancel", f.db.child.value)) }
        assertEquals(OperationErrorCode.REVISION_CONFLICT, failure.error.code)
        assertEquals("COPYING", f.db.files.values.single().state); assertEquals("RUNNING", f.db.jobs.values.single().state)
        assertEquals(f.lease.owner, f.db.jobs.values.single().leaseOwner)
        assertEquals("INGESTING", f.db.sessions[f.db.child.value]!!.state)
        assertEquals("RESERVED", f.db.sessions[f.db.parent.value]!!.state)
    }

    @Test fun expiryDuringPhaseWriterRollsBackItsJournalMutation() {
        val f = Fixture(); f.running(); f.expireAfterPhaseWrite = true
        val failure = assertThrows(RepositoryFailure::class.java) { f.phases.copying(f.intent, f.lease) }
        assertEquals(OperationErrorCode.REVISION_CONFLICT, failure.error.code)
        assertEquals("INTENT", f.db.files.values.single().state)
    }

    @Test fun retryableFailureRetainsCanonicalIntentAndEndsItsLeaseWithoutPublishing() {
        val f = Fixture(); f.running(); assertTrue(f.phases.copying(f.intent, f.lease))
        val input = f.db.files.values.single().payload.copyOf()
        assertTrue(f.phases.failed(f.intent, f.lease, OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_io_fault", f.db.child.value)))
        assertEquals("RETRYABLE", f.db.files.values.single().state); assertEquals("RETRYABLE", f.db.jobs.values.single().state)
        assertArrayEquals(input, f.db.files.values.single().payload)
        assertNull(f.db.jobs.values.single().leaseOwner); assertNull(f.db.jobs.values.single().leaseUntil)
        assertTrue(f.db.catalog.pages.isEmpty()); assertFalse(f.phases.copying(f.intent, f.lease))
    }

    @Test fun terminalFailureCannotSettleAReboundChildOrParent() {
        for (target in listOf("child", "parent")) {
            val f = Fixture(); f.running(); assertTrue(f.phases.copying(f.intent, f.lease))
            val id = if (target == "child") f.db.child.value else f.db.parent.value
            f.db.sessions[id] = f.db.sessions[id]!!.copy(documentId = UUID.randomUUID().toString())
            val failure = assertThrows(RepositoryFailure::class.java) { f.phases.failed(f.intent, f.lease, OperationError(OperationErrorCode.CANCELED, false, "synthetic_cancel", f.db.child.value)) }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
            assertEquals("COPYING", f.db.files.values.single().state); assertEquals("RUNNING", f.db.jobs.values.single().state)
        }
    }
}
