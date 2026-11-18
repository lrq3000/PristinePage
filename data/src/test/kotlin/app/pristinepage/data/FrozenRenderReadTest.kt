package app.pristinepage.data

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

class FrozenRenderReadTest {
    @Test fun missingAndUnboundedJobStopBeforeAnyProjectionOrPinLookup() = runTest {
        val frozen = Frozen().freeze()
        for ((flag, code) in listOf(null to OperationErrorCode.SOURCE_UNAVAILABLE, false to OperationErrorCode.INTEGRITY_FAILED)) {
            val read = Read(frozen).apply { admitted = flag }
            read.refuses(code)
            assertEquals(listOf("begin", "job bounds", "end"), read.events)
        }
    }

    @Test fun unexpectedlyMissingProjectionIsNotMissingJob() = runTest {
        val read = Read(Frozen().freeze()).apply { job = null }
        read.refuses(OperationErrorCode.INTEGRITY_FAILED)
        assertEquals(listOf("begin", "job bounds", "job", "end"), read.events)
    }

    @Test fun noPinInvalidPinAndMultiplicityAllStopBeforeTheSourceGuard() = runTest {
        val frozen = Frozen().freeze()
        for (pins in listOf(emptyList(), listOf(""), listOf("A".repeat(36)), listOf(frozen.page.sourceId.value, ""))) {
            val read = Read(frozen).apply { pinIds = pins }
            read.refuses(OperationErrorCode.INTEGRITY_FAILED)
            assertEquals(listOf("begin", "job bounds", "job", "pins", "end"), read.events)
        }
    }

    @Test fun missingOrUnboundedPinnedSourceStopsBeforeSourceAndBlobProjection() = runTest {
        val frozen = Frozen().freeze()
        for (flag in listOf(null, false)) {
            val read = Read(frozen).apply { sourceAdmitted = flag }
            read.refuses(OperationErrorCode.INTEGRITY_FAILED)
            assertEquals(listOf("begin", "job bounds", "job", "pins", "source bounds", "end"), read.events)
        }
    }

    @Test fun exactVerifiedReferenceAndEquivalentPinUseOneTransaction() = runTest {
        val frozen = Frozen().freeze()
        val read = Read(frozen)
        // The producer may reuse a render job originally pinned on a byte-distinct source with equivalent normalized identity.
        val alternateId = "10000000-0000-0000-0000-000000000000"
        val alternateBlob = "20000000-0000-0000-0000-000000000000"
        read.pinIds = listOf(alternateId)
        read.source = read.source.copy(sourceId = alternateId, sourceBlobId = alternateBlob)
        read.blob = read.blob.copy(sourceBlobId = alternateBlob, byteSha256 = "d".repeat(64), relativePath = "sources/$alternateBlob/original.bin")
        read.verify()
        assertEquals(completeRead, read.events)
    }

    @Test fun guardExtentTracksTheCanonicalInputForEachProfileNotARecipeOrAssemblyConstant() = runTest {
        val short = Frozen(current).freeze()
        val longerProfile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference-étendu", "binary64")
        val longer = Frozen(longerProfile).freeze()
        assertEquals(short.job.input.size + "-étendu".encodeToByteArray().size, longer.job.input.size)
        for ((fixture, runtime) in listOf(short to current, longer to longerProfile)) {
            val read = Read(fixture)
            read.verify(runtime)
            assertEquals(completeRead, read.events)
        }
    }

    @Test fun swappedAssemblyReferenceAndNameOnlyMatchDoNotSatisfyCanonicalProof() = runTest {
        val owner = Frozen()
        val first = owner.freeze()
        val second = first.copy(page = owner.snapshot.pages[1], job = owner.jobs.getValue(owner.references[1].value), id = owner.references[1])
        assertEquals(first.job.input.size, second.job.input.size)
        assertNotEquals(first.job.idempotenceKey, second.job.idempotenceKey)
        Read(second.copy(page = first.page)).refuses(OperationErrorCode.INTEGRITY_FAILED)
        Read(first).apply { job = first.job.copy(idempotenceKey = second.job.idempotenceKey) }
            .refuses(OperationErrorCode.INTEGRITY_FAILED)
    }

    @Test fun blobProjectionCannotFollowAProjectedSourceFromAnotherKey() = runTest {
        val read = Read(Frozen().freeze()).apply { source = source.copy(sourceId = "10000000-0000-0000-0000-000000000000") }
        read.refuses(OperationErrorCode.INTEGRITY_FAILED)
        assertEquals(listOf("begin", "job bounds", "job", "pins", "source bounds", "source", "end"), read.events)
    }

