package app.pristinepage.data

import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FrozenRenderJobProofTest {
    @Test fun secondFreezeStoresTheSameRenderIdsInItsOwnOrderedAssemblyBlob() = runTest {
        val fixture = FreezeFixture()
        val first = fixture.freeze()
        val second = fixture.freeze()
        assertNotEquals(first.snapshotId, second.snapshotId)
        assertEquals(first.snapshotHash, second.snapshotHash)
        assertEquals(fixture.repository.pageIds, second.pages.map { it.pageId })
        assertEquals(2, fixture.jobs.values.count { it.kind == "PAGE_RENDER" })
        val ordered = second.pages.map { fixture.jobFor(it).jobId }
        assertNotEquals(ordered[0], ordered[1])
        // Read the two v1 UUID fields at their fixed offsets, independently of the assembly decoder.
        for (snapshot in listOf(first, second)) {
            val bytes = fixture.assembly(snapshot).input
            assertEquals(224, bytes.size)
            assertEquals(ordered, listOf(bytes.decodeToString(148, 184), bytes.decodeToString(188, 224)))
        }
        assertEquals(2, fixture.repository.metadata.references.values.count { it.ownerType == "JOB" })
    }

    @Test fun correctOrderedReferencesMatchButSelfConsistentSwapAndRekeyDoNot() = runTest {
        val f = FreezeFixture()
        val frozen = f.freeze()
        val (a, b) = frozen.pages
        val assembly = f.assembly(frozen)
        val ordered = ExportAssemblyInput.verify(JobId(assembly.jobId), frozen, assembly)
        val (jobA, jobB) = ordered.map { f.jobs.getValue(it.value) }
        assertEquals(f.jobFor(a), jobA)
        assertEquals(f.jobFor(b), jobB)
        // Apart from page UUID, the canonical inputs are identical, including recipe and normalized identity.
        assertArrayEquals(jobA.input.copyOfRange(69, jobA.input.size), jobB.input.copyOfRange(69, jobB.input.size))
        val repository = f.repository
        assertTrue(repository.repository.rotatePage(a.pageId, 1, 1) is DocumentMutationResult.Applied)
        assertTrue(repository.repository.reorderPages(repository.document, 2, listOf(b.pageId, a.pageId)) is DocumentMutationResult.Applied)
        for ((ordinal, page) in frozen.pages.withIndex()) {
            val job = f.jobs.getValue(ordered[ordinal].value)
            verify(ordered[ordinal], page, job, f.pins(job), f.binding)
        }

        val swapped = assembly.withInput(ExportAssemblyInput.encode(frozen.snapshotId, frozen.snapshotHash, ordered.reversed()), rekey = false)
        val wrong = ExportAssemblyInput.verify(JobId(assembly.jobId), frozen, swapped).first()
        assertEquals(JobId(jobB.jobId), wrong)
        refuses("valid B job at A's ordinal", a, jobB, f.binding, f.pins(jobB), wrong)
        val forged = jobA.withInput(jobB.input)
        refuses("B identity and fresh digest/key in A row", a, forged, f.binding, f.pins(jobA))
    }

    @Test fun equivalentPinnedSourceCanHaveDistinctSourceBlobAndEncodedIdentity() = runTest {
        val f = FreezeFixture()
        val page = f.freeze().pages.first()
        val job = f.jobFor(page)
        val alternate = f.alternateBinding()
        val pins = f.pins(job).map { it.copy(sourceId = alternate.source.sourceId) }
        assertNotEquals(page.sourceId.value, alternate.source.sourceId)
        assertNotEquals(page.sourceBlobId.value, alternate.blob.sourceBlobId)
        assertNotEquals(page.byteHash, alternate.blob.byteSha256)
        val recipe = RecipeCodec.decode(page.canonicalRecipe)
        val request = RenderRequest(alternate.source.sourceId, page.pixelHash, page.pageId.value, page.recipeRevision, recipe, page.output, 99, null, FallbackPolicy.FAIL)
        val sameIdentity = RenderJobIdentity.create(request, emptyMap(), page.assetSetHash, page.conversionFingerprint, profile.rendererVersion, profile.encodingVersion, profile.backend, profile.precision)
        assertArrayEquals(job.input, sameIdentity.canonicalInput)
        assertEquals(job.idempotenceKey, sameIdentity.idempotenceKey)
        // Mutable attempt/state data is outside this proof: canceled is not made runnable by equivalence.
        verify(JobId(job.jobId), page, job.copy(state = "CANCELED", attempt = 3, errorCode = "CANCELED"), pins, alternate)
    }

    @Test fun requiresExactlyOneExistingJobOwnerWithMatchingSourceAndBlobRows() = runTest {
        val f = FreezeFixture()
        val page = f.freeze().pages.first()
        val job = f.jobFor(page)
        val pins = f.pins(job)
        val pin = pins.single()
        val binding = f.binding
        val cases = mapOf(
            "no pin" to emptyList(),
            "duplicate pin" to (pins + pin),
            "second source" to (pins + pin.copy(sourceId = f.alternateBinding().source.sourceId)),
            "wrong owner type" to listOf(pin.copy(ownerType = "EXPORT_PAGE")),
            "foreign owner" to listOf(pin.copy(ownerId = uuid(6))),
            "foreign source" to listOf(pin.copy(sourceId = uuid(7))),
        )
        for ((name, refs) in cases) refuses(name, page, job, binding, refs)
        refuses("missing source/blob binding", page, job, null, pins)
        refuses("unrelated blob row", page, job, binding.copy(blob = f.alternateBinding().blob), pins)
    }

    @Test fun refusesInconsistentAndNonequivalentSourceMetadata() = runTest {
        val f = FreezeFixture()
        val page = f.freeze().pages.first()
        val job = f.jobFor(page)
        val original = f.binding
        val alternate = f.alternateBinding()
        val source = alternate.source
        val blob = alternate.blob
        val cases = mapOf(
            "source READY" to alternate.copy(source = source.copy(state = "QUARANTINED")),
            "blob READY" to alternate.copy(blob = blob.copy(state = "DELETING")),
            "source count" to alternate.copy(source = source.copy(refCount = 0)),
            "blob count" to alternate.copy(blob = blob.copy(sourceCount = 0)),
            "normalized hash" to alternate.copy(source = source.copy(pixelSha256 = "6".repeat(64))),
            "conversion version" to alternate.copy(source = source.copy(conversionFingerprint = "srgb-v2")),
            "normalized dimensions" to ExportSourceBinding(source.copy(width = 4), blob.copy(encodedHeight = 4)),
            "EXIF size binding" to alternate.copy(source = source.copy(exifOrientation = 1)),
            "profile hash grammar" to alternate.copy(source = source.copy(profileSha256 = "x".repeat(64))),
            "byte hash grammar" to alternate.copy(blob = blob.copy(byteSha256 = blob.byteSha256.uppercase())),
            "byte count" to alternate.copy(blob = blob.copy(byteCount = 0)),
            "path identity" to alternate.copy(blob = blob.copy(relativePath = original.blob.relativePath)),
            "same source ID changed blob" to alternate.copy(source = source.copy(sourceId = original.source.sourceId)),
            "same blob ID changed byte hash" to original.copy(blob = original.blob.copy(byteSha256 = blob.byteSha256)),
        )
        for ((name, binding) in cases) refuses(name, page, job, binding, f.pins(job).map { it.copy(sourceId = binding.source.sourceId) })
    }

    @Test fun refusesChangedJobRowFieldsAndDigestGrammar() = runTest {
        val f = FreezeFixture()
        val page = f.freeze().pages.first()
        val job = f.jobFor(page)
        val cases = mapOf(
            "job ID" to job.copy(jobId = uuid(7)),
            "kind" to job.copy(kind = "EXPORT_ASSEMBLY"),
            "snapshot" to job.copy(snapshotId = uuid(8)),
            "ordinal" to job.copy(pageOrdinal = page.ordinal),
            "key" to job.copy(idempotenceKey = "page-render-v2:${job.canonicalInputSha256}"),
            "digest" to job.copy(canonicalInputSha256 = "0".repeat(64)),
            "digest case" to job.copy(canonicalInputSha256 = job.canonicalInputSha256.uppercase()),
            "bytes only" to job.copy(input = job.input.copyOf().also { it[0] = 0 }),
        )
        for ((name, row) in cases) refuses(name, page, row, f.binding, f.pins(job), JobId(job.jobId))
    }

    @Test fun refusesNoncanonicalFrozenRecipeOrWrongSourceAndProfileBinding() = runTest {
        val f = FreezeFixture()
        val page = f.freeze().pages.first()
        val row = f.pages.first { it.originalPageId == page.pageId.value }
        val job = f.jobFor(page)
        val cases = mapOf(
            "recipe schema" to row.copy(recipeSchemaVersion = 2),
            "noncanonical recipe" to row.copy(recipe = byteArrayOf(32) + row.recipe),
            "recipe hash" to row.copy(recipeSha256 = "0".repeat(64)),
            "recipe source pixel hash" to row.copy(pixelSha256 = "1".repeat(64)),
            "recipe source size" to row.copy(width = 4),
            "recipe conversion fingerprint" to row.copy(conversionFingerprint = "srgb-v2"),
            "revision" to row.copy(recipeRevision = row.recipeRevision + 1),
            "renderer version" to row.copy(rendererVersion = "renderer-v2"),
            "provider descriptor" to row.copy(providers = profile.providerDescriptor() + byteArrayOf(32)),
            "asset hash" to row.copy(assetSetSha256 = "0".repeat(64)),
            "output mismatch" to row.copy(requestedOutputWidth = row.outputWidth - 1),
            "different output" to row.copy(outputWidth = row.outputWidth - 1, requestedOutputWidth = row.outputWidth - 1),
        )
        for ((name, corrupt) in cases) refuses(name, ExportPageSnapshot(corrupt), job, f.binding, f.pins(job))
        for (other in listOf(
            OrdinaryExportProfile("renderer-v2", "rgb8-v1", "reference", "binary64"),
            OrdinaryExportProfile("renderer-v1", "rgb8-v2", "reference", "binary64"),
            OrdinaryExportProfile("renderer-v1", "rgb8-v1", "other", "binary64"),
            OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "float32"),
        )) refuses("different runtime profile", page, job, f.binding, f.pins(job), expectedProfile = other)
    }

    @Test fun neverDropsFallbackAcceptanceMasksOrProviderRequirements() = runTest {
        val f = FreezeFixture()
        val page = f.freeze().pages.first()
        val row = f.pages.first { it.originalPageId == page.pageId.value }
        val job = f.jobFor(page)
        for ((name, corrupt) in mapOf(
            "keep stage input" to row.copy(fallbackPolicy = "KEEP_STAGE_INPUT"),
            "accepted render" to row.copy(acceptedRenderId = uuid(8)),
            "accepted execution" to row.copy(acceptedExecutionSha256 = "c".repeat(64)),
            "nonempty masks" to row.copy(masks = "[{}]".encodeToByteArray()),
        )) refuses(name, ExportPageSnapshot(corrupt), job, f.binding, f.pins(job))

        val providerRecipe = RecipeCodec.decode(row.recipe).copy(appearance = AppearanceRecipe.ProviderAppearanceV1("other", 1, "1".repeat(64), "variant", "2".repeat(64)))
        val frozenProvider = ExportPageSnapshot(row.copy(recipe = RecipeCodec.encode(providerRecipe), recipeSha256 = RecipeCodec.hash(providerRecipe)))
        val request = RenderRequest(row.sourceId, row.pixelSha256, row.originalPageId, row.recipeRevision, providerRecipe, page.output, 0, null, FallbackPolicy.FAIL)
        val identity = RenderJobIdentity.create(request, emptyMap(), row.assetSetSha256, row.conversionFingerprint, profile.rendererVersion, profile.encodingVersion, profile.backend, profile.precision)
        refuses("provider recipe hidden behind ordinary descriptor", frozenProvider, job.withInput(identity.canonicalInput), f.binding, f.pins(job))
    }

    private class FreezeFixture : ExportSnapshotQueries {
        val repository = RepositoryFixture()
        val jobs = LinkedHashMap<String, JobRow>()
        val pages = mutableListOf<ExportPageRow>()
        val binding get() = repository.metadata.sources.getValue(repository.source.value).let {
            ExportSourceBinding(it, repository.metadata.blobs.getValue(it.sourceBlobId))
        }
        private val service = RoomExportSnapshots(repository.metadata, this, {}, { null }, "renderer-v1", "rgb8-v1", "reference", "binary64", { 1000L }, Dispatchers.Unconfined)
        private val settings = ExportSettings(ExportFormat.PNG, repository.pageIds.map { PageOutputSettings(it, OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)) }, null, null, emptyList())

        suspend fun freeze() = service.freezeExport(repository.document, 1, settings)
        fun assembly(snapshot: ExportSnapshot) = jobs.values.single { it.kind == "EXPORT_ASSEMBLY" && it.snapshotId == snapshot.snapshotId.value }
        fun jobFor(page: ExportPageSnapshot) = jobs.values.single { it.kind == "PAGE_RENDER" && it.input.decodeToString(33, 69) == page.pageId.value }
        fun pins(job: JobRow) = repository.metadata.references.values.filter { it.ownerType == "JOB" && it.ownerId == job.jobId }.take(2)
        fun alternateBinding(): ExportSourceBinding {
            val blob = uuid(4)
            return ExportSourceBinding(
                binding.source.copy(sourceId = uuid(3), sourceBlobId = blob, exifOrientation = 6, refCount = 1),
                binding.blob.copy(sourceBlobId = blob, byteSha256 = "d".repeat(64), byteCount = 7, encodedWidth = 2, encodedHeight = 3, relativePath = "sources/$blob/original.bin", ingestTransactionId = uuid(5)),
            )
        }

        override fun insertSnapshot(row: ExportSnapshotRow) { check(repository.metadata.inTransaction) }
        override fun insertExportPage(row: ExportPageRow) { check(repository.metadata.inTransaction); pages += row }
        override fun renderJobByKey(key: String): JobRow? { check(repository.metadata.inTransaction); return jobs.values.singleOrNull { it.idempotenceKey == key } }
        override fun jobSourceReferences(jobId: String): List<SourceReferenceRow> { check(repository.metadata.inTransaction); return pins(jobs.getValue(jobId)) }
        override fun insertExportJob(row: JobRow) { check(repository.metadata.inTransaction); check(jobs.putIfAbsent(row.jobId, row) == null) }
        override fun snapshotMetadataIsBounded(snapshotId: String): Boolean? = error("Not a load fixture")
        override fun exportPagesAreBounded(snapshotId: String): Boolean = error("Not a load fixture")
        override fun snapshot(id: String): ExportSnapshotRow? = error("Not a load fixture")
        override fun exportPages(snapshotId: String): List<ExportPageRow> = error("Not a load fixture")
    }

    private fun refuses(name: String, page: ExportPageSnapshot, row: JobRow, source: ExportSourceBinding?, pins: List<SourceReferenceRow>, expected: JobId = JobId(row.jobId), expectedProfile: OrdinaryExportProfile = profile) {
        val failure = assertThrows(name, RepositoryFailure::class.java) { verify(expected, page, row, pins, source, expectedProfile) }
        assertEquals(name, OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "frozen_render_job_invalid", expected.value), failure.error)
    }

    private fun verify(expected: JobId, page: ExportPageSnapshot, row: JobRow, pins: List<SourceReferenceRow>, source: ExportSourceBinding?, expectedProfile: OrdinaryExportProfile = profile) =
        FrozenRenderJobProof.verify(expected, page, row, pins, source, expectedProfile)

    private fun JobRow.withInput(bytes: ByteArray, rekey: Boolean = true): JobRow {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return copy(input = bytes, canonicalInputSha256 = digest, idempotenceKey = if (rekey) "page-render-v1:$digest" else idempotenceKey)
    }

    companion object {
        private val profile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")
        private fun uuid(n: Int) = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"
    }
}
