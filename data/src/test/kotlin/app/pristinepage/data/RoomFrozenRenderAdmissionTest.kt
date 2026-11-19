package app.pristinepage.data

import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RoomFrozenRenderAdmissionTest {
    @Test fun admitsExactFrozenJobOnlyAfterSuspendingPreflightOutsideRoom() = runTest {
        val f = fixture()
        val before = f.stored()
        // The first projected BLOB is a borrowed buffer, not the backing row. The read must detach it before Room returns.
        f.afterFirstTransaction = { f.projectedInputs.single().fill(0) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val admission = async {
            f.admit {
                entered.complete(Unit)
                release.await()
                assertFalse("Preflight resumed inside Room", f.catalog.inTransaction)
                null
            }
        }
        entered.await()
        assertEquals(listOf(proofReads), f.transactions)
        assertEquals(0, f.casCalls)
        f.assertStored(before)
        release.complete(Unit)
        assertNotNull(admission.await())
        assertEquals(1, f.preflightCalls)
        assertEquals(1, f.casCalls)
        assertEquals(listOf(proofReads, proofReads + "cas"), f.transactions)
        f.assertStored(before.copy(job = before.job.copy(state = "ADMITTED", updatedAt = admissionTime)))
    }

    @Test fun propagatesCapabilityAndCapacityRefusalsWithoutOpeningWriter() = runTest {
        for ((code, key) in listOf(
            OperationErrorCode.PROVIDER_UNAVAILABLE to "ordinary_png_capability_unavailable",
            OperationErrorCode.RESOURCE_LIMIT to "ordinary_png_capacity_unavailable",
        )) {
            val f = fixture()
            val before = f.stored()
            val refusal = OperationError(code, false, key, f.jobId.value)
            f.refuses(refusal) { refusal }
            assertEquals(1, f.preflightCalls)
            assertEquals(listOf(proofReads), f.transactions)
            f.assertNoCas(before)
        }
    }

    @Test fun intactHistoricalProfileIsUnavailableBeforeCallingPreflight() = runTest {
        val old = OrdinaryExportProfile("renderer-v1-retained", "rgb8-v1-retained", "reference", "binary64")
        val f = fixture(old)
        val before = f.stored()
        // The same canonical freeze bytes must authenticate with their original profile; a broken fixture is not an unavailable capability.
        f.verifyRetainedProfile(old)
        f.refuses(f.failure("frozen_render_profile_unavailable", OperationErrorCode.PROVIDER_UNAVAILABLE))
        assertEquals(0, f.preflightCalls)
        assertEquals(listOf(proofReads), f.transactions)
        f.assertNoCas(before)
    }

    @Test fun canceledPreflightCannotOpenTheWriterOrReachCas() = runTest {
        val f = fixture()
        val before = f.stored()
        expectCancellation { canceled ->
            f.admit {
                currentCoroutineContext().cancel(canceled)
                null // No later suspension in the port to surface the cancellation for the consumer.
            }
        }
        assertEquals(1, f.preflightCalls)
        assertEquals(listOf(proofReads), f.transactions)
        f.assertNoCas(before)
    }

    @Test fun cancellationDuringWriterProofCannotReachCas() = runTest {
        val f = fixture()
        val before = f.stored()
        expectCancellation { canceled ->
            f.admit {
                val context = currentCoroutineContext()
                f.onBlobProjection = { context.cancel(canceled) }
                null
            }
        }
        assertEquals(1, f.preflightCalls)
        assertEquals(listOf(proofReads, proofReads), f.transactions)
        f.assertNoCas(before)
    }

    @Test fun failedInitialRawBoundsStopBeforeTheCorrespondingTypedProjection() = runTest {
        for (guard in listOf("job bounds", "source bounds")) {
            val f = fixture()
            val before = f.stored()
            f.refusedGuard = guard
            f.refuses(f.failure("frozen_render_job_metadata_invalid"))
            assertEquals(0, f.preflightCalls)
            assertEquals(listOf(readsThrough(guard)), f.transactions)
            f.assertNoCas(before)
        }
    }

    @Test fun failedWriterRawBoundsStopBeforeTheCorrespondingTypedProjectionAndCas() = runTest {
        for (guard in listOf("job bounds", "source bounds")) {
            fixture().refusesAfterPreflight("frozen_render_job_metadata_invalid", readsThrough(guard)) {
                if (guard == "job bounds") job = job.copy(input = job.input + 0.toByte())
                else refusedGuard = guard
            }
        }
    }

    @Test fun sameLengthJobBytesDriftingAfterPreflightAreRejectedBeforeCas() = runTest {
        fixture().refusesAfterPreflight("frozen_render_job_invalid") {
            // Keep the stored key and hash unchanged; an extent or digest-field comparison alone accepts this row.
            job = job.copy(input = job.input.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        }
    }

    @Test fun stableNormalizedEquivalentJobPinIsAllowedWithoutRepinningToTheFrozenPage() = runTest {
        val f = fixture()
        f.retainEquivalentOriginal()
        f.attachEquivalentPin(replace = true)
        val before = f.stored()
        assertNotNull(f.admit())
        assertEquals(1, f.preflightCalls)
        assertEquals(1, f.casCalls)
        assertEquals(listOf(proofReads, proofReads + "cas"), f.transactions)
        f.assertStored(before.copy(job = before.job.copy(state = "ADMITTED", updatedAt = admissionTime)))
    }

    @Test fun replacementByOneNormalizedEquivalentPinAfterPreflightIsRejectedBeforeCas() = runTest {
        // Same canonical job, still exactly one valid pin and same normalized pixels; only the retained original changed.
        val f = fixture()
        f.retainEquivalentOriginal()
        f.refusesAfterPreflight("frozen_render_admission_binding_changed") { attachEquivalentPin(replace = true) }
    }

    @Test fun missingOrAdditionalJobPinAfterPreflightIsRejectedBeforeSourceProjection() = runTest {
        for (add in listOf(false, true)) {
            val f = fixture()
            if (add) f.retainEquivalentOriginal()
            f.refusesAfterPreflight("frozen_render_job_metadata_invalid", readsThrough("pins")) {
                if (add) attachEquivalentPin(replace = false) else removePin()
            }
        }
    }

    @Test fun individuallyValidImmutableSourceOrBlobDriftAfterPreflightIsRejectedBeforeCas() = runTest {
        for (sourceDrift in listOf(true, false)) {
            fixture().refusesAfterPreflight("frozen_render_admission_binding_changed") {
                val (source, blob) = bindingFor(pinIds().single())
                if (sourceDrift) catalog.sources[source.sourceId] = source.copy(exifOrientation = 2)
                else catalog.blobs[blob.sourceBlobId] = blob.copy(byteCount = blob.byteCount + 1)
                // Both changes still satisfy the current one-view proof. Comparing pixel identity alone cannot detect them.
                FrozenRenderJobProof.verifyPin(page, source.sourceId, bindingFor(source.sourceId))
            }
        }
    }

    @Test fun liveRefcountChangesDoNotReplaceOrInvalidateTheImmutableBinding() = runTest {
        val f = fixture()
        lateinit var afterExternalChange: Stored
        assertNotNull(f.admit {
            val (source, blob) = f.bindingFor(f.pinIds().single())
            // Model only the counter effect of unrelated retained owners; the exact live JOB reference is untouched.
            f.catalog.sources[source.sourceId] = source.copy(refCount = source.refCount + 1)
            f.catalog.blobs[blob.sourceBlobId] = blob.copy(sourceCount = blob.sourceCount + 1)
            afterExternalChange = f.stored()
            null
        })
        assertEquals(1, f.casCalls)
        assertEquals(listOf(proofReads, proofReads + "cas"), f.transactions)
        f.assertStored(afterExternalChange.copy(job = afterExternalChange.job.copy(state = "ADMITTED", updatedAt = admissionTime)))
    }

    @Test fun zeroSourceReferenceCountAfterPreflightRefusesBeforeCas() = runTest {
        fixture().refusesAfterPreflight("frozen_render_job_invalid") {
            val (source, _) = bindingFor(pinIds().single())
            catalog.sources[source.sourceId] = source.copy(refCount = 0)
        }
    }

    @Test fun zeroBlobSourceCountAfterPreflightRefusesBeforeCas() = runTest {
        fixture().refusesAfterPreflight("frozen_render_job_invalid") {
            val (_, blob) = bindingFor(pinIds().single())
            catalog.blobs[blob.sourceBlobId] = blob.copy(sourceCount = 0)
        }
    }

    @Test fun replayReturnsNoReceiptFromZeroRowCasWithoutTouchingPriorAdmission() = runTest {
        val f = fixture()
        assertNotNull(f.admit())
        val admitted = f.stored()
        f.now++
        val replay = f.admit()
        assertEquals(2, f.preflightCalls)
        assertEquals(listOf(1, 0), f.casResults)
        assertEquals(listOf(proofReads, proofReads + "cas", proofReads, proofReads + "cas"), f.transactions)
        f.assertStored(admitted)
        assertNull("A zero admission CAS must return no receipt", replay)
    }

    @Test fun missedFirstCasLeavesPendingJobWithNoReceipt() = runTest {
        val f = fixture()
        val before = f.stored()
        f.overrideCount = 0
        assertNull(f.admit())
        assertEquals(listOf(0), f.casResults)
        assertEquals(listOf(proofReads, proofReads + "cas"), f.transactions)
        f.assertStored(before)
    }

    @Test fun countOneButTransactionCompletionFailureDoesNotDeliverAdmissionReceipt() = runTest {
        val f = fixture()
        val before = f.stored()
        var completionReached = false
        f.afterWriterBody = {
            assertEquals(listOf(1), f.casResults)
            assertEquals("ADMITTED", f.job.state)
            completionReached = true
            throw IOException("Synthetic failure completing the admission transaction")
        }
        var returned = false
        try {
            f.admit()
            returned = true
            fail("Admission returned after transaction completion failed")
        } catch (failure: RepositoryFailure) {
            assertEquals(OperationError(OperationErrorCode.STORAGE_FAILED, true, "frozen_render_admission_storage_failed", f.jobId.value),
                failure.error)
        }
        assertTrue(completionReached)
        assertFalse(returned)
        assertEquals(1, f.preflightCalls)
        assertEquals(listOf(proofReads, proofReads + "cas"), f.transactions)
        f.assertStored(before)
    }

    @Test fun impossibleCasCountFailsClosedAndDiscardsTheProvisionalMutation() = runTest {
        val f = fixture()
        val before = f.stored()
        f.overrideCount = 2
        f.refuses(f.failure("frozen_render_admission_count_invalid"))
        assertEquals(listOf(2), f.casResults)
        f.assertStored(before)
    }

    private suspend fun expectCancellation(action: suspend (CancellationException) -> Unit) = coroutineScope {
        val canceled = CancellationException("frozen render admission test cancellation")
        launch {
            try {
                action(canceled)
                fail("Expected cancellation, not a returned admission result")
            } catch (actual: CancellationException) {
                assertEquals(canceled.message, actual.message)
            }
        }.join()
    }

    internal data class Stored(
        val job: JobRow,
        val references: Map<Triple<String, String, String>, SourceReferenceRow>,
        val sources: Map<String, SourceRow>,
        val blobs: Map<String, SourceBlobRow>,
    )

    private suspend fun fixture(retained: OrdinaryExportProfile = profile) = Fixture().apply { freeze(retained) }

    internal class Fixture(private val traceSourceProjection: Boolean = true) {
        private val repository = RepositoryFixture(count = 1)
        val catalog = repository.metadata
        private val jobs = LinkedHashMap<String, JobRow>()
        lateinit var page: ExportPageSnapshot
            private set
        private var frozenJobId: JobId? = null
        val jobId: JobId get() = checkNotNull(frozenJobId)
        var job: JobRow
            get() = jobs.getValue(jobId.value)
            set(row) { jobs[jobId.value] = row }
        private lateinit var canonical: JobRow
        val transactions = mutableListOf<MutableList<String>>()
        private val admittedGuards = mutableSetOf<String>()
        val projectedInputs = mutableListOf<ByteArray>()
        val casResults = mutableListOf<Int>()
        var casCalls = 0
            private set
        var preflightCalls = 0
            private set
        var refusedGuard: String? = null
        var afterFirstTransaction: () -> Unit = {}
        var afterWriterBody: () -> Unit = {}
        var onBlobProjection: () -> Unit = {}
        var overrideCount: Int? = null
        var now = admissionTime

        suspend fun freeze(retained: OrdinaryExportProfile = profile) {
            val snapshots = object : ExportSnapshotQueries {
                override fun insertSnapshot(row: ExportSnapshotRow) = Unit
                override fun insertExportPage(row: ExportPageRow) = Unit
                override fun renderJobIdByKey(key: String) = jobs.values.singleOrNull { it.idempotenceKey == key }?.jobId
                override fun renderJobReadIsBounded(jobId: String, expectedInputBytes: Int): Boolean? = error("Fixture freezes fresh jobs only")
                override fun renderJobIdentity(jobId: String): FrozenRenderJobIdentityRow? = error("Fixture freezes fresh jobs only")
                override fun boundedRenderJobPinSourceIds(jobId: String): List<String> = error("Fixture freezes fresh jobs only")
                override fun insertExportJob(row: JobRow) { check(jobs.putIfAbsent(row.jobId, row) == null) }
                override fun snapshotMetadataIsBounded(snapshotId: String): Boolean? = error("Not a snapshot reader")
                override fun exportPagesAreBounded(snapshotId: String): Boolean = error("Not a snapshot reader")
                override fun snapshot(id: String): ExportSnapshotRow? = error("Not a snapshot reader")
                override fun exportPages(snapshotId: String): List<ExportPageRow> = error("Not a snapshot reader")
            }
            val freezer = RoomExportSnapshots(catalog, snapshots, {}, { null }, retained.rendererVersion, retained.encodingVersion,
                retained.backend, retained.precision, { 1000L }, Dispatchers.Unconfined)
            val settings = ExportSettings(ExportFormat.PNG, listOf(PageOutputSettings(repository.pageIds.single(), output)), null, null, emptyList())
            val snapshot = freezer.freezeExport(repository.document, 1, settings)
            val assembly = jobs.values.single { it.kind == "EXPORT_ASSEMBLY" }
            page = snapshot.pages.single()
            frozenJobId = ExportAssemblyInput.verify(JobId(assembly.jobId), snapshot, assembly)[page.ordinal]
            canonical = job.copy(input = job.input.copyOf())
            assertEquals("PENDING", job.state)
            assertEquals(FallbackPolicy.FAIL, page.fallbackPolicy)
            assertEquals(output, page.output)
            assertEquals(listOf(page.sourceId.value), pinIds())
        }

        // Keep Room's transactions and typed access as doubles, but not the canonical read or write authorization logic.
        private val metadata = object : MetadataAccess {
            // The producer tests need the same catalog identity across the admission and writer adapters, as in real Room.
            override val queries: CatalogQueries = if (!traceSourceProjection) catalog else object : CatalogQueries by catalog {
                override fun source(id: String): SourceRow? {
                    typedAfter("source bounds", "source")
                    assertEquals(pinIds().singleOrNull(), id)
                    return catalog.source(id)
                }

                override fun sourceBlob(id: String): SourceBlobRow? {
                    typedAfter("source bounds", "blob")
                    assertEquals(pinIds().singleOrNull()?.let { catalog.sources[it]?.sourceBlobId }, id)
                    return catalog.sourceBlob(id).also { onBlobProjection() }
                }

                override fun page(id: String): PageRow? = throw AssertionError("Admission must not read a live page")
                override fun recipe(pageId: String, revision: Long): RecipeRevisionRow? = throw AssertionError("Admission must not read a live recipe")
            }

            override fun <T> transaction(action: () -> T): T {
                assertFalse("Unexpected nested transaction", catalog.inTransaction)
                transactions.add(mutableListOf())
                admittedGuards.clear()
                val before = job
                val result = try {
                    catalog.transaction {
                        val value = action()
                        // The body, including the CAS result branch, has returned. Throw before the fake transaction returns to admission.
                        if (transactions.size == 2) afterWriterBody()
                        value
                    }
                } catch (failure: Throwable) {
                    job = before
                    throw failure
                }
                if (transactions.size == 1) afterFirstTransaction()
                return result
            }
        }

        private fun record(event: String) {
            assertTrue("$event must be inside a metadata transaction", catalog.inTransaction)
            transactions.last().add(event)
        }

        private fun typedAfter(guard: String, event: String) {
            record(event)
            // Throw AssertionError rather than a repository error a buggy consumer could mistake for a legitimate refusal.
            assertTrue("$event reached without an admitted $guard in this transaction", guard in admittedGuards)
        }

        private fun jobBounds(id: String, inputBytes: Int): Boolean {
            record("job bounds")
            assertEquals(jobId.value, id)
            assertEquals(canonical.input.size, inputBytes)
            return (refusedGuard != "job bounds" && job.input.size == inputBytes).also {
                if (it) admittedGuards += "job bounds"
            }
        }

        private fun jobIdentity(id: String): FrozenRenderJobIdentityRow {
            typedAfter("job bounds", "job")
            assertEquals(jobId.value, id)
            val input = job.input.copyOf().also { projectedInputs += it }
            return FrozenRenderJobIdentityRow(job.jobId, job.kind, job.idempotenceKey, job.canonicalInputSha256, input)
        }

        private fun boundedPins(id: String): List<String> {
            record("pins")
            assertEquals(jobId.value, id)
            return pinIds()
        }

        private fun sourceBounds(id: String): Boolean? {
            record("source bounds")
            assertEquals(pinIds().singleOrNull(), id)
            return (if (refusedGuard == "source bounds") false else catalog.sourceReadIsBounded(id)).also {
                if (it == true) admittedGuards += "source bounds"
            }
        }

        // This double checks handoff arguments, exposes row counts, and changes only the fields the real SQL writes; it does not certify the SQL predicates.
        private fun cas(id: String, key: String, sha: String, input: ByteArray, updatedAt: Long): Int {
            casCalls++
            record("cas")
            assertEquals(jobId.value, id)
            assertEquals(canonical.idempotenceKey, key)
            assertEquals(canonical.canonicalInputSha256, sha)
            assertArrayEquals(canonical.input, input)
            assertEquals(now, updatedAt)
            val count = overrideCount ?: if (job.state == "PENDING") 1 else 0
            if (count > 0) job = job.copy(state = "ADMITTED", updatedAt = updatedAt)
            casResults += count
            return count
        }

        fun pinIds() = catalog.references.values.asSequence()
            .filter { it.ownerType == "JOB" && it.ownerId == jobId.value }.take(2).map { it.sourceId }.toList()

        fun bindingFor(sourceId: String): ExportSourceBinding {
            val source = catalog.sources.getValue(sourceId)
            return ExportSourceBinding(source, catalog.blobs.getValue(source.sourceBlobId))
        }

        fun removePin() {
            val original = pinIds().single()
            checkNotNull(catalog.references.remove(Triple("JOB", jobId.value, original)))
            val source = catalog.sources.getValue(original)
            catalog.sources[original] = source.copy(refCount = source.refCount - 1)
        }

        fun retainEquivalentOriginal() {
            val (source, blob) = bindingFor(pinIds().single())
            catalog.sources[otherSourceId] = source.copy(sourceId = otherSourceId, sourceBlobId = otherBlobId, refCount = 0)
            catalog.blobs[otherBlobId] = blob.copy(sourceBlobId = otherBlobId, byteSha256 = "d".repeat(64),
                relativePath = "sources/$otherBlobId/original.bin", ingestTransactionId = otherIngestId)
            assertNotEquals(page.byteHash, catalog.blobs.getValue(otherBlobId).byteSha256)
        }

        fun attachEquivalentPin(replace: Boolean) {
            if (replace) removePin()
            assertEquals(1L, catalog.insertReference(SourceReferenceRow("JOB", jobId.value, otherSourceId, 0)))
            val source = catalog.sources.getValue(otherSourceId)
            catalog.sources[otherSourceId] = source.copy(refCount = source.refCount + 1)
            FrozenRenderJobProof.verifyPin(page, otherSourceId, bindingFor(otherSourceId))
        }

        suspend fun verifyRetainedProfile(retained: OrdinaryExportProfile) {
            RoomFrozenRenderReads(catalog, { id, extent -> id == jobId.value && extent == canonical.input.size },
                { FrozenRenderJobIdentityRow(job.jobId, job.kind, job.idempotenceKey, job.canonicalInputSha256, job.input) },
                { id -> assertEquals(jobId.value, id); pinIds() }, catalog::sourceReadIsBounded, retained, Dispatchers.Unconfined)
                .verify(jobId, page)
        }

        fun stored() = Stored(job.copy(input = job.input.copyOf(), checkpoint = job.checkpoint?.copyOf()),
            catalog.references.toMap(), catalog.sources.toMap(), catalog.blobs.toMap())

        /** Copy only the frozen writer's input rows to an independent store; neither store has admitted the job yet. */
        fun forkPendingRender(): Fixture {
            check(job.state == "PENDING")
            val pending = stored()
            return Fixture(traceSourceProjection).also { other ->
                other.page = page
                other.frozenJobId = jobId
                other.job = pending.job
                other.canonical = pending.job.copy(input = pending.job.input.copyOf())
                other.catalog.sources.clear()
                other.catalog.sources.putAll(pending.sources.mapValues { it.value.copy() })
                other.catalog.blobs.clear()
                other.catalog.blobs.putAll(pending.blobs.mapValues { it.value.copy() })
                other.catalog.references.clear()
                other.catalog.references.putAll(pending.references.mapValues { it.value.copy() })
            }
        }

        fun assertStored(expected: Stored) {
            assertEquals(expected.job.copy(input = job.input, checkpoint = job.checkpoint), job)
            assertArrayEquals(expected.job.input, job.input)
            assertArrayEquals(expected.job.checkpoint, job.checkpoint)
            assertEquals(expected.references, catalog.references)
            assertEquals(expected.sources, catalog.sources)
            assertEquals(expected.blobs, catalog.blobs)
        }

        fun assertNoCas(expected: Stored) {
            assertEquals(0, casCalls)
            assertStored(expected)
        }

        fun failure(key: String, code: OperationErrorCode = OperationErrorCode.INTEGRITY_FAILED) =
            OperationError(code, false, key, jobId.value)

        suspend fun refuses(expected: OperationError, preflight: suspend () -> OperationError? = { null }) {
            val failure = try {
                admit(preflight)
                throw AssertionError("Expected ${expected.safeMessageKey}, not a returned admission result")
            } catch (failure: RepositoryFailure) {
                failure
            }
            assertEquals(expected, failure.error)
        }

        suspend fun refusesAfterPreflight(key: String, writerReads: List<String> = proofReads, mutate: Fixture.() -> Unit) {
            lateinit var changed: Stored
            refuses(failure(key)) {
                mutate()
                changed = stored()
                null
            }
            assertEquals(1, preflightCalls)
            assertEquals(listOf(proofReads, writerReads), transactions)
            assertNoCas(changed)
        }

        suspend fun admit(preflight: suspend () -> OperationError? = { null }): RoomFrozenRenderAdmission.Admitted? {
            val initialBinding = pinIds().singleOrNull()?.let(::bindingFor)
            var preflightedBinding: ExportSourceBinding? = null
            val gate: suspend (ExportPageSnapshot, ExportSourceBinding) -> OperationError? = { frozenPage, binding ->
                assertFalse("Preflight must not run inside Room", catalog.inTransaction)
                preflightCalls++
                assertSame(page, frozenPage)
                assertEquals(initialBinding, binding)
                preflightedBinding = binding
                preflight()
            }
            return RoomFrozenRenderAdmission(
                metadata, profile, gate, ::jobBounds, ::jobIdentity, ::boundedPins, ::sourceBounds, ::cas, { now }, Dispatchers.Unconfined,
            ).admit(jobId, page)?.also { receipt ->
                assertSame(page, receipt.proof.page)
                assertEquals(jobId, receipt.proof.jobId)
                assertSame("Retain the preflight object, not a newly projected writer binding", preflightedBinding, receipt.proof.pinnedBinding)
            }
        }
    }

    private companion object {
        const val admissionTime = 2000L
        val profile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")
        val output = OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)
        const val otherSourceId = "10000000-0000-0000-0000-000000000000"
        const val otherBlobId = "20000000-0000-0000-0000-000000000000"
        const val otherIngestId = "30000000-0000-0000-0000-000000000000"
        val proofReads = listOf("job bounds", "job", "pins", "source bounds", "source", "blob")
        fun readsThrough(guard: String) = proofReads.take(proofReads.indexOf(guard) + 1)
    }
}
