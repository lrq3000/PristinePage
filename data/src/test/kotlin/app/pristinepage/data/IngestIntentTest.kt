package app.pristinepage.data

import app.pristinepage.data.db.*
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Actual intent/session/job commands with only Android's row storage/transaction substituted. Preparation cannot copy/decode a source or make a Page visible; those remain consuming SourceStore/recovery tests. */
class IngestIntentTest {
    internal class Fixture : ImportSessionQueries, IngestPreparationQueries, IngestCompletionQueries, JobLeaseQueries {
        val catalog = MemoryMetadata()
        val document = DocumentId(UUID.randomUUID().toString())
        val parent = ImportSessionId(UUID.randomUUID().toString())
        val child = ImportSessionId(UUID.randomUUID().toString())
        val page = PageId(UUID.randomUUID().toString())
        val uri = "content://fixture.documents/selected/one"
        val sessions = linkedMapOf<String, ImportSessionRow>()
        val files = linkedMapOf<String, FileTransactionRow>()
        val jobs = linkedMapOf<String, JobRow>()
        val blobs = linkedMapOf<String, SourceBlobRow>()
        var now = 1000L
        var rejectChildUpdate = false
        val metadata = object : MetadataAccess {
            override val queries: CatalogQueries get() = catalog
            override fun <T> transaction(action: () -> T): T {
                val s = sessions.toMap(); val f = files.toMap(); val j = jobs.toMap(); val b = blobs.toMap()
                return try { catalog.transaction(action) }
                catch (failure: Throwable) {
                    sessions.clear(); sessions.putAll(s); files.clear(); files.putAll(f); jobs.clear(); jobs.putAll(j); blobs.clear(); blobs.putAll(b)
                    throw failure
                }
            }
        }
        val commands: IngestPreparation
        init {
            catalog.documents[document.value] = DocumentRow(document.value, "Synthetic ingest fixture", 1, "ACTIVE", 0, 0, null)
            val membership = ImportMembership.hash(emptyList())
            sessions[parent.value] = ImportSessionRow(parent.value, null, "BATCH", document.value, null, null, null, null, "RESERVED", membership, null, 0, 86400000, null)
            sessions[child.value] = ImportSessionRow(child.value, parent.value, "ITEM", document.value, null, 0, page.value, uri, "BOUND", membership, null, 0, 86400000, null)
            val type = try { Class.forName("app.pristinepage.data.repository.RoomIngestPreparation") }
            catch (error: ClassNotFoundException) { throw AssertionError("Session-bound ingest intent preparation is absent", error) }
            commands = try { type.constructors.single { it.parameterCount == 5 }.newInstance(metadata, this, this, this, { now }) as IngestPreparation }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        override fun session(id: String): ImportSessionRow? { check(catalog.inTransaction); return sessions[id] }
        override fun children(parentId: String) = sessions.values.filter { it.parentSessionId == parentId }.sortedBy { it.selectionOrdinal }
        override fun activeBatches(documentId: String) = sessions.values.filter { it.documentId == documentId && it.kind == "BATCH" && it.state == "RESERVED" }
        override fun insertSession(row: ImportSessionRow) { check(catalog.inTransaction); check(sessions.putIfAbsent(row.sessionId, row) == null) }
        override fun updateSession(row: ImportSessionRow): Int {
            check(catalog.inTransaction)
            if (rejectChildUpdate) throw IOException("synthetic private metadata write failure")
            if (row.sessionId !in sessions) return 0
            sessions[row.sessionId] = row; return 1
        }
        override fun fileTransaction(id: String): FileTransactionRow? { check(catalog.inTransaction); return files[id] }
        override fun insertFileTransaction(row: FileTransactionRow) { check(catalog.inTransaction); check(files.putIfAbsent(row.transactionId, row) == null) }
        override fun insertIngestJob(row: JobRow) { check(catalog.inTransaction); check(jobs.putIfAbsent(row.jobId, row) == null) }
        override fun job(id: String): JobRow? { check(catalog.inTransaction); return jobs[id] }
        override fun updateLease(row: JobRow, expectedGeneration: Long): Int {
            check(catalog.inTransaction)
            if (jobs[row.jobId]?.leaseGeneration != expectedGeneration) return 0
            jobs[row.jobId] = row; return 1
        }
        override fun sourceBlob(id: String): SourceBlobRow? { check(catalog.inTransaction); return blobs[id] }
        override fun insertSourceBlob(row: SourceBlobRow) { check(catalog.inTransaction); check(blobs.putIfAbsent(row.sourceBlobId, row) == null) }
        override fun insertSource(row: SourceRow) { check(catalog.inTransaction); check(catalog.sources.putIfAbsent(row.sourceId, row) == null) }
        override fun updateFileTransaction(row: FileTransactionRow, expectedState: String): Int {
            check(catalog.inTransaction)
            if (files[row.transactionId]?.state != expectedState) return 0
            files[row.transactionId] = row; return 1
        }
    }

