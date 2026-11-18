package app.pristinepage.data

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RenderFileIntentTest {
    @Test fun committedIntentHasFixedPrivatePathsAndStableReplayWithoutNewIds() = runTest {
        val f = Fixture()
        val first = f.prepare()
        val row = f.rows.single()
        assertEquals("INTENT", row.state)
        assertEquals("RENDER", row.kind)
        assertEquals(f.lease.jobId.value, row.ownerId)
        assertEquals(1L, row.attempt)
        assertEquals("staging/$transactionId/derived.part", row.stagingPath)
        assertEquals("renders/$renderId/page.bin", row.finalPath)
        assertNull(row.sealSha256)
        assertNull(row.expectedByteSha256)
        assertNull(row.expectedByteCount)
        val tag = "pristinepage-ordinary-render-intent-v1\u0000".encodeToByteArray()
        assertArrayEquals(tag, row.payload.copyOf(tag.size))
        assertEquals(transactionId + renderId + f.lease.jobId.value + f.lease.owner,
            row.payload.decodeToString(tag.size, tag.size + 144))
        assertEquals(1L, ByteBuffer.wrap(row.payload, tag.size + 144, 8).long)
        assertEquals(1L, ByteBuffer.wrap(row.payload, tag.size + 152, 8).long)
        assertEquals(f.job.canonicalInputSha256, row.payload.decodeToString(tag.size + 160, tag.size + 224))
        assertEquals(3, ByteBuffer.wrap(row.payload, row.payload.size - 8, 4).int)
        assertEquals(2, ByteBuffer.wrap(row.payload, row.payload.size - 4, 4).int)
        assertEquals(first, f.prepare())
        assertEquals(2, f.idsIssued)
        assertEquals(1, f.writes)
    }

    @Test fun staleCrossJobExpiredOrUnownedLeaseStopsBeforeJournalAccess() = runTest {
        val f = Fixture()
        f.verify()
        val exact = f.lease
        for (lease in listOf(exact.copy(generation = 2), exact.copy(jobId = JobId(otherJob)), exact.copy(owner = nextOwner))) {
            f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.prepare(lease) }
        }
        f.liveness = LeaseOwnerState.UNKNOWN
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.prepare() }
        f.liveness = LeaseOwnerState.ALIVE
        f.elapsed = f.job.leaseUntil!!
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.prepare() }
        assertEquals(0, f.journalReads)
        assertEquals(0, f.idsIssued)
        assertEquals(0, f.writes)
    }

    @Test fun changedJobPinAndSubstitutedFrozenSourceRefuseWithoutChangingIntent() = runTest {
        val f = Fixture()
        val proof = f.verify()
        val intent = f.prepare(verified = proof)
        val row = f.rows.single()
        f.pinOverride = listOf(nextOwner)
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.prepare(verified = proof) }
        f.pinOverride = null
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.copying(intent.copy(sourceBlobId = SourceBlobId(nextOwner))) }
        assertEquals(row, f.rows.single())
        assertEquals(1, f.writes)
    }

    @Test fun changedCanonicalJobAndUnboundedLeaseNeverReachJournal() = runTest {
        val f = Fixture()
        val verified = f.verify()
        val original = f.job
        for (changed in listOf(original.copy(attempt = 2), original.copy(canonicalInputSha256 = "d".repeat(64)), original.copy(input = original.input + 1.toByte()))) {
            f.job = changed
            f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.prepare(verified = verified) }
        }
        f.job = original
        f.leaseBounded = false
        val reads = f.jobReads
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.prepare(verified = verified) }
        assertEquals(reads, f.jobReads)
        assertEquals(0, f.journalReads)
        assertEquals(0, f.writes)
    }

    @Test fun coherentButDifferentReferenceCannotAuthorizeThisLease() = runTest {
        val f = Fixture()
        val other = Fixture()
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.prepare(verified = other.verify()) }
        assertEquals(0, f.journalReads)
        assertEquals(0, f.writes)
    }

    @Test fun copyingRequiresTheCommittedExactIntentAndGrantsOnlyOneTransition() = runTest {
        val f = Fixture()
        val intent = f.prepare()
        val row = f.rows.removeAt(0)
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.copying(intent) }
        f.rows += row
        f.copying(intent)
        assertEquals(row.copy(state = "COPYING"), f.rows.single())
        assertEquals(intent, f.prepare())
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.copying(intent) }
        assertEquals(2, f.writes)
        assertEquals(2, f.idsIssued)
        assertEquals("RUNNING", f.job.state)
    }

    @Test fun anotherAttemptNeitherReopensOldIdsNorChangesTheOldRow() = runTest {
        val f = Fixture()
        val old = f.prepare()
        val oldLease = f.lease
        val first = f.rows.single()
        f.job = f.job.copy(leaseOwner = nextOwner, leaseGeneration = 2, attempt = 2)
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.prepare(oldLease) }
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.copying(old) }
        val new = f.prepare()
        assertNotEquals(old, new)
        assertEquals(4, f.idsIssued)
        assertEquals(first, f.rows.first())
        val second = f.rows.last()
        assertNotEquals(first.transactionId, second.transactionId)
        assertNotEquals(first.finalPath, second.finalPath)
        // Merely changing the indexed attempt on old metadata cannot bestow its old IDs on the new attempt.
        f.rows[1] = first.copy(attempt = 2)
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.prepare() }
        assertEquals(2, f.writes)
    }

    @Test fun corruptAndWrongPhaseRowsNeverAuthorizeCopyingOrAllocateReplacement() = runTest {
        val f = Fixture()
        val intent = f.prepare()
        val row = f.rows.single()
        for (changed in listOf(
            row.copy(finalPath = "renders/$nextOwner/page.bin"),
            row.copy(stagingPath = "staging/$nextOwner/derived.part"),
            row.copy(payload = row.payload.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }),
            row.copy(expectedByteCount = 1),
        )) {
            f.rows[0] = changed
            f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.copying(intent) }
            assertSame(changed, f.rows.single())
        }
        f.rows[0] = row.copy(state = "SEALED")
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.copying(intent) }
        f.rows[0] = row
        f.journalBounded = false
        val projections = f.journalProjections
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.prepare() }
        assertEquals(projections, f.journalProjections)
        assertEquals(1, f.writes)
        assertEquals(2, f.idsIssued)
    }

    @Test fun storageExceptionAfterInsertAndElapsedExpiryAfterEitherWriteRollBack() = runTest {
        val f = Fixture()
        f.afterWrite = { throw IOException("private details") }
        f.refuses(OperationErrorCode.STORAGE_FAILED) { f.prepare() }
        assertTrue(f.rows.isEmpty())
        f.afterWrite = { f.elapsed = f.job.leaseUntil!! }
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.prepare() }
        assertTrue(f.rows.isEmpty())
        f.elapsed = 100L
        f.afterWrite = {}
        val intent = f.prepare()
        val row = f.rows.single()
        f.afterWrite = { f.elapsed = f.job.leaseUntil!! }
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.copying(intent) }
        assertEquals(row, f.rows.single())
    }

    @Test fun mismatchedCopyCasOrPostWriteReadbackCannotCommit() = runTest {
        val f = Fixture()
        val intent = f.prepare()
        val row = f.rows.single()
        f.copyCas = false
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.copying(intent) }
        f.copyCas = true
        f.afterWrite = { f.rows[0] = f.rows.single().copy(transactionId = nextOwner) }
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.copying(intent) }
        assertEquals(row, f.rows.single())
    }

    @Test fun expiryDuringThePostInsertPinReadRollsBackIntent() = runTest {
        val f = Fixture()
        val proof = f.verify()
        f.onPinRead = { if (f.writes == 1) f.elapsed = f.job.leaseUntil!! }
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.prepare(verified = proof) }
        assertEquals(1, f.writes)
        assertEquals(f.job.leaseUntil, f.elapsed)
        assertTrue(f.rows.isEmpty())
    }

    @Test fun expiryDuringThePostCasPinReadRollsBackCopying() = runTest {
        val f = Fixture()
        val intent = f.prepare()
        val row = f.rows.single()
        f.onPinRead = { if (f.writes == 2) f.elapsed = f.job.leaseUntil!! }
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.copying(intent) }
        assertEquals(2, f.writes)
        assertEquals(f.job.leaseUntil, f.elapsed)
        assertEquals(row, f.rows.single())
    }

    @Test fun ownerLosingLivenessDuringThePostWritePinReadRollsBack() = runTest {
        val f = Fixture()
        val proof = f.verify()
        f.onPinRead = { if (f.writes == 1) f.liveness = LeaseOwnerState.ENDED }
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.prepare(verified = proof) }
        assertEquals(1, f.writes)
        assertTrue(f.rows.isEmpty())
    }

    @Test fun malformedScopedSiblingWithMissingExactKeyBlocksAllocationAndProjection() = runTest {
        val f = Fixture()
        val proof = f.verify()
        val malformed = Fixture.AttemptKey("RENDER", f.lease.jobId.value, 1.25)
        f.opaqueAttemptKeys += malformed
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.prepare(verified = proof) }
        assertEquals(1, f.attemptScans)
        assertEquals(0, f.journalProjections)
        assertEquals(0, f.idsIssued)
        assertEquals(0, f.writes)
        assertEquals(listOf(malformed), f.opaqueAttemptKeys)
    }

    @Test fun exactKeyReplayAndCopyDoNotScanSiblingAttempts() = runTest {
        val f = Fixture()
        val intent = f.prepare()
        val scans = f.attemptScans
        f.opaqueAttemptKeys += Fixture.AttemptKey("RENDER", f.lease.jobId.value, 1.25)
        assertEquals(intent, f.prepare())
        f.copying(intent)
        assertEquals(scans, f.attemptScans)
        assertEquals(2, f.idsIssued)
        assertEquals("COPYING", f.rows.single().state)
    }

    @Test fun validSiblingAttemptAndUnrelatedMalformedKeysDoNotBlockCreate() = runTest {
        val f = Fixture()
        val previous = f.prepare()
        val old = f.rows.single()
        f.opaqueAttemptKeys += Fixture.AttemptKey("EXPORT", f.lease.jobId.value, 1.25)
        f.opaqueAttemptKeys += Fixture.AttemptKey("RENDER", otherJob, "bad")
        f.job = f.job.copy(leaseOwner = nextOwner, leaseGeneration = 2, attempt = 2)
        val next = f.prepare()
        assertNotEquals(previous, next)
        assertEquals(old, f.rows.first())
        assertEquals(2, f.attemptScans)
        assertEquals(4, f.idsIssued)
        assertEquals(2, f.writes)
    }

    @Test fun cancellationAfterFirstWriteIsNotStorageFailureAndDurableCancelIsDistinct() = runTest {
        val f = Fixture()
        val cancellation = CancellationException("synthetic cancel")
        f.afterWrite = { throw cancellation }
        try {
            f.prepare()
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertTrue(f.rows.isEmpty())
        f.afterWrite = {}
        f.job = f.job.copy(state = "CANCELED", leaseOwner = null, leaseUntil = null)
        f.refuses(OperationErrorCode.CANCELED) { f.prepare(f.initialLease) }
        assertTrue(f.rows.isEmpty())
    }

    private class Fixture {
        // Raw corrupt keys cannot be represented by the Room entity's Long attempt field; the SQL appendix covers actual SQLite storage and selection.
        data class AttemptKey(val kind: String, val owner: String, val attempt: Any)

        private val fixture = RepositoryFixture()
        private val catalog = fixture.metadata
        private val jobs = linkedMapOf<String, JobRow>()
        val rows = mutableListOf<FileTransactionRow>()
        val opaqueAttemptKeys = mutableListOf<AttemptKey>()
        lateinit var initialLease: JobLease
        lateinit var job: JobRow
        lateinit var page: ExportPageSnapshot
        val lease get() = JobLease(initialLease.jobId, checkNotNull(job.leaseOwner), job.leaseGeneration)
        var elapsed = 100L
        var liveness = LeaseOwnerState.ALIVE
        var leaseBounded = true
        var journalBounded = true
        var pinOverride: List<String>? = null
        var onPinRead: () -> Unit = {}
        var copyCas = true
        var jobReads = 0
        var journalReads = 0
        var journalProjections = 0
        var attemptScans = 0
        var idsIssued = 0
        var writes = 0
        var afterWrite: () -> Unit = {}
        private val metadata = object : MetadataAccess {
            override val queries: CatalogQueries = catalog
            override fun <T> transaction(action: () -> T): T {
                val before = rows.toList()
                val beforeJob = job
                return try { catalog.transaction(action) }
                catch (failure: Throwable) { rows.clear(); rows.addAll(before); job = beforeJob; throw failure }
            }
        }
        private val leases = object : JobLeaseQueries {
            override fun job(id: String): JobRow? { check(catalog.inTransaction); jobReads++; return job.takeIf { it.jobId == id } }
            override fun updateLease(row: JobRow, expectedGeneration: Long): Int {
                check(catalog.inTransaction)
                if (job.leaseGeneration != expectedGeneration) return 0
                job = row
                return 1
            }
        }

        private suspend fun initialize() {
            if (::job.isInitialized) return
            val snapshots = object : ExportSnapshotQueries {
                override fun insertSnapshot(row: ExportSnapshotRow) = Unit
                override fun insertExportPage(row: ExportPageRow) = Unit
                override fun renderJobByKey(key: String) = jobs.values.singleOrNull { it.idempotenceKey == key }
                override fun jobSourceReferences(jobId: String) = catalog.references.values.filter { it.ownerType == "JOB" && it.ownerId == jobId }
                override fun insertExportJob(row: JobRow) { check(jobs.putIfAbsent(row.jobId, row) == null) }
                override fun snapshotMetadataIsBounded(snapshotId: String): Boolean? = error("Not a snapshot reader")
                override fun exportPagesAreBounded(snapshotId: String): Boolean = error("Not a snapshot reader")
                override fun snapshot(id: String): ExportSnapshotRow? = error("Not a snapshot reader")
                override fun exportPages(snapshotId: String): List<ExportPageRow> = error("Not a snapshot reader")
            }
            val freezer = RoomExportSnapshots(catalog, snapshots, {}, { null }, profile.rendererVersion, profile.encodingVersion, profile.backend, profile.precision, { 1000L }, Dispatchers.Unconfined)
            val frozen = freezer.freezeExport(fixture.document, 1, ExportSettings(ExportFormat.PNG, fixture.pageIds.map { PageOutputSettings(it, OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)) }, null, null, emptyList()))
            val assembly = jobs.values.single { it.kind == "EXPORT_ASSEMBLY" }
            val id = ExportAssemblyInput.verify(JobId(assembly.jobId), frozen, assembly).first()
            page = frozen.pages.first()
            job = jobs.getValue(id.value).copy(state = "ADMITTED")
            val result = RoomJobLeases(metadata, leases, { liveness }, { 1000L }, { elapsed }, Dispatchers.Unconfined).acquireAdmitted(id, owner, 50)
            initialLease = (result as AcquireLeaseResult.Granted).lease
        }

        suspend fun verify(): RoomFrozenRenderReads.Verified {
            initialize()
            return RoomFrozenRenderReads(catalog, { _, _ -> true },
                { FrozenRenderJobIdentityRow(job.jobId, job.kind, job.idempotenceKey, job.canonicalInputSha256, job.input) },
                ::pins, { true }, profile, Dispatchers.Unconfined).verified(JobId(job.jobId), page)
        }

        private fun pins(id: String): List<String> {
            check(catalog.inTransaction)
            onPinRead()
            return pinOverride ?: catalog.references.values.filter { it.ownerType == "JOB" && it.ownerId == id }.map { it.sourceId }
        }

        private val port = object : RenderIntentQueries {
            override fun renderIntentHasMalformedAttempts(jobId: String): Boolean {
                check(catalog.inTransaction)
                attemptScans++
                return rows.any { it.kind == "RENDER" && it.ownerId == jobId && it.attempt <= 0 } ||
                    opaqueAttemptKeys.any { it.kind == "RENDER" && it.owner == jobId && (it.attempt !is Long || it.attempt <= 0) }
            }

            override fun renderJobLeaseReadIsBounded(jobId: String): Boolean {
                check(catalog.inTransaction)
                return leaseBounded
            }
            override fun renderIntentReadIsBounded(jobId: String, attempt: Long, expectedPayloadBytes: Int): Boolean? {
                check(catalog.inTransaction)
                journalReads++
                return row(jobId, attempt)?.let { journalBounded && it.payload.size == expectedPayloadBytes }
            }
            override fun renderIntentRow(jobId: String, attempt: Long): FileTransactionRow? {
                check(catalog.inTransaction)
                journalProjections++
                return row(jobId, attempt)
            }
            override fun insertRenderIntent(row: FileTransactionRow) {
                check(catalog.inTransaction)
                check(rows.none { it.transactionId == row.transactionId || it.kind == row.kind && it.ownerId == row.ownerId && it.attempt == row.attempt })
                rows += row; writes++; afterWrite()
            }
            override fun markRenderIntentCopying(transactionId: String, jobId: String, attempt: Long, updatedAt: Long): Int {
                check(catalog.inTransaction)
                val row = row(jobId, attempt) ?: return 0
                if (!copyCas || row.transactionId != transactionId || row.state != "INTENT") return 0
                rows[rows.indexOf(row)] = row.copy(state = "COPYING", updatedAt = updatedAt)
                writes++; afterWrite()
                return 1
            }
            override fun advanceRenderFilePhase(previous: FileTransactionRow, next: FileTransactionRow): Int =
                error("Intent tests do not execute file phases")
        }

        private val intents = RoomRenderIntents(metadata, leases, port,
            { id, bytes -> check(catalog.inTransaction); job.takeIf { it.jobId == id }?.let { it.input.size == bytes } },
            ::pins, { liveness }, { 1000L }, { elapsed }, { id(++idsIssued + 10) }, Dispatchers.Unconfined)

        private fun row(jobId: String, attempt: Long) = rows.singleOrNull { it.kind == "RENDER" && it.ownerId == jobId && it.attempt == attempt }

        suspend fun prepare(lease: JobLease? = null, verified: RoomFrozenRenderReads.Verified? = null): RenderFileIntent {
            initialize()
            return intents.prepare(verified ?: verify(), lease ?: this.lease)
        }

        suspend fun copying(intent: RenderFileIntent) = intents.copying(verify(), lease, intent)

        suspend fun refuses(code: OperationErrorCode, action: suspend () -> Any) {
            try {
                action()
                fail("Expected $code")
            } catch (failure: RepositoryFailure) {
                assertEquals(code, failure.error.code)
                assertEquals(code == OperationErrorCode.STORAGE_FAILED, failure.error.retryable)
                assertFalse(failure.error.safeMessageKey.contains("private"))
            }
        }
    }

    private companion object {
        val profile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")
        fun id(n: Int): String = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"
        val owner = id(1)
        val nextOwner = id(2)
        val otherJob = id(3)
        val transactionId = id(11)
        val renderId = id(12)
    }
}
