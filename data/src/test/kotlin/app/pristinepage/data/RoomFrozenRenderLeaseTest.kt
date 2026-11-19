package app.pristinepage.data

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RoomFrozenRenderLeaseTest {
    @Test fun admittedJobGetsOneLiveGrantWithIndependentClocks() = runTest {
        val f = pending()
        f.scrambleCasArgument = true
        f.admit()
        val before = f.rows()
        assertEquals(AcquireLeaseResult.Granted(JobLease(f.id, owner, 1)), f.acquire())
        assertEquals(before.afterFirstGrant(), f.rows())
        assertEquals(AcquireLeaseResult.Busy("RUNNING"), f.acquire())
        assertEquals(listOf(proofReads, proofReads + "admit", leaseReads + "update", leaseReads), f.transactions)
        assertEquals(2, f.fullReads)
        assertEquals(1, f.leaseUpdates)
    }

    @Test fun stableByteDistinctNormalizedEquivalentPinIsNotRebound() = runTest {
        val f = pending()
        f.replacePin()
        f.admit()
        val before = f.rows()
        assertEquals(alternateSource, f.receipt.proof.pinSourceId)
        assertEquals(AcquireLeaseResult.Granted(JobLease(f.id, owner, 1)), f.acquire())
        assertEquals(before.afterFirstGrant(), f.rows())
        assertEquals(1, f.leaseUpdates)
    }

    @Test fun wrongRawJobExtentStopsBeforeNarrowOrFullProjection() = runTest {
        val f = admitted()
        f.row = f.row!!.let { it.copy(input = it.input + 0.toByte()) }
        f.reject("frozen_render_job_metadata_invalid", listOf("job bounds"))
    }

    @Test fun failedLeaseScalarGuardStopsBeforeFullProjection() = runTest {
        val f = admitted()
        f.row = f.row!!.copy(checkpoint = byteArrayOf())
        f.reject("frozen_render_job_metadata_invalid", proofReads + "lease bounds")
    }

    @Test fun equalLengthInputDriftStopsBeforeFullProjection() = runTest {
        val f = admitted()
        f.row = f.row!!.let { it.copy(input = it.input.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }) }
        f.reject("frozen_render_job_invalid", proofReads)
    }

    @Test fun canonicalLengthKeyDriftStopsBeforeFullProjection() = runTest {
        val f = admitted()
        f.row = f.row!!.copy(idempotenceKey = "page-render-v1:" + "0".repeat(64))
        f.reject("frozen_render_job_invalid", proofReads)
    }

    @Test fun canonicalLengthDigestDriftStopsBeforeFullProjection() = runTest {
        val f = admitted()
        f.row = f.row!!.copy(canonicalInputSha256 = "0".repeat(64))
        f.reject("frozen_render_job_invalid", proofReads)
    }

    @Test fun freshlyVerifiableReplacementCannotUseTheFirstAdmissionsAuthority() = runTest {
        val f = admitted()
        val original = f.binding()
        f.replacePin()
        val fresh = f.readVerified()
        assertEquals(alternateSource, fresh.pinSourceId)
        val replacement = f.binding()
        assertNotEquals(original.source.sourceId, replacement.source.sourceId)
        assertNotEquals(original.blob.sourceBlobId, replacement.blob.sourceBlobId)
        assertNotEquals(original.blob.byteSha256, replacement.blob.byteSha256)
        assertEquals(original.source.pixelSha256, replacement.source.pixelSha256)
        assertEquals(original.source.conversionFingerprint, replacement.source.conversionFingerprint)
        assertEquals(f.receipt.proof.canonicalInputSha256, fresh.canonicalInputSha256)
        assertEquals(original, f.receipt.proof.pinnedBinding)
        // The fresh read authenticates B but conveys no admission authority. The separate writer must compare against A.
        assertEquals(listOf(proofReads, proofReads + "admit", proofReads), f.transactions)
        f.reject("frozen_render_lease_binding_changed", proofReads)
        assertEquals(4, f.transactions.size)
        assertEquals(0, f.ownerReads)
    }

    @Test fun nonpositiveLiveSourceOrBlobCounterStopsBeforeFullProjection() = runTest {
        for (count in listOf(0L, -1L)) {
            for (changeSource in listOf(true, false)) {
                val f = admitted()
                val (source, blob) = f.binding()
                // Keep the fake scalar guard permissive here, so a passing test requires the typed binding check.
                f.supplyCounterDriftToTypedProof = true
                if (changeSource) f.catalog.sources[source.sourceId] = source.copy(refCount = count)
                else f.catalog.blobs[blob.sourceBlobId] = blob.copy(sourceCount = count)
                f.reject("frozen_render_job_invalid", proofReads)
                assertEquals(0, f.ownerReads)
            }
        }
    }

    @Test fun pinnedImmutableSourceOrBlobDriftStopsBeforeFullProjection() = runTest {
        for (changeSource in listOf(true, false)) {
            val f = admitted()
            val (source, blob) = f.binding()
            if (changeSource) f.catalog.sources[source.sourceId] = source.copy(exifOrientation = 2)
            else f.catalog.blobs[blob.sourceBlobId] = blob.copy(byteCount = blob.byteCount + 1)
            // These changes still pass one-view proof and keep the exact SourceId. The verified binding must also match.
            FrozenRenderJobProof.verifyPin(f.page, source.sourceId, f.binding())
            f.reject("frozen_render_lease_binding_changed", proofReads)
        }
    }

    @Test fun changedPositiveCountersDoNotAlterLeaseIdentityOrOwnership() = runTest {
        val f = admitted()
        val (source, blob) = f.binding()
        f.catalog.sources[source.sourceId] = source.copy(refCount = source.refCount + 1)
        f.catalog.blobs[blob.sourceBlobId] = blob.copy(sourceCount = blob.sourceCount + 1)
        val before = f.rows()
        assertEquals(AcquireLeaseResult.Granted(JobLease(f.id, owner, 1)), f.acquire())
        assertEquals(before.afterFirstGrant(), f.rows())
    }

    @Test fun missingJobCannotReachAnyRowProjection() = runTest {
        val f = admitted()
        f.row = null
        f.reject("frozen_render_job_unavailable", listOf("job bounds"), OperationErrorCode.SOURCE_UNAVAILABLE)
    }

    @Test fun earlierReceiptDoesNotOverrideLivePendingOrTerminalState() = runTest {
        for (state in listOf("PENDING", "SUCCEEDED", "CANCELED")) {
            val f = admitted()
            f.row = f.row!!.copy(state = state)
            f.reject("job_not_admitted", leaseReads, OperationErrorCode.INVALID_INPUT)
            assertEquals(0, f.ownerReads)
        }
    }

    @Test fun fullRowMustMatchEvenAfterNarrowIdentityAndPinSucceeded() = runTest {
        val changes: List<(JobRow) -> JobRow> = listOf(
            { it.copy(input = ByteArray(it.input.size)) },
            { it.copy(idempotenceKey = "page-render-v1:" + "0".repeat(64)) },
            { it.copy(canonicalInputSha256 = "0".repeat(64)) },
            { it.copy(kind = "RECONCILE") },
            { it.copy(jobId = owner) },
            { it.copy(snapshotId = owner, pageOrdinal = 0) },
        )
        for (change in changes) {
            val f = admitted()
            // Deliberately inconsistent port, not a claim about native Room isolation.
            f.fullProjection = change
            f.reject("frozen_render_job_invalid", leaseReads)
            assertEquals(0, f.ownerReads)
        }
    }

    @Test fun knownEndedAndUnknownRequestersCannotRunAnOwnerFreeAdmittedJob() = runTest {
        for (state in listOf(LeaseOwnerState.ENDED, LeaseOwnerState.UNKNOWN)) {
            val f = admitted()
            f.owners[owner] = state
            f.reject("job_owner_not_alive", leaseReads, OperationErrorCode.INVALID_INPUT)
            assertEquals(1, f.ownerReads)
        }
    }

    @Test fun cancellationDuringLivenessCheckCannotWriteLease() = runTest {
        val f = admitted()
        val before = f.rows()
        launch {
            val context = currentCoroutineContext()
            f.onOwnerRead = { context.cancel(CancellationException("lease canceled before update")) }
            try {
                f.acquire()
                fail("Cancellation must propagate, not return a result")
            } catch (canceled: CancellationException) {
                assertEquals("lease canceled before update", canceled.message)
            }
        }.join()
        f.assertNoUpdate(before, leaseReads)
        assertEquals(1, f.ownerReads)
    }

    @Test fun auditClockCancellationCannotReachGuardedCas() = runTest {
        val f = admitted()
        val before = f.rows()
        launch {
            val context = currentCoroutineContext()
            f.onAudit = { context.cancel(CancellationException("lease canceled during audit sample")) }
            try {
                f.acquire()
                fail("Cancellation must propagate, not return a result")
            } catch (canceled: CancellationException) {
                assertEquals("lease canceled during audit sample", canceled.message)
            }
        }.join()
        f.assertNoUpdate(before, leaseReads)
    }

    @Test fun expiredEndedOwnerAllowsDistinctLiveTakeoverThroughSameWriter() = runTest {
        val f = admitted()
        assertEquals(AcquireLeaseResult.Granted(JobLease(f.id, owner, 1)), f.acquire())
        f.owners[owner] = LeaseOwnerState.ENDED
        f.owners[nextOwner] = LeaseOwnerState.ALIVE
        f.elapsed = 539
        assertEquals(AcquireLeaseResult.Busy("RUNNING"), f.acquire(nextOwner))
        f.elapsed = 540
        assertEquals(AcquireLeaseResult.Busy("RUNNING"), f.acquire())
        assertEquals(AcquireLeaseResult.Granted(JobLease(f.id, nextOwner, 2)), f.acquire(nextOwner))
        assertEquals(2, f.leaseUpdates)
        assertEquals(2L, f.row!!.attempt)
        assertEquals(580L, f.row!!.leaseUntil)
    }

    @Test fun zeroRowCasIsBusyNotGranted() = runTest {
        val f = admitted()
        val before = f.rows()
        f.missCas = true
        assertEquals(AcquireLeaseResult.Busy("ADMITTED"), f.acquire())
        assertEquals(1, f.leaseUpdates)
        assertEquals(leaseReads + "update", f.transactions.last())
        assertEquals(before, f.rows())
    }

    @Test fun guardStorageFailureIsRetryableWithoutWideReadOrWrite() = runTest {
        val f = admitted()
        val before = f.rows()
        f.onLeaseBounds = { error("Private database detail") }
        assertEquals(AcquireLeaseResult.Rejected(OperationError(OperationErrorCode.STORAGE_FAILED, true,
            "job_lease_write_failed", f.id.value)), f.acquire())
        f.assertNoUpdate(before, proofReads + "lease bounds")
    }

    private suspend fun pending() = Fixture().apply { freeze() }
    private suspend fun admitted() = pending().apply { admit() }

    private class Fixture : ExportSnapshotQueries, JobLeaseQueries {
        private val repository = RepositoryFixture(count = 1)
        val catalog = repository.metadata
        private val frozenJobs = mutableListOf<JobRow>()
        lateinit var page: ExportPageSnapshot
        val id: JobId get() = JobId(canonical.jobId)
        lateinit var receipt: RoomFrozenRenderAdmission.Admitted
        var row: JobRow? = null
        private lateinit var canonical: JobRow
        val transactions = mutableListOf<MutableList<String>>()
        var fullReads = 0
        var leaseUpdates = 0
        var ownerReads = 0
        var elapsed = 500L
        val owners = mutableMapOf(owner to LeaseOwnerState.ALIVE)
        var onOwnerRead: () -> Unit = {}
        var onAudit: () -> Unit = {}
        var onLeaseBounds: () -> Unit = {}
        var fullProjection: (JobRow) -> JobRow = { it }
        var missCas = false
        var scrambleCasArgument = false
        var supplyCounterDriftToTypedProof = false

        private val metadata = object : MetadataAccess {
            override val queries = object : CatalogQueries by catalog {
                override fun source(id: String): SourceRow? {
                    record("source")
                    assertEquals(pins().single(), id)
                    return catalog.source(id)
                }
                override fun sourceBlob(id: String): SourceBlobRow? {
                    record("blob")
                    assertEquals(binding().blob.sourceBlobId, id)
                    return catalog.sourceBlob(id)
                }
            }
            override fun <T> transaction(action: () -> T): T {
                assertFalse("Nested metadata transaction", catalog.inTransaction)
                transactions.add(mutableListOf())
                return catalog.transaction(action)
            }
        }

        suspend fun freeze() {
            val freezer = RoomExportSnapshots(catalog, this, {}, { null }, profile.rendererVersion, profile.encodingVersion,
                profile.backend, profile.precision, { 1_000 }, Dispatchers.Unconfined)
            val settings = ExportSettings(ExportFormat.PNG, listOf(PageOutputSettings(repository.pageIds.single(), output)), null, null, emptyList())
            val snapshot = freezer.freezeExport(repository.document, 1, settings)
            val assembly = frozenJobs.single { it.kind == "EXPORT_ASSEMBLY" }
            page = snapshot.pages.single()
            val referenced = ExportAssemblyInput.verify(JobId(assembly.jobId), snapshot, assembly)[page.ordinal]
            canonical = frozenJobs.single { it.jobId == referenced.value }
            row = canonical.copy(input = canonical.input.copyOf())
        }

        suspend fun readVerified(): RoomFrozenRenderReads.Verified =
            RoomFrozenRenderReads(metadata, ::renderJobReadIsBounded, ::renderJobIdentity,
                ::boundedRenderJobPinSourceIds, ::sourceBounds, profile, Dispatchers.Unconfined).verified(id, page)

        suspend fun admit() {
            assertEquals("PENDING", row!!.state)
            val before = rows()
            var preflighted: ExportSourceBinding? = null
            receipt = checkNotNull(RoomFrozenRenderAdmission(
                metadata, profile, { actual, pin ->
                    assertFalse(catalog.inTransaction)
                    assertSame(page, actual)
                    assertEquals(binding(), pin)
                    preflighted = pin
                    null
                }, ::renderJobReadIsBounded, ::renderJobIdentity, ::boundedRenderJobPinSourceIds,
                ::sourceBounds, ::admitPending, { 2_000 }, Dispatchers.Unconfined,
            ).admit(id, page))
            assertEquals(before.copy(job = before.job!!.copy(state = "ADMITTED", updatedAt = 2_000)), rows())
            assertEquals(listOf(proofReads, proofReads + "admit"), transactions.takeLast(2))
            assertEquals(0, fullReads)
            assertEquals(0, leaseUpdates)
            assertSame(page, receipt.proof.page)
            assertEquals(id, receipt.proof.jobId)
            assertEquals(canonical.canonicalInputSha256, receipt.proof.canonicalInputSha256)
            assertEquals(pins().single(), receipt.proof.pinSourceId)
            assertSame(preflighted, receipt.proof.pinnedBinding)
        }

        private fun admitPending(id: String, key: String, digest: String, input: ByteArray, audit: Long): Int {
            record("admit")
            assertEquals(this.id.value, id)
            val pending = row!!
            assertEquals("PENDING", pending.state)
            assertEquals(pending.idempotenceKey, key)
            assertEquals(pending.canonicalInputSha256, digest)
            assertArrayEquals(pending.input, input)
            row = pending.copy(state = "ADMITTED", updatedAt = audit)
            if (scrambleCasArgument) input.fill(0)
            return 1
        }

        fun replacePin() {
            assertFalse(catalog.inTransaction)
            val (source, blob) = binding()
            checkNotNull(catalog.references.remove(Triple("JOB", id.value, source.sourceId)))
            catalog.sources[source.sourceId] = source.copy(refCount = source.refCount - 1)
            val otherBlob = "00000000-0000-0000-0000-0000000000b2"
            catalog.sources[alternateSource] = source.copy(sourceId = alternateSource, sourceBlobId = otherBlob, refCount = 1)
            catalog.blobs[otherBlob] = blob.copy(sourceBlobId = otherBlob, byteSha256 = "d".repeat(64),
                relativePath = "sources/$otherBlob/original.bin", sourceCount = 1)
            catalog.references[Triple("JOB", id.value, alternateSource)] = SourceReferenceRow("JOB", id.value, alternateSource, 0)
            assertNotEquals(page.byteHash, binding().blob.byteSha256)
            FrozenRenderJobProof.verifyPin(page, alternateSource, binding())
        }

        fun pins() = catalog.references.values.asSequence()
            .filter { it.ownerType == "JOB" && it.ownerId == id.value }.take(2).map { it.sourceId }.toList()

        fun binding(): ExportSourceBinding {
            val source = catalog.sources.getValue(pins().single())
            return ExportSourceBinding(source, catalog.blobs.getValue(source.sourceBlobId))
        }

        private fun sourceBounds(id: String): Boolean? {
            record("source bounds")
            assertEquals(pins().single(), id)
            return if (supplyCounterDriftToTypedProof) true else catalog.sourceReadIsBounded(id)
        }

        override fun renderJobReadIsBounded(jobId: String, expectedInputBytes: Int): Boolean? {
            record("job bounds")
            assertEquals(id.value, jobId)
            assertEquals(canonical.input.size, expectedInputBytes)
            return row?.let { it.input.size == expectedInputBytes }
        }

        override fun renderJobIdentity(jobId: String): FrozenRenderJobIdentityRow? {
            record("narrow job")
            assertEquals(id.value, jobId)
            return row?.let { FrozenRenderJobIdentityRow(it.jobId, it.kind, it.idempotenceKey, it.canonicalInputSha256, it.input.copyOf()) }
        }

        override fun boundedRenderJobPinSourceIds(jobId: String): List<String> {
            record("pins")
            assertEquals(id.value, jobId)
            return pins()
        }

        private fun leaseBounds(jobId: String): Boolean? {
            record("lease bounds")
            assertEquals(id.value, jobId)
            onLeaseBounds()
            return row?.let { it.checkpoint?.isEmpty() != true }
        }

        override fun job(id: String): JobRow? {
            fullReads++
            record("full job")
            assertEquals(this.id.value, id)
            return row?.let(fullProjection)
        }

        override fun updateLease(row: JobRow, expectedGeneration: Long): Int {
            leaseUpdates++
            record("update")
            val current = this.row!!
            assertEquals(current.jobId, row.jobId)
            assertEquals(current.leaseGeneration, expectedGeneration)
            if (missCas || current.leaseGeneration != expectedGeneration) return 0
            this.row = current.copy(state = row.state, attempt = row.attempt, leaseOwner = row.leaseOwner, leaseUntil = row.leaseUntil,
                leaseGeneration = row.leaseGeneration, checkpoint = row.checkpoint, errorCode = row.errorCode, updatedAt = row.updatedAt)
            return 1
        }

        private fun record(name: String) {
            assertTrue("$name outside its writer/reader", catalog.inTransaction)
            transactions.last().add(name)
        }

        fun rows(): Stored = Stored(row?.copy(input = noInput, checkpoint = null), row?.input?.toList(),
            row?.checkpoint?.toList(), catalog.references.toMap(), catalog.sources.toMap(), catalog.blobs.toMap())

        suspend fun reject(key: String, reads: List<String>, code: OperationErrorCode = OperationErrorCode.INTEGRITY_FAILED) {
            val before = rows()
            assertEquals(AcquireLeaseResult.Rejected(OperationError(code, false, key, id.value)), acquire())
            assertNoUpdate(before, reads)
        }

        fun assertNoUpdate(before: Stored, reads: List<String>) {
            assertEquals(reads, transactions.last())
            assertEquals(if ("full job" in reads) 1 else 0, fullReads)
            assertEquals(0, leaseUpdates)
            assertEquals(before, rows())
        }

        private val registry = LeaseOwnerLiveness { requested ->
            ownerReads++
            assertTrue(catalog.inTransaction)
            onOwnerRead()
            owners[requested] ?: LeaseOwnerState.UNKNOWN
        }

        suspend fun acquire(requested: String = owner): AcquireLeaseResult {
            assertFalse(catalog.inTransaction)
            return RoomJobLeases.FrozenRenderAcquisition(
                metadata, this, registry, ::renderJobReadIsBounded, ::renderJobIdentity, ::boundedRenderJobPinSourceIds,
                ::sourceBounds, ::leaseBounds, { onAudit(); 7_000L }, { elapsed }, Dispatchers.Unconfined,
            ).acquire(receipt, requested, 40L)
        }

        override fun insertSnapshot(row: ExportSnapshotRow) = Unit
        override fun insertExportPage(row: ExportPageRow) = Unit
        override fun insertExportJob(row: JobRow) { frozenJobs.add(row) }
        override fun renderJobIdByKey(key: String) = frozenJobs.singleOrNull { it.idempotenceKey == key }?.jobId
        override fun snapshotMetadataIsBounded(snapshotId: String): Boolean? = error("Not a snapshot reader")
        override fun exportPagesAreBounded(snapshotId: String): Boolean = error("Not a snapshot reader")
        override fun snapshot(id: String): ExportSnapshotRow? = error("Not a snapshot reader")
        override fun exportPages(snapshotId: String): List<ExportPageRow> = error("Not a snapshot reader")
    }

    private data class Stored(
        val job: JobRow?, val input: List<Byte>?, val checkpoint: List<Byte>?,
        val references: Map<Triple<String, String, String>, SourceReferenceRow>,
        val sources: Map<String, SourceRow>, val blobs: Map<String, SourceBlobRow>,
    ) {
        fun afterFirstGrant() = copy(job = job!!.copy(state = "RUNNING", attempt = 1, leaseGeneration = 1,
            leaseOwner = owner, leaseUntil = 540, updatedAt = 7_000))
    }

    private companion object {
        val noInput = byteArrayOf()
        const val owner = "00000000-0000-0000-0000-0000000000a1"
        const val nextOwner = "00000000-0000-0000-0000-0000000000a2"
        const val alternateSource = "00000000-0000-0000-0000-0000000000b1"
        val output = OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)
        val profile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")
        val proofReads = listOf("job bounds", "narrow job", "pins", "source bounds", "source", "blob")
        val leaseReads = proofReads + listOf("lease bounds", "full job")
    }
}
