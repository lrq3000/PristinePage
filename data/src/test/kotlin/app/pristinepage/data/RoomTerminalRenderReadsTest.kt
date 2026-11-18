package app.pristinepage.data

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RoomTerminalRenderReadsTest {
    @Test fun acceptsExactCurrentAttemptWithThreeDistinctOriginalsAndReturnsDetachedMetadata() = runTest {
        val f = fixture()
        val retainedIntent = f.current.payload.copyOf()
        // Older and newer rows deliberately look usable by cardinality or latest-attempt heuristics, but neither is the exact attempt.
        f.journals[1] = f.writerJournal(attempt = 1).copy(state = "MOVED")
        f.journals[3] = f.writerJournal(attempt = 3).copy(state = "COPYING", sealSha256 = null, expectedByteSha256 = null, expectedByteCount = null)
        // The producer's old snapshot pin may already be gone. Its canonical retained row is not the original JOB pin.
        val producerId = f.producer.sourceId.value
        f.metadata.sources[producerId] = f.metadata.sources.getValue(producerId).copy(refCount = 0)
        f.onTransactionExit = {
            f.render.execution.fill(0)
            f.current.payload.fill(0)
            f.job.input.fill(0)
        }
        val receipt = f.load()
        assertEquals(listOf(Triple(id(5), 2L, RenderFileIntent.payloadBytes)), f.journalGuardReads)
        assertEquals(RenderId(id(6)), receipt.renderId)
        assertEquals(TransactionId(id(7)), receipt.transactionId)
        assertEquals(sealHash, receipt.sealSha256)
        assertEquals(encodedHash, receipt.byteSha256)
        assertEquals(5L, receipt.byteCount)
        assertEquals(size, receipt.size)
        assertEquals(rasterHash, receipt.rasterSha256)
        assertArrayEquals(retainedIntent, receipt.intentPayload())
        receipt.intentPayload().fill(0)
        assertArrayEquals(retainedIntent, receipt.intentPayload())
    }

    @Test fun refusesMissingCurrentAttemptRatherThanUsingACommittedOlderJournal() = runTest {
        val f = fixture()
        f.journals[1] = f.writerJournal(attempt = 1)
        f.journals.remove(2L)
        f.forbidJournalProjection = true
        f.refuses()
        assertEquals(listOf(Triple(id(5), 2L, RenderFileIntent.payloadBytes)), f.journalGuardReads)
    }

    @Test fun historicalProfileWithMovedJournalReportsIntegrityBeforeAvailability() = runTest {
        fixture(retained = historicalProfile).apply {
            // MOVED can pass the shape-only guard, but does not finish the terminal metadata chain.
            current = current.copy(state = "MOVED")
        }.refuses(safeKey = "terminal_render_metadata_invalid")
    }

    @Test fun historicalProfileWithWrongExecutionDigestReportsIntegrityBeforeAvailability() = runTest {
        fixture(retained = historicalProfile).apply {
            val wrongDigest = "0".repeat(64)
            assertNotEquals(render.executionSha256, wrongDigest)
            render = render.copy(executionSha256 = wrongDigest)
        }.refuses(safeKey = "ordinary_render_execution_invalid")
    }

    @Test fun intactHistoricalChainIsValidWithItsOwnProfileButUnavailableToCurrentRuntime() = runTest {
        val compatible = fixture(retained = historicalProfile, runtime = historicalProfile)
        assertEquals(RenderId(id(6)), compatible.load().renderId)
        fixture(retained = historicalProfile).refuses(
            code = OperationErrorCode.PROVIDER_UNAVAILABLE,
            safeKey = "frozen_render_profile_unavailable",
        )
    }

    @Test fun refusesEarlierOrBrokenTerminalStates() = runTest {
        for (state in listOf("PENDING", "RUNNING", "CHECKPOINTED", "FAILED", "CANCELED")) {
            fixture().apply { job = job.copy(state = state) }.refuses()
        }
        for (mutate in listOf<(JobRow) -> JobRow>(
            { it.copy(attempt = 0) }, { it.copy(leaseGeneration = 1) }, { it.copy(leaseOwner = id(30)) },
            { it.copy(leaseUntil = 1) }, { it.copy(errorCode = "STORAGE_FAILED") },
        )) fixture().apply { job = mutate(job) }.refuses()
        for (state in listOf("INTENT", "COPYING", "SEALED", "MOVED", "QUARANTINED", "CANCELED")) {
            fixture().apply { current = current.copy(state = state) }.refuses()
        }
        fixture().apply { current = current.copy(errorCode = "STORAGE_FAILED") }.refuses()
        fixture().apply { render = render.copy(renderStatus = "DEGRADED") }.refuses()
        fixture().apply { render = render.copy(state = "QUARANTINED") }.refuses()
        fixture().apply { render = render.copy(byteCount = 0) }.refuses()
    }

    @Test fun refusesWrongProducerEvenWithExecutionAndCacheDigestsRecomputed() = runTest {
        fixture().apply {
            // Valid, rehashed execution for the consuming page's own normalized-equivalent original, not for RenderedPage.sourceId.
            render = writerRender(frozen)
                .copy(sourceId = producer.sourceId.value)
        }.refuses()
        fixture().apply {
            // Execution and render agree on the alternate tuple, but the historical journal still binds the real producer.
            render = writerRender(frozen)
        }.refuses()
        fixture().apply {
            // Joined render and journal agree but the producer's actual guarded Blob identity does not.
            val blob = metadata.blobs.getValue(producer.sourceBlobId.value)
            metadata.blobs[blob.sourceBlobId] = blob.copy(byteSha256 = "e".repeat(64))
        }.refuses()
        fixture().apply {
            val key = producer.sourceId.value
            metadata.sources[key] = metadata.sources.getValue(key).copy(conversionFingerprint = "other-v1")
        }.refuses()
        fixture().apply {
            val key = producer.sourceId.value
            metadata.sources[key] = metadata.sources.getValue(key).copy(pixelSha256 = "e".repeat(64))
        }.refuses()
        fixture().apply {
            // A structurally valid, rehashed decode stage cannot redefine the encoded dimensions of the producer.
            render = writerRender(producer, encodedSize = size)
        }.refuses()
    }

    @Test fun rejectsMalformedOrNonReadySiblingWithoutProjectingEitherRender() = runTest {
        for (sibling in listOf("", id(25))) {
            for (siblingFirst in listOf(false, true)) {
                fixture().apply {
                    siblings = if (siblingFirst) listOf(sibling, render.renderId) else listOf(render.renderId, sibling)
                    forbidRenderProjection = true
                }.refuses()
            }
        }
    }

    @Test fun refusesBadRawExtentBeforeTypedRenderOrJournalOrProducerProjection() = runTest {
        fixture().apply {
            render = render.copy(execution = ByteArray(39310))
            forbidRenderProjection = true
        }.refuses()
        fixture().apply {
            current = current.copy(payload = current.payload + 0.toByte())
            forbidJournalProjection = true
        }.refuses()
        fixture().apply {
            refusedSource = producer.sourceId.value
        }.refuses()
    }

    @Test fun refusesNoncanonicalOrMisboundJournalEvenIfByteAndRasterMetadataLookRight() = runTest {
        for (mutation in listOf<(Fixture) -> Unit>(
            { it.current = it.current.copy(expectedByteSha256 = "e".repeat(64)) },
            { it.current = it.current.copy(expectedByteCount = 6) },
            { it.current = it.current.copy(sealSha256 = "E".repeat(64)) },
            { it.current = it.current.copy(finalPath = "renders/${id(9)}/page.bin") },
            { it.current = it.current.copy(stagingPath = "staging/${id(9)}/derived.part") },
            { it.current = it.writerJournal(owner = "A".repeat(36)) },
            { it.current = it.writerJournal(generation = 1) },
            { it.current = it.writerJournal(pin = SourceId(id(12))) },
            { it.current = it.writerJournal(inputHash = "e".repeat(64)) },
            { it.current = it.writerJournal(recipeHash = "e".repeat(64)) },
            { it.current = it.current.copy(payload = it.current.payload.copyOf().apply { this[0] = 0 }) },
        )) fixture().apply { mutation(this) }.refuses()
    }

    @Test fun refusesChangedJobOrRenderHashesAndWrongOrderedJobId() = runTest {
        fixture().apply { job = job.copy(input = job.input.copyOf().apply { this[lastIndex] = 0 }) }.refuses()
        fixture().apply { pinIds = listOf(id(14), id(12)) }.refuses()
        fixture().apply { render = render.copy(executionSha256 = "e".repeat(64)) }.refuses()
        fixture().apply { render = render.copy(cacheKey = "render-cache-v1:" + "e".repeat(64)) }.refuses()
        fixture().apply { render = render.copy(rasterPixelSha256 = "e".repeat(64)) }.refuses()
        fixture().refuses(JobId(id(90)), OperationErrorCode.SOURCE_UNAVAILABLE)
    }

    private suspend fun fixture(retained: OrdinaryExportProfile = profile, runtime: OrdinaryExportProfile = profile) =
        Fixture(retained, runtime).apply { initialize() }

    private class Fixture(private val retained: OrdinaryExportProfile, private val runtime: OrdinaryExportProfile) : MetadataAccess {
        val metadata = MemoryMetadata()
        val frozen = page(10, 11, "c", retained)
        val producer = page(12, 13, "b", retained)
        var pinIds = listOf(id(14))
        var refusedSource: String? = null
        private var reading = false
        override val queries: CatalogQueries = object : CatalogQueries by metadata {
            override fun source(id: String): SourceRow? {
                check(reading && metadata.inTransaction)
                if (id == refusedSource) throw AssertionError("Projected Source despite its raw refusal")
                return metadata.source(id)
            }

            override fun sourceBlob(id: String): SourceBlobRow? {
                check(reading && metadata.inTransaction)
                if (metadata.sources[refusedSource]?.sourceBlobId == id) throw AssertionError("Projected Blob despite its raw refusal")
                return metadata.sourceBlob(id)
            }
        }
        var onTransactionExit: () -> Unit = {}
        override fun <T> transaction(action: () -> T): T {
            if (reading) throw AssertionError("The terminal reader opened a second transaction")
            reading = true
            return metadata.transaction(action).also { onTransactionExit() }
        }
        private val identity = RenderJobIdentity.create(request(frozen), emptyMap(), OrdinaryExportProfile.emptyAssetSetHash,
            frozen.conversionFingerprint, retained.rendererVersion, retained.encodingVersion, retained.backend, retained.precision)
        var job = JobRow(id(5), "PAGE_RENDER", identity.idempotenceKey, identity.canonicalInputSha256, null, null, identity.canonicalInput,
            "SUCCEEDED", 2, null, null, 2, null, null, 1, 1)
        lateinit var render: RenderedPageRow
        val journals = mutableMapOf<Long, FileTransactionRow>()
        var current: FileTransactionRow
            get() = journals.getValue(2)
            set(value) { journals[2] = value }
        lateinit var siblings: List<String>
        var forbidRenderProjection = false
        var forbidJournalProjection = false
        val journalGuardReads = mutableListOf<Triple<String, Long, Int>>()
        private var orderedJob: JobId? = null

        suspend fun initialize() {
            storeOriginal(frozen, size, 1)
            storeOriginal(producer, PixelSize(2, 3), 6)
            storeOriginal(page(14, 15, "d", retained), size, 1)
            val settings = ExportSettings(ExportFormat.PNG, listOf(PageOutputSettings(frozen.pageId, output)), null, null, emptyList())
            val snapshot = ExportSnapshot(ExportSnapshotId(id(1)), DocumentId(id(2)), 1, "f".repeat(64), settings, listOf(frozen), 1)
            val assemblyId = JobId(id(3))
            val input = ExportAssemblyInput.encode(snapshot.snapshotId, snapshot.snapshotHash, listOf(JobId(job.jobId)))
            val assembly = AssemblyJobIdentityRow(assemblyId.value, "EXPORT_ASSEMBLY", ExportAssemblyInput.key(snapshot.snapshotId),
                ExportAssemblyInput.digest(input), snapshot.snapshotId.value, input)
            val ordered = RoomExportAssemblyReads(metadata, { it == assemblyId.value }, { assembly }, Dispatchers.Unconfined).load(assemblyId, snapshot)
            orderedJob = ordered[frozen.ordinal]
            render = writerRender(producer)
            current = writerJournal()
            siblings = listOf(render.renderId)
        }

        private fun storeOriginal(page: ExportPageSnapshot, encoded: PixelSize, orientation: Int) {
            val source = page.sourceId.value
            val blob = page.sourceBlobId.value
            metadata.sources[source] = SourceRow(source, blob, pixels, 3, 2, orientation, "srgb-v1", null, 1, "READY", 0)
            metadata.blobs[blob] = SourceBlobRow(blob, page.byteHash, 9, "image/png", encoded.width, encoded.height,
                "sources/$blob/original.bin", "READY", 1, id(20), 0)
        }

        suspend fun writerRender(page: ExportPageSnapshot, encodedSize: PixelSize = if (page === producer) PixelSize(2, 3) else size): RenderedPageRow {
            val proof = RoomFrozenRenderReads(metadata, { _, extent -> extent == job.input.size }, { identityRow() }, { pinIds },
                metadata::sourceReadIsBounded, retained, Dispatchers.Unconfined).verified(JobId(job.jobId), page)
            val stages = listOf(
                StageRecord("source_decode_v1", 1, null, "OK", encodedSize, size, emptyList()),
                StageRecord("geometry_none_v1", 1, null, "EXACT_PERMUTATION", size, size, emptyList()),
                StageRecord("none_v1", 1, null, "EXACT_IDENTITY", size, size, emptyList()),
            )
            val execution = RenderResult(request(page), RenderStatus.OK, RgbImage(3, 2, 9, ByteArray(18) { (it + 1).toByte() }),
                stages, emptyList()).use { OrdinaryRenderExecution.capture(proof, it) {} }
            return RenderedPageRow(id(6), job.jobId, page.sourceId.value, page.pageId.value, 4, execution.cacheKey, execution.bytes(),
                execution.sha256, "renders/${id(6)}/page.bin", encodedHash, rasterHash, 5, 3, 2, "OK", "READY")
        }

        fun writerJournal(attempt: Long = 2, generation: Long = attempt, owner: String = id(8), pin: SourceId = SourceId(id(14)),
            inputHash: String = identity.canonicalInputSha256, recipeHash: String = producer.recipeHash): FileTransactionRow {
            val transactionId = TransactionId(id(if (attempt == 2L) 7 else 30 + attempt.toInt()))
            val renderId = RenderId(id(if (attempt == 2L) 6 else 40 + attempt.toInt()))
            val intent = RenderFileIntent(transactionId, renderId, JobId(job.jobId), owner, generation, attempt,
                inputHash, producer.pageId, producer.sourceId, producer.sourceBlobId, pin, producer.byteHash, pixels, 4, recipeHash, output)
            return FileTransactionRow(transactionId.value, "RENDER", job.jobId, "COMMITTED", intent.stagingPath, intent.finalPath,
                sealHash, encodedHash, 5, intent.journalPayload(), attempt, null, 1)
        }

        private fun identityRow() = FrozenRenderJobIdentityRow(job.jobId, job.kind, job.idempotenceKey, job.canonicalInputSha256, job.input)
        private fun <T> sql(action: () -> T): T {
            check(metadata.inTransaction)
            return action()
        }

        suspend fun load(jobId: JobId = checkNotNull(orderedJob)) = RoomTerminalRenderReads(this, runtime, Dispatchers.Unconfined,
            { id, extent -> sql { if (id == job.jobId) job.input.size == extent else null } },
            { id -> sql { identityRow().takeIf { it.jobId == id } } },
            { id -> sql { if (id == job.jobId) pinIds else emptyList() } },
            { id -> sql { if (id == refusedSource) false else metadata.sourceReadIsBounded(id) } },
            { id -> sql { job.attempt.takeIf { id == job.jobId && job.state == "SUCCEEDED" && job.attempt > 0 &&
                job.attempt == job.leaseGeneration && job.leaseOwner == null && job.leaseUntil == null && job.errorCode == null } } },
            { id -> sql { if (id == job.jobId) siblings else emptyList() } },
            { id, owner, page, revision, width, height, cap -> sql {
                if (id != render.renderId) null else render.jobId == owner && render.pageId == page && render.recipeRevision == revision &&
                    render.width == width && render.height == height && cap == 39309 && render.execution.size in 1..cap
            } },
            { id -> sql {
                if (forbidRenderProjection) throw AssertionError("Projected a nonunique or unsafe render row")
                render.takeIf { it.renderId == id }
            } },
            { id, attempt, extent -> sql {
                journalGuardReads += Triple(id, attempt, extent)
                journals[attempt]?.takeIf { it.ownerId == id && it.kind == "RENDER" }?.let { it.payload.size == extent }
            } },
            { id, attempt -> sql {
                if (forbidJournalProjection) throw AssertionError("Projected an unsafe journal row")
                journals[attempt]?.takeIf { it.ownerId == id && it.kind == "RENDER" }
            } },
        ).load(jobId, frozen)

        suspend fun refuses(
            requested: JobId = checkNotNull(orderedJob),
            code: OperationErrorCode = OperationErrorCode.INTEGRITY_FAILED,
            safeKey: String? = null,
        ) {
            val failure = try {
                load(requested)
                throw AssertionError("Unauthenticated metadata produced a receipt")
            } catch (failure: RepositoryFailure) {
                failure
            }
            assertEquals(code, failure.error.code)
            safeKey?.let { assertEquals(it, failure.error.safeMessageKey) }
            assertEquals(requested.value, failure.error.affectedId)
            assertFalse(failure.error.retryable)
        }
    }

    private companion object {
        fun id(n: Int) = UUID(0, n.toLong()).toString()
        val size = PixelSize(3, 2)
        val output = OutputSpec(size, RenderPurpose.EXPORT)
        val pixels = "a".repeat(64)
        val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding(pixels, size, "srgb-v1")))
        val profile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")
        val historicalProfile = OrdinaryExportProfile("renderer-v1-retained", "rgb8-v1-retained", "reference-retained", "binary64")
        val sealHash = "9".repeat(64) // A recorded digest, not a claim that a seal exists.
        val encodedHash = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(80, 78, 71, 17, 4)).joinToString("") { "%02x".format(it) }
        const val rasterHash = "69383a3ea68c8e12c19b9f0b604a888357ef9a8c3f180f6e50c60faea74df7c4"
        fun request(page: ExportPageSnapshot) =
            RenderRequest(page.sourceId.value, pixels, page.pageId.value, 4, recipe, output, 0, null, FallbackPolicy.FAIL)
        fun page(source: Int, blob: Int, hashDigit: String, retained: OrdinaryExportProfile) = ExportPageSnapshot(ExportPageRow(id(1), 0, id(4), id(source), id(blob),
            hashDigit.repeat(64), pixels, 3, 2, "srgb-v1", 4, 1, RecipeCodec.encode(recipe), RecipeCodec.hash(recipe),
            "[]".encodeToByteArray(), retained.providerDescriptor(), OrdinaryExportProfile.emptyAssetSetHash,
            retained.rendererVersion, 3, 2, 3, 2, "FAIL", null, null))
    }
}
