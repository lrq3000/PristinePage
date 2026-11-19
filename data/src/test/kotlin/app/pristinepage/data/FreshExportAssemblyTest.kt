package app.pristinepage.data

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FreshExportAssemblyTest {
    @Test fun firstClaimUsesFrozenOrderAndSeparateClockDomains() = runTest {
        val store = Store()
        val assembly = store.consumer()
        val prepared = assembly.prepare(snapshotId)
        store.catalog.documents.clear()
        store.catalog.pages.clear()
        store.catalog.recipes.clear()
        val admitted = assembly.admit(prepared)
        assertEquals("ADMITTED", store.job.state)
        assertEquals(1000L, store.job.updatedAt)
        assertEquals(0L, store.job.leaseGeneration)
        assertArrayEquals(store.originalInput, store.job.input)
        assertEquals(2, store.fullReads)
        store.wall = 1100
        val acquired = store.consumer().acquire(admitted, owner, 20L)
        assertSame(admitted, acquired.admitted)
        val lease = acquired.lease
        assertEquals(JobLease(jobId, owner, 1L), lease)
        assertEquals("RUNNING", store.job.state)
        assertEquals(1L, store.job.attempt)
        assertEquals(1L, store.job.leaseGeneration)
        assertEquals(owner, store.job.leaseOwner)
        assertEquals(27L, store.job.leaseUntil)
        assertEquals(1100L, store.job.updatedAt)
        assertEquals("FROZEN", store.header.state)
        assertArrayEquals(store.originalInput, store.job.input)
        assertEquals(4, store.fullReads)
        assertEquals(listOf("PENDING", "ADMITTED", "ADMITTED", "RUNNING"), store.guardedPhases)
        val snapshot = prepared.snapshot
        assertEquals(store.frozenPages.map { it.originalPageId }, snapshot.pages.map { it.pageId.value })
        assertEquals(refs, prepared.orderedJobIds)
        assertEquals(1, store.resourceChecks)
    }

    @Test fun rawSnapshotRefusalNeverMaterializesItsBlockedProjectionOrJoinsJob() = runTest {
        for (field in listOf("header", "pages")) {
            val store = Store(block = field)
            fails(OperationErrorCode.INTEGRITY_FAILED) { store.consumer().prepare(snapshotId) }
            assertEquals(
                if (field == "header") listOf("header guard") else listOf("header guard", "header row", "pages guard"),
                store.events,
            )
        }
    }

    @Test fun indexedLookupCannotTreatAbsenceOrMalformedMatchAsAuthority() = runTest {
        for ((mode, code) in listOf("missing" to OperationErrorCode.SOURCE_UNAVAILABLE, "malformed" to OperationErrorCode.INTEGRITY_FAILED)) {
            val store = Store(block = mode)
            fails(code) { store.consumer().prepare(snapshotId) }
            assertEquals("job key", store.events.last())
            assertEquals(0, store.fullReads)
        }
    }

    @Test fun crossStorePreparationAndAdmissionRejectBeforeAnyReadOrMutation() = runTest {
        val store = Store()
        val assembly = store.consumer()
        val other = Store()
        val receiver = other.consumer()
        val prepared = assembly.prepare(snapshotId)
        fails(OperationErrorCode.INTEGRITY_FAILED) { receiver.admit(prepared) }
        val admitted = assembly.admit(prepared)
        fails(OperationErrorCode.INTEGRITY_FAILED) { receiver.acquire(admitted, owner, 20L) }
        assertTrue(other.events.isEmpty())
        assertEquals("PENDING", other.job.state)
    }

    @Test fun preflightChangesCannotAuthorizeAFullJobRead() = runTest {
        for (mutation in listOf("reorder", "reference", "page-content", "header-content", "job-id", "checkpoint")) {
            val store = Store()
            val assembly = store.consumer()
            val prepared = assembly.prepare(snapshotId)
            store.preflight = {
                when (mutation) {
                    "reorder" -> store.replacePayload(refs.reversed())
                    "reference" -> store.replacePayload(listOf(refs.first(), JobId(owner)))
                    "page-content" -> store.frozenPages[0].providers[0] = '!'.code.toByte()
                    "header-content" -> store.header.settings[0] = 0
                    "job-id" -> store.job = store.job.copy(jobId = owner)
                    "checkpoint" -> store.job = store.job.copy(checkpoint = byteArrayOf(1))
                }
            }
            val code = if (mutation == "checkpoint") OperationErrorCode.REVISION_CONFLICT else OperationErrorCode.INTEGRITY_FAILED
            fails(code) { assembly.admit(prepared) }
            assertEquals(mutation, 0, store.fullReads)
            assertEquals(mutation, 0, store.writes)
        }
    }

    @Test fun ineligibleAndUnknownSnapshotStatesStayDistinctAtEachStage() = runTest {
        val expected = mapOf(
            "CANCELED" to OperationErrorCode.CANCELED,
            "FAILED" to OperationErrorCode.PROCESSING_FAILED,
            "EXPIRED" to OperationErrorCode.SESSION_EXPIRED,
            "FUTURE_STATE" to OperationErrorCode.UNSUPPORTED_VERSION,
            "PRIVATE_READY" to OperationErrorCode.REVISION_CONFLICT,
            "ASSEMBLING" to OperationErrorCode.REVISION_CONFLICT,
            "RENDERING" to OperationErrorCode.REVISION_CONFLICT,
        )
        for ((state, code) in expected) for (stage in 0..2) {
            val store = Store()
            val assembly = store.consumer()
            if (stage == 0) store.header = store.header.copy(state = state)
            val failure: suspend () -> Unit = {
                val prepared = assembly.prepare(snapshotId)
                if (stage == 1) store.header = store.header.copy(state = state)
                val admitted = assembly.admit(prepared)
                if (stage == 2) store.header = store.header.copy(state = state)
                assembly.acquire(admitted, owner, 20L)
            }
            fails(code, failure)
            assertEquals(state, store.header.state)
            assertEquals(if (stage == 2) "ADMITTED" else "PENDING", store.job.state)
            assertEquals(if (stage == 2) 2 else 0, store.fullReads)
        }
    }

    @Test fun retentionEqualityFailsBeforeAdmissionAndAcquisition() = runTest {
        for (beforeAcquire in listOf(false, true)) {
            val store = Store()
            val assembly = store.consumer()
            val prepared = assembly.prepare(snapshotId)
            if (beforeAcquire) {
                val admitted = assembly.admit(prepared)
                store.wall = store.header.retainedUntil
                fails(OperationErrorCode.SESSION_EXPIRED) { assembly.acquire(admitted, owner, 20L) }
            } else {
                store.preflight = { store.wall = store.header.retainedUntil }
                fails(OperationErrorCode.SESSION_EXPIRED) { assembly.admit(prepared) }
            }
            assertEquals(if (beforeAcquire) 1 else 0, store.writes)
        }
    }

    @Test fun onlyACurrentlyLiveRequesterCanAcquireAndCompetingAttemptsNeverTakeOver() = runTest {
        val store = Store()
        val assembly = store.consumer()
        val admitted = assembly.admit(assembly.prepare(snapshotId))
        for (notAlive in listOf(LeaseOwnerState.ENDED, LeaseOwnerState.UNKNOWN)) {
            store.requester = notAlive
            fails(OperationErrorCode.INVALID_INPUT) { assembly.acquire(admitted, owner, 20L) }
            assertEquals("ADMITTED", store.job.state)
            assertEquals(1, store.writes)
        }
        store.requester = LeaseOwnerState.ALIVE
        store.job = store.job.copy(state = "RUNNING", attempt = 1, leaseGeneration = 1, leaseOwner = refs.first().value, leaseUntil = 1)
        val reads = store.fullReads
        fails(OperationErrorCode.REVISION_CONFLICT) { assembly.acquire(admitted, owner, 20L) }
        assertEquals(reads, store.fullReads)
        assertEquals(refs.first().value, store.job.leaseOwner)
        assertEquals(1, store.writes)
    }

    @Test fun aSameExtentReplacementAfterAdmissionAndAStaleAuditFieldCannotAcquire() = runTest {
        for (mutation in listOf("input", "audit")) {
            val store = Store()
            val assembly = store.consumer()
            val admitted = assembly.admit(assembly.prepare(snapshotId))
            if (mutation == "input") store.replacePayload(refs.reversed()) else store.job = store.job.copy(updatedAt = 1001)
            fails(if (mutation == "input") OperationErrorCode.INTEGRITY_FAILED else OperationErrorCode.REVISION_CONFLICT) {
                assembly.acquire(admitted, owner, 20L)
            }
            if (mutation == "input") assertEquals(2, store.fullReads)
            assertEquals(1, store.writes)
            assertEquals("ADMITTED", store.job.state)
        }
    }

    @Test fun aSuccessfulWriteCountWithWrongPostReadRollsBackBothStages() = runTest {
        for (acquiring in listOf(false, true)) {
            val store = Store()
            val assembly = store.consumer()
            val prepared = assembly.prepare(snapshotId)
            if (acquiring) {
                val admitted = assembly.admit(prepared)
                store.corruptAfterWrite = true
                val before = store.job
                fails(OperationErrorCode.INTEGRITY_FAILED) { assembly.acquire(admitted, owner, 20L) }
                assertEquals(before, store.job)
            } else {
                store.corruptAfterWrite = true
                val before = store.job
                fails(OperationErrorCode.INTEGRITY_FAILED) { assembly.admit(prepared) }
                assertEquals(before, store.job)
            }
            assertEquals(if (acquiring) 4 else 2, store.fullReads)
        }
    }

    @Test fun admissionRawInputExtentRefusesBeforeEqualityOrFullProjection() = runTest { assertRawInputExtent(acquiring = false) }
    @Test fun acquisitionRawInputExtentRefusesBeforeEqualityOrFullProjection() = runTest { assertRawInputExtent(acquiring = true) }

    private suspend fun assertRawInputExtent(acquiring: Boolean) {
        val store = Store()
        val assembly = store.consumer()
        val prepared = assembly.prepare(snapshotId)
        val admitted = if (acquiring) assembly.admit(prepared) else null
        store.job = store.job.copy(input = ByteArray(4145))
        val before = store.job
        val eventCount = store.events.size
        fails(OperationErrorCode.INTEGRITY_FAILED) {
            if (admitted == null) assembly.admit(prepared) else assembly.acquire(admitted, owner, 20)
        }
        assertEquals(before, store.job)
        val tail = store.events.drop(eventCount)
        assertTrue(tail.contains("job identity guard"))
        assertFalse(tail.contains("exact input"))
        assertFalse(tail.contains("full job"))
    }

    @Test fun normalConstructionCannotAcceptIndependentlyInjectedWriters() {
        val constructors = RoomFreshExportAssembly::class.java.constructors.filterNot { it.isSynthetic }
        assertEquals(listOf(PristineDatabase::class.java, LeaseOwnerLiveness::class.java, Function3::class.java),
            constructors.single().parameterTypes.toList())
    }

    @Test fun anIdenticalJobInAnotherStoreCannotAcceptTheReceipt() = runTest {
        val retained = Store()
        val unrelated = Store()
        val legitimate = retained.consumer()
        val prepared = legitimate.prepare(snapshotId)
        val admitted = legitimate.admit(prepared)
        unrelated.job = retained.job.copy(input = retained.job.input.copyOf())
        val priorRetained = retained.job
        val priorUnrelated = unrelated.job
        val count = retained.events.size
        fails(OperationErrorCode.INTEGRITY_FAILED) { unrelated.consumer().acquire(admitted, owner, 20) }
        assertEquals(priorRetained, retained.job)
        assertEquals(priorUnrelated, unrelated.job)
        assertEquals(count, retained.events.size)
        assertTrue(unrelated.events.isEmpty())
    }

    @Test fun admissionRetentionEqualityDuringFinalPageProjectionRollsBack() = runTest { assertFinalRetention(acquiring = false) }
    @Test fun acquisitionRetentionEqualityDuringFinalPageProjectionRollsBack() = runTest { assertFinalRetention(acquiring = true) }

    private suspend fun assertFinalRetention(acquiring: Boolean) {
        val store = Store()
        val assembly = store.consumer()
        val prepared = assembly.prepare(snapshotId)
        val admitted = if (acquiring) assembly.admit(prepared) else null
        val before = store.job
        store.duringFinalPages = { store.wall = store.header.retainedUntil }
        fails(OperationErrorCode.SESSION_EXPIRED) {
            if (admitted == null) assembly.admit(prepared) else assembly.acquire(admitted, owner, 20)
        }
        assertEquals(before, store.job)
        assertEquals(if (acquiring) 2 else 1, store.writes)
    }

    @Test fun elapsedEqualityDuringPostCasFullReadRollsBackTheGrantedRow() = runTest {
        val store = Store()
        val assembly = store.consumer()
        val admitted = assembly.admit(assembly.prepare(snapshotId))
        val before = store.job
        store.duringFinalJob = { store.elapsed = checkNotNull(store.job.leaseUntil) }
        fails(OperationErrorCode.REVISION_CONFLICT) { assembly.acquire(admitted, owner, 20) }
        assertEquals(before, store.job)
        assertEquals(2, store.writes)
        assertEquals(1000L, store.wall)
    }

    @Test fun requesterThatEndsDuringPostCasReadCannotReceiveAGrant() = runTest {
        val store = Store()
        val assembly = store.consumer()
        val admitted = assembly.admit(assembly.prepare(snapshotId))
        val before = store.job
        store.duringFinalJob = { store.requester = LeaseOwnerState.ENDED }
        fails(OperationErrorCode.INVALID_INPUT) { assembly.acquire(admitted, owner, 20) }
        assertEquals(before, store.job)
        assertEquals(2, store.writes)
    }

    @Test fun cancellationBeforeTheCallbackReturnsRollsBackButPostCommitCancellationDoesNot() = runTest {
        for (acquiring in listOf(false, true)) for (afterCommit in listOf(false, true)) {
            val store = Store()
            val assembly = store.consumer()
            val prepared = assembly.prepare(snapshotId)
            val admitted = if (acquiring) assembly.admit(prepared) else null
            val before = store.job
            var delivered = false
            var canceled = false
            launch(start = CoroutineStart.UNDISPATCHED) {
                val cancelHere: () -> Unit = { coroutineContext[Job]!!.cancel() }
                if (afterCommit) store.afterWrittenTransaction = cancelHere else store.duringFinalPages = cancelHere
                try {
                    if (admitted == null) assembly.admit(prepared) else assembly.acquire(admitted, owner, 20)
                    delivered = true
                } catch (_: CancellationException) {
                    canceled = true
                }
            }.join()
            assertTrue(canceled)
            assertFalse(delivered)
            if (!afterCommit) assertEquals(before, store.job)
            else assertEquals(if (acquiring) "RUNNING" else "ADMITTED", store.job.state)
            assertEquals(if (acquiring) 2 else 1, store.writes)
        }
    }

    @Test fun structuredOwnersCompeteForExactlyOneSameStoreFirstAttempt() = runTest {
        val store = Store()
        val owners = LocalOperationOwners()
        val first = store.consumer(liveness = owners)
        val second = store.consumer(liveness = owners)
        val admission = first.admit(first.prepare(snapshotId))
        val acquired = CompletableDeferred<JobLease>()
        val lost = CompletableDeferred<Unit>()
        launch {
            owners.run { requester ->
                val lease = first.acquire(admission, requester, 20).lease
                acquired.complete(lease)
                lost.await()
                assertEquals(LeaseOwnerState.ALIVE, owners.state(requester))
            }
        }
        launch {
            owners.run { requester ->
                val lease = acquired.await()
                assertNotEquals(lease.owner, requester)
                assertEquals(LeaseOwnerState.ALIVE, owners.state(lease.owner))
                fails(OperationErrorCode.REVISION_CONFLICT) { second.acquire(admission, requester, 20) }
                assertEquals(lease.owner, store.job.leaseOwner)
                lost.complete(Unit)
            }
        }.also { it.join() }
        val lease = acquired.await()
        assertEquals(1L, lease.generation)
        assertEquals(1L, store.job.attempt)
        assertEquals(2, store.writes)
        assertEquals(LeaseOwnerState.ENDED, owners.state(lease.owner))
    }

    private class Store(var block: String? = null) : RoomFreshExportAssembly.SyntheticStore() {
        private val fixture = RepositoryFixture()
        val catalog = fixture.metadata
        override val queries: CatalogQueries get() = catalog
        private val source = catalog.sources.getValue(fixture.source.value)
        private val blob = catalog.blobs.getValue(source.sourceBlobId)
        private val profile = OrdinaryExportProfile("renderer-v1", "codec-v1", "cpu", "reference")
        private val settings = ExportSettings(ExportFormat.PNG, fixture.pageIds.mapIndexed { ordinal, page ->
            PageOutputSettings(page, OutputSpec(PixelSize(3 - ordinal, 2), RenderPurpose.EXPORT))
        }, null, null, emptyList())
        val frozenPages = fixture.pageIds.mapIndexed { ordinal, page ->
            ExportPageRow(snapshotId.value, ordinal, page.value, source.sourceId, blob.sourceBlobId, blob.byteSha256,
                source.pixelSha256, 3, 2, source.conversionFingerprint, 1, 1, RecipeCodec.encode(fixture.initial),
                RecipeCodec.hash(fixture.initial), "[]".encodeToByteArray(), profile.providerDescriptor(),
                OrdinaryExportProfile.emptyAssetSetHash, profile.rendererVersion, 3 - ordinal, 2, 3 - ordinal, 2, "FAIL", null, null)
        }
        var header = ExportSnapshotRow(snapshotId.value, fixture.document.value, 1,
            ExportSnapshotIdentity.create(fixture.document, 1, settings, frozenPages).snapshotHash,
            ExportSettingsCodec.encode(settings), "FROZEN", 995, 1200)
        val originalInput = wire(header.snapshotSha256, refs)
        var job = JobRow(jobId.value, "EXPORT_ASSEMBLY", key, digest(originalInput), snapshotId.value, null,
            originalInput.copyOf(), "PENDING", 0, null, null, 0, null, null, 995, 995)
        var wall = 1000L
        var elapsed = 7L
        var duringFinalJob: () -> Unit = {}
        var duringFinalPages: () -> Unit = {}
        var afterWrittenTransaction: () -> Unit = {}
        var requester = LeaseOwnerState.ALIVE
        var preflight: () -> Unit = {}
        var resourceChecks = 0
        var corruptAfterWrite = false
        var fullReads = 0
        var writes = 0
        val events = mutableListOf<String>()
        val guardedPhases = mutableListOf<String>()
        private var exactInput = false
        private var phaseAdmitted = false
        private var leaseAdmitted = false
        private var wroteInTransaction = false

        override fun <T> transaction(action: () -> T): T {
            var committedWrite = false
            val result = catalog.transaction {
                val before = job
                try { action().also { committedWrite = wroteInTransaction } }
                catch (e: Throwable) { job = before; throw e }
                finally { exactInput = false; phaseAdmitted = false; leaseAdmitted = false; wroteInTransaction = false }
            }
            if (committedWrite) afterWrittenTransaction()
            return result
        }

        private fun event(name: String, id: String, expected: String = snapshotId.value) {
            check(catalog.inTransaction)
            assertEquals(name, expected, id)
            events += name
        }

        override fun snapshotMetadataIsBounded(snapshotId: String): Boolean? {
            event("header guard", snapshotId)
            return block != "header"
        }
        override fun snapshot(id: String): ExportSnapshotRow {
            event("header row", id)
            assertEquals("header guard", events[events.lastIndex - 1])
            return header
        }
        override fun exportPagesAreBounded(snapshotId: String): Boolean {
            event("pages guard", snapshotId)
            return block != "pages"
        }
        override fun exportPages(snapshotId: String): List<ExportPageRow> {
            event("pages rows", snapshotId)
            assertEquals("pages guard", events[events.lastIndex - 1])
            if (wroteInTransaction) duringFinalPages()
            return frozenPages
        }
        override fun renderJobIdByKey(key: String): String? {
            event("job key", key, Companion.key)
            return when (block) {
                "missing" -> null
                "malformed" -> ""
                else -> job.jobId
            }
        }
        override fun assemblyBounds(id: String): Boolean {
            event("job identity guard", id, jobId.value)
            return job.pageOrdinal == null && job.input.size in 184..4144
        }
        override fun assemblyIdentity(id: String): AssemblyJobIdentityRow {
            event("job identity row", id, jobId.value)
            assertEquals("job identity guard", events[events.lastIndex - 1])
            return AssemblyJobIdentityRow(job.jobId, job.kind, job.idempotenceKey, job.canonicalInputSha256, job.snapshotId, job.input)
        }
        override fun exactInputMatches(id: String, snapshot: String, key: String, digest: String, input: ByteArray): Boolean {
            event("exact input", id, jobId.value)
            assertEquals("job identity guard", events[events.lastIndex - 1])
            exactInput = job.jobId == id && job.snapshotId == snapshot && job.kind == "EXPORT_ASSEMBLY" &&
                job.pageOrdinal == null && job.idempotenceKey == key && job.canonicalInputSha256 == digest && job.input.contentEquals(input)
            return exactInput
        }
        override fun leaseBounds(id: String): Boolean {
            event("lease bounds", id, jobId.value)
            leaseAdmitted = true
            return true
        }
        override fun phaseMatches(id: String, state: String, attempt: Long, owner: String?, until: Long?): Boolean {
            event("phase", id, jobId.value)
            phaseAdmitted = job.state == state && job.attempt == attempt && job.leaseGeneration == attempt &&
                job.leaseOwner == owner && job.leaseUntil == until && job.checkpoint == null && job.errorCode == null
            return phaseAdmitted
        }
        override fun job(id: String): JobRow {
            event("full job", id, jobId.value)
            check(exactInput && phaseAdmitted && leaseAdmitted)
            fullReads++
            guardedPhases += job.state
            if (wroteInTransaction) duringFinalJob()
            // Model an inconsistent typed projection after a successful raw check, not a changed SQLite row.
            return if (corruptAfterWrite && wroteInTransaction) job.copy(createdAt = job.createdAt + 1) else job
        }
        override fun admitPending(id: String, snapshot: String, key: String, digest: String, input: ByteArray, previousUpdatedAt: Long, updatedAt: Long): Int {
            event("write ADMITTED", id, jobId.value)
            assertEquals("PENDING", job.state)
            assertEquals(job.snapshotId, snapshot)
            assertEquals(job.idempotenceKey, key)
            assertEquals(job.canonicalInputSha256, digest)
            assertArrayEquals(job.input, input)
            assertEquals(job.updatedAt, previousUpdatedAt)
            job = job.copy(state = "ADMITTED", updatedAt = updatedAt)
            writes++
            wroteInTransaction = true
            exactInput = false; phaseAdmitted = false; leaseAdmitted = false
            return 1
        }
        override fun updateLease(row: JobRow, expectedGeneration: Long): Int {
            event("write RUNNING", row.jobId, jobId.value)
            assertEquals(0L, expectedGeneration)
            assertEquals("ADMITTED", job.state)
            job = row
            writes++
            wroteInTransaction = true
            exactInput = false; phaseAdmitted = false; leaseAdmitted = false
            return 1
        }

        private fun liveness(ownerId: String): LeaseOwnerState {
            event("requester liveness", ownerId, owner)
            return requester
        }
        private suspend fun resourceCheck(snapshot: ExportSnapshot, ids: List<JobId>): OperationError? {
            check(!catalog.inTransaction)
            assertEquals(snapshotId, snapshot.snapshotId)
            assertEquals(refs, ids)
            resourceChecks++
            preflight()
            return null
        }
        fun replacePayload(ids: List<JobId>) {
            val bytes = wire(header.snapshotSha256, ids)
            job = job.copy(input = bytes, canonicalInputSha256 = digest(bytes))
        }

        fun consumer(liveness: LeaseOwnerLiveness = LeaseOwnerLiveness(::liveness)) =
            RoomFreshExportAssembly.forSyntheticTests(this, liveness, ::resourceCheck, { wall }, { elapsed }, Dispatchers.Unconfined)

        override fun insertSnapshot(row: ExportSnapshotRow): Unit = error("Unexpected snapshot insert")
        override fun insertExportPage(row: ExportPageRow): Unit = error("Unexpected page insert")
        override fun insertExportJob(row: JobRow): Unit = error("Unexpected job insert")
        override fun renderJobReadIsBounded(jobId: String, expectedInputBytes: Int): Boolean? = error("Not a render job")
        override fun renderJobIdentity(jobId: String): FrozenRenderJobIdentityRow? = error("Not a render job")
        override fun boundedRenderJobPinSourceIds(jobId: String): List<String> = error("Not a render job")
    }

    private suspend fun fails(code: OperationErrorCode, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (e: RepositoryFailure) {
            assertEquals(code, e.error.code)
        }
    }

    private companion object {
        val snapshotId = ExportSnapshotId("01234567-89ab-cdef-0123-456789abcdef")
        val jobId = JobId("abcdef01-2345-6789-abcd-ef0123456789")
        const val owner = "ab000000-0000-0000-0000-000000000001"
        const val key = "export-assembly-v1/01234567-89ab-cdef-0123-456789abcdef"
        val refs = listOf(JobId("fedcba98-7654-3210-fedc-ba9876543210"), JobId("00112233-4455-6677-8899-aabbccddeeff"))

        fun wire(hash: String, ids: List<JobId>) = ByteBuffer.allocate(224).apply {
            put("pristinepage-export-assembly-v1\u0000".encodeToByteArray())
            putInt(36); put(snapshotId.value.encodeToByteArray())
            putInt(64); put(hash.encodeToByteArray())
            putInt(2)
            ids.forEach { putInt(36); put(it.value.encodeToByteArray()) }
        }.array()

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    }
}