    @Test fun durableIntentAndPendingJobPrecedeAnySourceOrPageVisibility() {
        val f = Fixture(); val intent = f.commands.prepare(f.child, f.uri)
        assertEquals(f.document, intent.documentId); assertEquals(f.child, intent.sessionId); assertEquals(f.page, intent.pageId)
        val row = f.files[intent.transactionId.value]!!
        assertEquals("INGEST", row.kind); assertEquals(f.child.value, row.ownerId); assertEquals("INTENT", row.state)
        assertEquals("staging/${intent.transactionId.value}/original.part", row.stagingPath)
        assertEquals("sources/${intent.sourceBlobId.value}/original.bin", row.finalPath)
        assertEquals(intent, IngestJournalCodec.decodeIntent(row.payload))
        val job = f.jobs[intent.jobId.value]!!
        assertEquals("PENDING", job.state); assertEquals(0L, job.attempt); assertNull(job.leaseOwner); assertNull(job.leaseUntil)
        assertArrayEquals(row.payload, job.input); assertFalse(String(job.input, Charsets.UTF_8).contains("content:"))
        assertEquals("INGESTING", f.sessions[f.child.value]!!.state)
        assertTrue(f.catalog.pages.isEmpty()); assertTrue(f.catalog.sources.isEmpty()); assertEquals(1L, f.catalog.documents[f.document.value]!!.documentRevision)
    }

    @Test fun repeatedPreparationReusesTheExactTransactionAndJob() {
        val f = Fixture(); val first = f.commands.prepare(f.child, f.uri)
        val row = f.files[first.transactionId.value]!!; val job = f.jobs[first.jobId.value]!!
        f.now = 2000
        assertEquals(first, f.commands.prepare(f.child, f.uri))
        assertSame(row, f.files[first.transactionId.value]); assertSame(job, f.jobs[first.jobId.value])
        assertEquals(1, f.files.size); assertEquals(1, f.jobs.size)
    }

    @Test fun wrongUriCanceledExpiredOrWrongDestinationCannotCreateAnIntent() {
        for (mode in listOf("uri", "canceled", "expired", "destination", "membership")) {
            val f = Fixture()
            when (mode) {
                "canceled" -> f.sessions[f.child.value] = f.sessions[f.child.value]!!.copy(state = "CANCELED")
                "expired" -> f.now = 86400000
                "destination" -> f.sessions[f.child.value] = f.sessions[f.child.value]!!.copy(documentId = UUID.randomUUID().toString())
                "membership" -> f.sessions[f.parent.value] = f.sessions[f.parent.value]!!.copy(expectedMembershipToken = "0".repeat(64))
            }
            assertThrows(RepositoryFailure::class.java) { f.commands.prepare(f.child, if (mode == "uri") "content://fixture.documents/different" else f.uri) }
            assertTrue(f.files.isEmpty()); assertTrue(f.jobs.isEmpty()); assertTrue(f.catalog.pages.isEmpty())
        }
    }

    @Test fun failureBindingChildRollsBackBothNewIntentAndPendingJob() {
        val f = Fixture(); f.rejectChildUpdate = true
        val failure = assertThrows(RepositoryFailure::class.java) { f.commands.prepare(f.child, f.uri) }
        assertEquals(OperationErrorCode.STORAGE_FAILED, failure.error.code)
        assertTrue(f.files.isEmpty()); assertTrue(f.jobs.isEmpty())
        assertEquals("BOUND", f.sessions[f.child.value]!!.state); assertNull(f.sessions[f.child.value]!!.transactionId)
    }

    @Test fun retainedIntentCannotBeReboundToAnotherDocumentDuringReplay() {
        val f = Fixture(); val intent = f.commands.prepare(f.child, f.uri)
        val row = f.files[intent.transactionId.value]!!
        f.files[row.transactionId] = row.copy(payload = IngestJournalCodec.encodeIntent(intent.copy(documentId = DocumentId(UUID.randomUUID().toString()))))
        val failure = assertThrows(RepositoryFailure::class.java) { f.commands.prepare(f.child, f.uri) }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
        assertEquals(1, f.files.size); assertEquals(1, f.jobs.size); assertTrue(f.catalog.pages.isEmpty())
    }

    @Test fun missingOrChangedCanonicalJobInputDoesNotMintAnotherAttempt() {
        for (missing in listOf(false, true)) {
            val f = Fixture(); val intent = f.commands.prepare(f.child, f.uri)
            if (missing) f.jobs.remove(intent.jobId.value)
            else f.jobs[intent.jobId.value] = f.jobs[intent.jobId.value]!!.copy(input = byteArrayOf(9))
            val failure = assertThrows(RepositoryFailure::class.java) { f.commands.prepare(f.child, f.uri) }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
            assertEquals(1, f.files.size); assertTrue(f.catalog.pages.isEmpty())
        }
    }
}