    @Test fun fullIdentityProofUsesBytesAfterTheTransactionExitHook() = runTest {
        val frozen = Frozen().freeze()
        val stored = frozen.job.input.copyOf()
        val read = Read(frozen).apply {
            job = frozen.job.copy(input = stored)
            // The double alone owns this projected buffer. A proof performed before transaction exit would accept the original.
            afterRelease = { stored[stored.lastIndex] = 0 }
        }
        read.refuses(OperationErrorCode.INTEGRITY_FAILED)
        assertEquals(completeRead, read.events)
    }

    @Test fun inconsistentTypedJobExtentIsRefusedAfterTheGuardedPinRead() = runTest {
        val frozen = Frozen().freeze()
        val read = Read(frozen).apply {
            // A contradictory port admits the raw extent but returns a shorter materialized BLOB.
            job = frozen.job.copy(input = frozen.job.input.copyOf(frozen.job.input.size - 1))
        }
        val failure = read.refuses(OperationErrorCode.INTEGRITY_FAILED)
        assertEquals("frozen_render_job_metadata_invalid", failure.error.safeMessageKey)
        assertEquals(completeRead, read.events)
    }

    @Test fun verifiedHistoricalProfileIsUnavailableButCorruptHistoricalBindingIsNot() = runTest {
        val profile = OrdinaryExportProfile("renderer-older-v1", "rgb8-old\\", "older\"[[[}}\\", "float{[]}")
        val history = Frozen(profile).freeze()
        assertEquals("""{"backend":"older\"[[[}}\\","encodingVersion":"rgb8-old\\","precision":"float{[]}","providers":[],"version":1}""",
            history.page.providers.decodeToString())
        val valid = Read(history)
        assertEquals("frozen_render_profile_unavailable", valid.refuses(OperationErrorCode.PROVIDER_UNAVAILABLE).error.safeMessageKey)
        assertEquals(completeRead, valid.events)
        Read(history).apply { blob = blob.copy(byteSha256 = "c".repeat(64)) }
            .refuses(OperationErrorCode.INTEGRITY_FAILED)
        Read(history).apply { job = history.job.copy(canonicalInputSha256 = "0".repeat(64)) }
            .refuses(OperationErrorCode.INTEGRITY_FAILED)
    }

    @Test fun nestedProvidersAreRefusedWithoutInvokingTheTreeParserOrRoom() = runTest {
        val owner = Frozen()
        val frozen = owner.freeze()
        // One level beyond the ordinary v1 object and its empty providers array; no stress fixture.
        val descriptor = """{"backend":"reference","encodingVersion":"rgb8-v1","precision":"binary64","providers":[[]],"version":1}"""
        val malformed = ExportPageSnapshot(owner.pages.first().copy(providers = descriptor.encodeToByteArray()))
        var parsed: JsonElement? = null
        val failure = assertThrows(RepositoryFailure::class.java) {
            FrozenRenderJobProof.prepare(frozen.id, malformed, current) { text ->
                Json.parseToJsonElement(text).also { parsed = it }
            }
        }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
        val read = Read(frozen.copy(page = malformed))
        read.refuses(OperationErrorCode.INTEGRITY_FAILED)
        assertTrue(read.events.isEmpty())
        assertNull("Nonordinary nesting reached the tree parser", parsed)
    }

    @Test fun malformedAndNoncanonicalDescriptorsAreNotEvidenceOfHistoricalCapability() = runTest {
        val owner = Frozen()
        val frozen = owner.freeze()
        for (descriptor in listOf("{".encodeToByteArray(), current.providerDescriptor() + ' '.code.toByte())) {
            val read = Read(frozen.copy(page = ExportPageSnapshot(owner.pages.first().copy(providers = descriptor))))
            read.refuses(OperationErrorCode.INTEGRITY_FAILED)
            assertTrue(read.events.isEmpty())
        }
    }

