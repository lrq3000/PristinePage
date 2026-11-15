package app.pristinepage.data

import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Real lease/domain row logic with only Android's transaction boundary substituted. A domain document mutation stands in for the forthcoming source/page/journal writes; this does not claim actual ingestion or Room rollback runtime coverage. */
class JobLeaseCompletionTest {
    private class Fixture : JobLeaseQueries {
        val catalog = MemoryMetadata()
        val document = UUID.randomUUID().toString()
        val lease = JobLease(JobId(UUID.randomUUID().toString()), UUID.randomUUID().toString(), 1)
        var row = JobRow(lease.jobId.value, "RECONCILE", "fixture-v1", "a".repeat(64), null, null, byteArrayOf(1, 2, 3), "RUNNING", 1, lease.owner, 1100, 1, null, null, 0, 0)
        var now = 1000L
        var wallNow: Long? = null
        var failedCas = false
        var storageFault = false
        var transactions = 0
        val metadata = object : MetadataAccess {
            override val queries: CatalogQueries get() = catalog
            override fun <T> transaction(action: () -> T): T {
                transactions++
                val before = row
                return try { catalog.transaction(action) }
                catch (failure: Throwable) { row = before; throw failure }
            }
        }
        val completion: FencedJobCompletion
        init {
            catalog.documents[document] = DocumentRow(document, "Synthetic completion fixture", 1, "ACTIVE", 0, 0, null)
            val type = try { Class.forName("app.pristinepage.data.repository.JobLeaseTransactions") }
            catch (error: ClassNotFoundException) { throw AssertionError("Joint fenced metadata/job completion is absent", error) }
            completion = try { type.constructors.single { it.parameterCount == 4 }.newInstance(metadata, this, { wallNow ?: now }, { now }) as FencedJobCompletion }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        override fun job(id: String): JobRow? { check(catalog.inTransaction); return row.takeIf { it.jobId == id } }
        override fun updateLease(row: JobRow, expectedGeneration: Long): Int {
            check(catalog.inTransaction)
            if (storageFault) throw IOException("synthetic private storage fault")
            if (failedCas || expectedGeneration != this.row.leaseGeneration) return 0
            this.row = row; return 1
        }
        fun writeDomain(): String {
            check(catalog.inTransaction)
            val doc = catalog.documents[document]!!
            catalog.updateDocument(doc.copy(documentRevision = doc.documentRevision + 1))
            return "committed"
        }
        fun revision() = catalog.documents[document]!!.documentRevision
    }

    @Test fun jointCompletionPublishesDomainRowsAndJobInOneNonNestedTransaction() {
        val f = Fixture(); val input = f.row.input.copyOf()
        assertEquals("committed", f.completion.complete(f.lease, f::writeDomain))
        assertEquals(1, f.transactions); assertEquals(2L, f.revision())
        assertEquals("SUCCEEDED", f.row.state); assertNull(f.row.leaseOwner); assertNull(f.row.leaseUntil)
        assertEquals(1L, f.row.attempt); assertEquals(1L, f.row.leaseGeneration)
        assertArrayEquals(input, f.row.input); assertEquals("fixture-v1", f.row.idempotenceKey)
        assertNull(f.completion.complete(f.lease) { fail("Repeated completion ran domain writes"); "wrong" })
        assertEquals(2L, f.revision())
    }

    @Test fun staleExpiredOrCanceledLeaseCannotRunTheDomainWriter() {
        for (change in listOf<(JobRow) -> JobRow>(
            { it.copy(leaseOwner = UUID.randomUUID().toString()) }, { it.copy(leaseGeneration = 2) },
            { it.copy(leaseUntil = 1000) }, { it.copy(state = "CANCELED") }, { it.copy(state = "SUCCEEDED") },
        )) {
            val f = Fixture(); f.row = change(f.row); val before = f.row
            assertNull(f.completion.complete(f.lease) { fail("Lost lease ran a domain write"); "wrong" })
            assertEquals(1L, f.revision()); assertEquals(before, f.row)
        }
    }

    @Test fun domainFailureRollsBackBothMetadataAndJobInsteadOfFinishingTheLease() {
        val f = Fixture(); val fault = RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "synthetic_integrity_fault", f.document))
        val thrown = assertThrows(RepositoryFailure::class.java) { f.completion.complete(f.lease) { f.writeDomain(); throw fault } }
        assertSame(fault, thrown); assertEquals(1L, f.revision()); assertEquals("RUNNING", f.row.state)
    }

    @Test fun finalLeaseCasFailureRollsBackAlreadyWrittenDomainRows() {
        val f = Fixture(); f.failedCas = true
        val failure = assertThrows(RepositoryFailure::class.java) { f.completion.complete(f.lease, f::writeDomain) }
        assertEquals(OperationErrorCode.REVISION_CONFLICT, failure.error.code)
        assertEquals(1L, f.revision()); assertEquals("RUNNING", f.row.state)
    }

    @Test fun leaseExpiringDuringDomainWorkRollsBackTheEntireCompletion() {
        val f = Fixture()
        val failure = assertThrows(RepositoryFailure::class.java) { f.completion.complete(f.lease) { f.writeDomain(); f.now = 1100; "too late" } }
        assertEquals(OperationErrorCode.REVISION_CONFLICT, failure.error.code)
        assertEquals(1L, f.revision()); assertEquals("RUNNING", f.row.state)
    }

    @Test fun jobStorageFailureIsSafeAndRollsBackTheDomainWriter() {
        val f = Fixture(); f.storageFault = true
        val failure = assertThrows(RepositoryFailure::class.java) { f.completion.complete(f.lease, f::writeDomain) }
        assertEquals(OperationErrorCode.STORAGE_FAILED, failure.error.code); assertTrue(failure.error.retryable)
        assertFalse(failure.message!!.contains("private")); assertEquals(1L, f.revision()); assertEquals("RUNNING", f.row.state)
    }

    @Test fun cancellationInsideTheWriterRollsBackAndRemainsCancellation() {
        val f = Fixture(); val cancellation = CancellationException("synthetic cancellation")
        val thrown = assertThrows(CancellationException::class.java) { f.completion.complete(f.lease) { f.writeDomain(); throw cancellation } }
        assertSame(cancellation, thrown); assertEquals(1L, f.revision()); assertEquals("RUNNING", f.row.state)
    }

    @Test fun wallJumpDuringJointWriterCannotInvalidateItsElapsedDeadline() {
        val f = Fixture(); f.wallNow = 1000
        assertEquals("committed", f.completion.complete(f.lease) { f.writeDomain(); f.now = 1010; f.wallNow = 900000; "committed" })
        assertEquals(2L, f.revision()); assertEquals("SUCCEEDED", f.row.state)
        assertEquals(900000L, f.row.updatedAt)
    }

    @Test fun elapsedExpiryDuringJointWriterRollsBackEvenWhenWallTimeStandsStill() {
        val f = Fixture(); f.wallNow = 1000
        val failure = assertThrows(RepositoryFailure::class.java) { f.completion.complete(f.lease) { f.writeDomain(); f.now = 1100; "expired" } }
        assertEquals(OperationErrorCode.REVISION_CONFLICT, failure.error.code)
        assertEquals(1L, f.revision()); assertEquals("RUNNING", f.row.state)
    }
}