    @Test fun metadataFailureIsSafeAndRetryableButCancellationPropagates() = runTest {
        val frozen = Frozen().freeze()
        val read = Read(frozen).apply { afterRelease = { error("private database details") } }
        val failure = read.refuses(OperationErrorCode.STORAGE_FAILED)
        assertTrue(failure.error.retryable)
        assertFalse(failure.error.safeMessageKey.contains("private"))
        val cancellation = CancellationException("injected")
        try {
            Read(frozen).apply { afterRelease = { throw cancellation } }.verify()
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private data class Reference(val id: JobId, val page: ExportPageSnapshot, val job: JobRow, val binding: ExportSourceBinding)

    private class Frozen(profile: OrdinaryExportProfile = current) : ExportSnapshotQueries {
        private val fixture = RepositoryFixture()
        val jobs = LinkedHashMap<String, JobRow>()
        val pages = mutableListOf<ExportPageRow>()
        lateinit var snapshot: ExportSnapshot
            private set
        lateinit var references: List<JobId>
            private set
        private val freezer = RoomExportSnapshots(fixture.metadata, this, {}, { null }, profile.rendererVersion, profile.encodingVersion, profile.backend, profile.precision, { 1000L }, Dispatchers.Unconfined)

        suspend fun freeze(): Reference {
            val settings = ExportSettings(ExportFormat.PNG, fixture.pageIds.map { PageOutputSettings(it, OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)) }, null, null, emptyList())
            snapshot = freezer.freezeExport(fixture.document, 1, settings)
            val assembly = jobs.values.single { it.kind == "EXPORT_ASSEMBLY" }
            references = ExportAssemblyInput.verify(JobId(assembly.jobId), snapshot, assembly)
            val id = references.first()
            val source = fixture.metadata.sources.getValue(fixture.source.value)
            assertEquals(listOf(source.sourceId), fixture.metadata.references.values.filter { it.ownerType == "JOB" && it.ownerId == id.value }.map { it.sourceId })
            return Reference(id, snapshot.pages.first(), jobs.getValue(id.value), ExportSourceBinding(source, fixture.metadata.blobs.getValue(source.sourceBlobId)))
        }

        override fun insertSnapshot(row: ExportSnapshotRow) = Unit
        override fun insertExportPage(row: ExportPageRow) { pages += row }
        override fun renderJobIdByKey(key: String) = jobs.values.singleOrNull { it.idempotenceKey == key }?.jobId
        override fun renderJobReadIsBounded(jobId: String, expectedInputBytes: Int): Boolean? = error("Fixture only freezes fresh jobs")
        override fun renderJobIdentity(jobId: String): FrozenRenderJobIdentityRow? = error("Fixture only freezes fresh jobs")
        override fun boundedRenderJobPinSourceIds(jobId: String): List<String> = error("Fixture only freezes fresh jobs")
        override fun insertExportJob(row: JobRow) { check(jobs.putIfAbsent(row.jobId, row) == null) }
        override fun snapshotMetadataIsBounded(snapshotId: String): Boolean? = error("Not a snapshot read fixture")
        override fun exportPagesAreBounded(snapshotId: String): Boolean = error("Not a snapshot read fixture")
        override fun snapshot(id: String): ExportSnapshotRow? = error("Not a snapshot read fixture")
        override fun exportPages(snapshotId: String): List<ExportPageRow> = error("Not a snapshot read fixture")
    }

    private class Read(private val reference: Reference) : MetadataAccess {
        val events = mutableListOf<String>()
        var admitted: Boolean? = true
        var sourceAdmitted: Boolean? = true
        var job: JobRow? = reference.job
        var source = reference.binding.source
        var blob = reference.binding.blob
        var pinIds = listOf(source.sourceId)
        var afterRelease: () -> Unit = {}
        private var active = false
        override val queries: CatalogQueries = object : CatalogQueries by MemoryMetadata() {
            override fun source(id: String): SourceRow {
                record("source", id, pinIds.single())
                return source
            }
            override fun sourceBlob(id: String): SourceBlobRow {
                record("blob", id, blob.sourceBlobId)
                return blob
            }
            override fun page(id: String): PageRow? = throw AssertionError("Must not read a live page")
            override fun recipe(pageId: String, revision: Long): RecipeRevisionRow? = throw AssertionError("Must not read a live recipe")
        }

        override fun <T> transaction(action: () -> T): T {
            check(!active)
            active = true
            events += "begin"
            return try {
                action()
            } finally {
                active = false
                events += "end"
                afterRelease()
            }
        }

        private fun record(event: String, actual: String, expected: String) {
            assertTrue("$event must run inside the transaction", active)
            assertEquals(expected, actual)
            events += event
        }

        suspend fun refuses(code: OperationErrorCode): RepositoryFailure {
            try {
                verify()
                throw AssertionError("Expected $code")
            } catch (failure: RepositoryFailure) {
                assertEquals(code, failure.error.code)
                assertEquals(reference.id.value, failure.error.affectedId)
                return failure
            }
        }

        suspend fun verify(runtime: OrdinaryExportProfile = current): Unit = RoomFrozenRenderReads(
            this,
            { id, extent -> record("job bounds", id, reference.id.value); assertEquals(reference.job.input.size, extent); admitted },
            { id ->
                record("job", id, reference.id.value)
                job?.let { FrozenRenderJobIdentityRow(it.jobId, it.kind, it.idempotenceKey, it.canonicalInputSha256, it.input) }
            },
            { id -> record("pins", id, reference.id.value); pinIds },
            { id -> record("source bounds", id, pinIds.single()); sourceAdmitted },
            runtime,
            Dispatchers.Unconfined,
        ).verify(reference.id, reference.page)
    }

    private companion object {
        val current = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")
        val completeRead = listOf("begin", "job bounds", "job", "pins", "source bounds", "source", "blob", "end")
    }
}
