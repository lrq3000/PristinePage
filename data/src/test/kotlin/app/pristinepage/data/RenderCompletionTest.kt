package app.pristinepage.data

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.RenderFileExecutionTest.RenderFileFixture
import app.pristinepage.data.db.ExportPageRow
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.RenderedPageRow
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.FrozenRenderJobIdentityRow
import app.pristinepage.data.repository.LeaseOwnerState
import app.pristinepage.data.repository.RoomFrozenRenderReads
import app.pristinepage.data.repository.RoomRenderCompletion
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RenderCompletionTest {
    @Test fun executionGoldenRecordsActualOrderWarningsAndRasterWithoutOwningTheImage() = runTest {
        fun id(n: Int) = java.util.UUID(0L, n.toLong()).toString()
        val size = PixelSize(3, 2)
        val output = OutputSpec(size, RenderPurpose.EXPORT)
        val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding("a".repeat(64), size, "srgb-v1")))
        val profile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")
        val assets = OrdinaryExportProfile.emptyAssetSetHash
        val page = ExportPageSnapshot(ExportPageRow(id(1), 0, id(11), id(12), id(13), "b".repeat(64), "a".repeat(64),
            3, 2, "srgb-v1", 4, 1, RecipeCodec.encode(recipe), RecipeCodec.hash(recipe), "[]".encodeToByteArray(),
            profile.providerDescriptor(), assets, "renderer-v1", 3, 2, 3, 2, "FAIL", null, null))
        val request = RenderRequest(id(12), "a".repeat(64), id(11), 4, recipe, output, 0, null, FallbackPolicy.FAIL)
        val identity = RenderJobIdentity.create(request, emptyMap(), assets, "srgb-v1", "renderer-v1", "rgb8-v1", "reference", "binary64")
        val job = FrozenRenderJobIdentityRow(id(14), "PAGE_RENDER", identity.idempotenceKey, identity.canonicalInputSha256, identity.canonicalInput)
        val metadata = MemoryMetadata().apply {
            sources[id(12)] = SourceRow(id(12), id(13), page.pixelHash, 3, 2, 6, "srgb-v1", null, 1, "READY", 0)
            blobs[id(13)] = SourceBlobRow(id(13), page.byteHash, 4, "image/png", 2, 3, "sources/${id(13)}/original.bin", "READY", 1, id(15), 0)
        }
        val proof = RoomFrozenRenderReads(metadata, { _, size -> size == job.input.size }, { job }, { listOf(id(12)) },
            metadata::sourceReadIsBounded, profile, Dispatchers.Unconfined).verified(JobId(job.jobId), page)
        val stages = stages(size, size)
        val owner = RenderResult(request, RenderStatus.OK, raster(), stages, listOf("W3", "W1"))
        val record = owner.use { OrdinaryRenderExecution.capture(proof, it) {} }
        val envelope = record.bytes()
        // Independent Python struct/hashlib oracle: u32 length-prefixed ASCII fields, BE numeric values, ordered actual stages and warnings, packed RGB 1..18.
        assertEquals(791, envelope.size)
        assertEquals("6b5e82b5f7f7939fa67f57d0c9b1090a93807be035549904ad9c9464cf4bf065", hash(envelope))
        assertEquals("render-cache-v1:2d64c0a4ac5078042f43e8f2ffa9cf70b5ddbb479f291fd465bf3127721f5742", record.cacheKey)
        assertEquals(rasterHash, record.rasterSha256)
        envelope.fill(0)
        assertEquals(record.sha256, hash(record.bytes()))
        RenderResult(request, RenderStatus.OK, raster(), stages, listOf("W1", "W3")).use {
            val reordered = OrdinaryRenderExecution.capture(proof, it) {}
            assertNotEquals(record.sha256, reordered.sha256)
            assertNotEquals(record.cacheKey, reordered.cacheKey)
        }
    }

    @Test fun publishesOneReadyRowFromLiveReadbackAndDoesNotConsumeTheResultOwner() = runTest {
        val f = fixture()
        val originalJob = f.job
        val pins = f.retainedPins()
        var closes = 0
        f.result(ownership = AutoCloseable { closes++ }).use { result ->
            assertEquals(f.intent.renderId, f.complete(result))
            assertEquals(0, closes)
            assertEquals(18, result.image!!.getUnsigned(2, 1, 2))
        }
        assertEquals(1, closes)
        val ready = f.records.single()
        assertEquals(f.intent.renderId.value, ready.renderId)
        assertEquals(f.lease.jobId.value, ready.jobId)
        assertEquals(f.proof.page.sourceId.value, ready.sourceId)
        assertEquals(f.intent.finalPath, ready.relativePath)
        assertEquals("READY", ready.state)
        assertEquals("OK", ready.renderStatus)
        assertEquals(3 to 2, ready.width to ready.height)
        assertEquals(rasterHash, ready.rasterPixelSha256)
        assertEquals(hash(encoded), ready.byteSha256)
        assertEquals(encoded.size.toLong(), ready.byteCount)
        assertEquals(hash(ready.execution), ready.executionSha256)
        assertEquals("render-cache-v1:${hash(("pristinepage-ordinary-render-cache-v1\u0000" +
            originalJob.idempotenceKey + ready.executionSha256).encodeToByteArray())}", ready.cacheKey)
        assertEquals("COMMITTED", f.row.state)
        assertEquals(ready.byteSha256, f.row.expectedByteSha256)
        assertArrayEquals(f.intent.journalPayload(), f.row.payload)
        assertEquals(originalJob.copy(state = "SUCCEEDED", leaseOwner = null, leaseUntil = null, updatedAt = f.job.updatedAt), f.job)
        assertEquals(pins, f.retainedPins())
        assertArrayEquals(encoded, f.final.readBytes())
        f.result().use { result ->
            f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.complete(result, writer = { error("Already succeeded") }) }
        }
        assertSame(ready, f.records.single())
    }

    @Test fun executionBoundMovedStillRequiresSameRasterFromReadback() = runTest {
        val f = fixture()
        val attempt = f.newCompletion()
        f.bindMoved(attempt)
        val moved = f.row
        val final = f.final.readBytes()
        val seal = f.seal.readBytes()
        var seen = false
        f.result().use { result ->
            f.refuses(OperationErrorCode.INTEGRITY_FAILED) {
                f.complete(result, writer = { error("No second writer") }, readback = { file, _ ->
                    assertEquals(f.final, file)
                    seen = true
                    "ab".repeat(32) // Valid digest grammar, but not the sealed and actual borrowed raster.
                }, completion = attempt)
            }
        }
        assertTrue(seen)
        f.assertMoved(moved, final, seal)
    }

    @Test fun exactRequestStatusDimensionsAndBoundedStagesAreRequiredBeforeWriting() = runTest {
        val f = fixture()
        val request = f.request()
        val cases = listOf(
            f.result(request.copy(generation = 1)),
            f.result(request.copy(output = request.output.copy(purpose = RenderPurpose.PREVIEW))),
            f.result(request.copy(fallback = FallbackPolicy.KEEP_STAGE_INPUT)),
            f.result(request.copy(protectionMaskId = f.proof.pinSourceId)),
            f.result(request.copy(sourceId = f.intent.renderId.value)),
            f.result(request.copy(sourcePixelHash = "6".repeat(64))),
            f.result(request.copy(recipeRevision = 2)),
            f.result(request.copy(recipe = request.recipe.copy(binary = app.pristinepage.core.recipe.BinaryRecipe.GrayscaleV1))),
            f.result(status = RenderStatus.DEGRADED),
            RenderResult(request, RenderStatus.CANCELED, null, emptyList(), emptyList()),
            f.result(image = RgbImage(2, 3, 6, ByteArray(18))),
            f.result(stages = emptyList()),
            f.result(stages = f.stages() + List(32) { f.stages().last() }),
            f.result(warnings = listOf("x".repeat(129))),
        )
        for (result in cases) result.use {
            f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.complete(result, writer = { error("Invalid result reached writer") }) }
            assertEquals("INTENT", f.row.state)
            assertTrue(f.records.isEmpty())
            assertFalse(f.final.exists())
        }
    }

    @Test fun movedRetryCannotAttributeBytesToDifferentWarningsAfterLateCasRefusal() = runTest {
        val f = fixture()
        val completion = RoomRenderCompletion(f.executor, f.completionRows, Dispatchers.Unconfined)
        val pins = f.retainedPins()
        val job = f.job
        var writes = 0
        assertEquals("INTENT", f.row.state)
        assertFalse(f.final.exists())
        assertFalse(f.seal.exists())

        f.result().use { a ->
            val expected = OrdinaryRenderExecution.capture(f.proof, a) {}
            f.onLeaseWrite = {
                // Refuse the joint transaction after both the render INSERT and exact journal CAS have run.
                assertEquals("COMMITTED", f.row.state)
                assertEquals("SUCCEEDED", it.state)
                assertEquals(expected.sha256, f.records.single().executionSha256)
                false
            }
            f.refuses(OperationErrorCode.REVISION_CONFLICT) {
                completion.complete(f.proof, f.lease, f.intent, a, encoded.size.toLong(), {
                    assertEquals("COPYING", f.row.state)
                    writes++
                    it.write(encoded)
                }, ::readback)
            }
            val moved = f.row
            val final = f.final.readBytes()
            val seal = f.seal.readBytes()
            assertEquals(1, writes)
            assertArrayEquals(encoded, final)
            f.assertMoved(moved, final, seal)
            assertEquals(job, f.job)
            assertEquals(pins, f.retainedPins())

            f.onLeaseWrite = { true }
            f.result(warnings = a.warnings.reversed()).use { b ->
                assertEquals(a.request, b.request)
                assertEquals(a.status, b.status)
                assertEquals(a.stages, b.stages)
                assertNotEquals(a.warnings, b.warnings)
                val replacement = OrdinaryRenderExecution.capture(f.proof, b) {}
                assertEquals(expected.rasterSha256, replacement.rasterSha256)
                assertNotEquals(expected.sha256, replacement.sha256)
                assertNotEquals(expected.cacheKey, replacement.cacheKey)
                try {
                    completion.complete(f.proof, f.lease, f.intent, b, encoded.size.toLong(),
                        { fail("MOVED retry invoked B's writer") }, { _, _ -> error("Changed provenance must refuse before file I/O") })
                    fail("B published A's retained bytes with execution ${f.records.singleOrNull()?.executionSha256}; expected refusal")
                } catch (failure: RepositoryFailure) {
                    assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
                }
            }
            f.assertMoved(moved, final, seal)
            assertEquals(job, f.job)
            assertEquals(pins, f.retainedPins())

            var readbacks = 0
            assertEquals(f.intent.renderId, completion.complete(f.proof, f.lease, f.intent, a, encoded.size.toLong(),
                { fail("MOVED retry reran A's writer") }, { file, checkpoint ->
                    assertEquals(f.final, file)
                    readbacks++
                    readback(file, checkpoint)
                }))
            assertEquals(1, readbacks)
            val ready = f.records.single()
            assertArrayEquals(expected.bytes(), ready.execution)
            assertEquals(expected.sha256, ready.executionSha256)
            assertEquals(expected.cacheKey, ready.cacheKey)
            assertEquals("COMMITTED", f.row.state)
            assertEquals("SUCCEEDED", f.job.state)
            assertEquals(pins, f.retainedPins())
            assertArrayEquals(final, f.final.readBytes())
            assertArrayEquals(seal, f.seal.readBytes())
        }
    }

    @Test fun newScopeCannotReplayAnotherScopesAuthenticMovedBytes() = runTest {
        val f = fixture()
        val original = f.newCompletion()
        f.bindMoved(original)
        val moved = f.row
        val final = f.final.readBytes()
        val seal = f.seal.readBytes()
        val pins = f.retainedPins()
        f.result().use { result ->
            f.refuses(OperationErrorCode.INTEGRITY_FAILED) {
                f.complete(result, writer = { error("New scope invoked writer") }, readback = { _, _ ->
                    error("New scope must refuse before readback")
                })
            }
        }
        f.assertMoved(moved, final, seal)
        assertEquals(pins, f.retainedPins())
    }

    @Test fun matchingFileOnlyMovedCannotPublishWithoutProducingExecution() = runTest {
        val f = fixture()
        f.makeMoved()
        val moved = f.row
        val final = f.final.readBytes()
        val seal = f.seal.readBytes()
        val pins = f.retainedPins()
        f.result().use { result ->
            f.refuses(OperationErrorCode.INTEGRITY_FAILED) {
                f.complete(result, writer = { fail("File-only MOVED invoked a writer") })
            }
        }
        f.assertMoved(moved, final, seal)
        assertEquals(pins, f.retainedPins())
    }

    @Test fun failedFreshCopyingCasCannotBindExecutionBeforeTheWriterGrant() = runTest {
        val f = fixture()
        val attempt = f.newCompletion()
        var attemptedCopying = false
        f.phaseCasAllowed = { next ->
            assertEquals("COPYING", next.state)
            attemptedCopying = true
            false
        }
        f.result().use { a ->
            f.refuses(OperationErrorCode.REVISION_CONFLICT) {
                f.complete(a, writer = { error("Copying CAS did not commit") }, completion = attempt)
            }
        }
        assertTrue(attemptedCopying)
        assertEquals("INTENT", f.row.state)
        assertFalse(f.stage.exists())
        assertTrue(f.records.isEmpty())

        f.phaseCasAllowed = { true }
        f.result(warnings = listOf("FRESH_WRITER")).use { b ->
            val execution = OrdinaryRenderExecution.capture(f.proof, b) {}
            assertEquals(f.intent.renderId, f.complete(b, completion = attempt))
            assertEquals(execution.sha256, f.records.single().executionSha256)
        }
    }

    @Test fun reverifiedMovedRollsBackAfterJournalOrLeaseCasRefusalAndCanRetry() = runTest {
        val f = fixture()
        val attempt = f.newCompletion()
        f.bindMoved(attempt)
        val moved = f.row
        val seal = f.seal.readBytes()
        var attemptedCas = false
        f.phaseCasAllowed = {
            assertEquals("COMMITTED", it.state)
            assertEquals(1, f.records.size)
            attemptedCas = true
            false
        }
        f.result().use { result ->
            f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.complete(result, completion = attempt) }
            assertTrue(attemptedCas)
            f.assertMoved(moved, encoded, seal)
            f.phaseCasAllowed = { true }
            var attemptedSuccess = false
            f.onLeaseWrite = {
                assertEquals("SUCCEEDED", it.state)
                assertEquals("COMMITTED", f.row.state)
                attemptedSuccess = true
                false
            }
            f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.complete(result, completion = attempt) }
            assertTrue(attemptedSuccess)
            f.assertMoved(moved, encoded, seal)
            f.onLeaseWrite = { true }
            assertEquals(f.intent.renderId, f.complete(result, writer = { error("Already moved") }, completion = attempt))
            assertEquals("COMMITTED", f.row.state)
            assertArrayEquals(seal, f.seal.readBytes())
            assertArrayEquals(encoded, f.final.readBytes())
        }
    }

    @Test fun expiryAndLivenessSampleAfterLastSqlRollBackAllWrites() = runTest {
        for (expire in listOf(true, false)) {
            val f = fixture()
            val attempt = f.newCompletion()
            f.bindMoved(attempt)
            val moved = f.row
            val seal = f.seal.readBytes()
            var late = false
            f.onPinRead = {
                if (f.job.state == "SUCCEEDED") {
                    if (expire) f.elapsed = 150 else f.ownerState = LeaseOwnerState.UNKNOWN
                    late = true
                }
            }
            f.result().use { result -> f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.complete(result, completion = attempt) } }
            assertTrue(late)
            f.assertMoved(moved, encoded, seal)
        }
    }

    @Test fun canceledCoroutineAfterEachWriteCannotPublishAnyRows() = runTest {
        for (write in listOf("insert", "journal CAS", "job CAS")) {
            val f = fixture()
            val attempt = f.newCompletion()
            f.bindMoved(attempt)
            val moved = f.row
            val seal = f.seal.readBytes()
            var reached = false
            var returned = false
            val worker = launch(Dispatchers.Unconfined) {
                val cancelAfterWrite = {
                    assertEquals(1, f.records.size)
                    assertEquals(if (write == "insert") "MOVED" else "COMMITTED", f.row.state)
                    assertEquals(if (write == "job CAS") "SUCCEEDED" else "RUNNING", f.job.state)
                    reached = true
                    cancel()
                }
                when (write) {
                    "insert" -> f.onRecordInsert = cancelAfterWrite
                    "journal CAS" -> f.afterTransition = cancelAfterWrite
                    else -> f.onLeaseWrite = { cancelAfterWrite(); true }
                }
                f.result().use { f.complete(it, completion = attempt) }
                returned = true
            }
            worker.join()
            assertTrue(reached)
            assertTrue(worker.isCancelled)
            assertFalse(returned)
            f.assertMoved(moved, encoded, seal)
            f.onRecordInsert = {}
            f.afterTransition = {}
            f.onLeaseWrite = { true }
            f.result().use { result ->
                assertEquals(f.intent.renderId, f.complete(result, writer = { error("Canceled retry already moved") }, completion = attempt))
            }
            assertArrayEquals(encoded, f.final.readBytes())
            assertArrayEquals(seal, f.seal.readBytes())
        }
    }

    @Test fun auditOnlyJournalDriftAndMissingRetainedPinRollBackSuccess() = runTest {
        for (pin in listOf(true, false)) {
            val f = fixture()
            val attempt = f.newCompletion()
            f.bindMoved(attempt)
            val moved = f.row
            val originalPins = f.retainedPins()
            val seal = f.seal.readBytes()
            f.onLeaseWrite = {
                if (pin) f.clearJobPins() else f.row = f.row.copy(updatedAt = f.row.updatedAt + 1)
                true
            }
            f.result().use { result -> f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.complete(result, completion = attempt) } }
            f.assertMoved(moved, encoded, seal)
            assertEquals(originalPins, f.retainedPins())
        }
    }

    @Test fun lateMalformedJobIsNotProjectedAndRenderCollisionIsNotAdopted() = runTest {
        val f = fixture()
        val attempt = f.newCompletion()
        f.bindMoved(attempt)
        val moved = f.row
        val seal = f.seal.readBytes()
        var reads = -1
        f.onRecordInsert = { reads = f.jobReads; f.jobIsBounded = false }
        f.result().use { result -> f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.complete(result, completion = attempt) } }
        assertTrue(reads > 0)
        assertEquals(reads, f.jobReads)
        f.assertMoved(moved, encoded, seal)
        f.jobIsBounded = true
        f.onRecordInsert = { error("Must not try inserting around an occupied exact key") }
        val occupied = RenderedPageRow(f.intent.renderId.value, f.job.jobId, f.proof.pinSourceId,
            f.proof.page.pageId.value, 1, "bad-key", ByteArray(512 * 1024), "bad-digest", "bad-path", "bad-bytes", "bad-pixels",
            1, 1, 1, "bad-status", "bad-state")
        f.records += occupied
        f.result().use { result -> f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.complete(result, completion = attempt) } }
        assertSame(occupied, f.records.single())
        assertSame(moved, f.row)
        assertEquals("RUNNING", f.job.state)
        assertArrayEquals(encoded, f.final.readBytes())
    }

    private suspend fun fixture() = RenderFileFixture().apply { initialize() }

    private fun RenderFileFixture.request() = RenderRequest(proof.page.sourceId.value, proof.page.pixelHash,
        proof.page.pageId.value, proof.page.recipeRevision, RecipeCodec.decode(proof.page.canonicalRecipe), proof.page.output, 0, null, FallbackPolicy.FAIL)

    private fun RenderFileFixture.stages() = stages(proof.page.sourceSize, proof.page.output.size)

    private fun stages(source: PixelSize, output: PixelSize) = listOf(
        StageRecord("source_decode_v1", 1, null, "OK", PixelSize(2, 3), source, listOf("SOURCE_PIXELS_VERIFIED")),
        StageRecord("geometry_none_v1", 1, null, "EXACT_PERMUTATION", source, output, emptyList()),
        StageRecord("none_v1", 1, null, "EXACT_IDENTITY", output, output, listOf("W2", "W1")),
    )

    private fun RenderFileFixture.result(request: RenderRequest = request(), status: RenderStatus = RenderStatus.OK,
        image: RgbImage = raster(),
        stages: List<StageRecord> = stages(), warnings: List<String> = listOf("W3", "W1"), ownership: AutoCloseable? = null,
    ) = RenderResult(request, status, image, stages, warnings, ownership)

    private suspend fun RenderFileFixture.makeMoved() = execute(cap = encoded.size.toLong(),
        writer = { it.write(encoded) }, readback = ::readback)

    private suspend fun RenderFileFixture.bindMoved(completion: RoomRenderCompletion) {
        assertEquals("INTENT", row.state)
        onLeaseWrite = { false }
        try {
            result().use { refuses(OperationErrorCode.REVISION_CONFLICT) { complete(it, completion = completion) } }
        } finally {
            onLeaseWrite = { true }
        }
        assertEquals("MOVED", row.state)
        assertTrue(records.isEmpty())
        assertArrayEquals(encoded, final.readBytes())
        assertTrue(seal.isFile)
    }

    private fun RenderFileFixture.assertMoved(moved: FileTransactionRow, finalBytes: ByteArray, sealBytes: ByteArray) {
        assertEquals("MOVED", row.state)
        assertSame(moved, row)
        assertTrue(records.isEmpty())
        assertEquals("RUNNING", job.state)
        assertEquals(lease.owner, job.leaseOwner)
        assertArrayEquals(finalBytes, final.readBytes())
        assertArrayEquals(sealBytes, seal.readBytes())
    }

    private fun RenderFileFixture.newCompletion() =
        RoomRenderCompletion(executor, completionRows, Dispatchers.Unconfined)

    private suspend fun RenderFileFixture.complete(result: RenderResult, writer: (OutputStream) -> Unit = { it.write(encoded) },
        readback: (File, () -> Unit) -> String? = ::readback, completion: RoomRenderCompletion = newCompletion()): RenderId =
        completion.complete(proof, lease, intent, result, encoded.size.toLong(), writer, readback)

    private companion object {
        fun raster() = RgbImage(3, 2, 10, ByteArray(19) { if (it == 9) 99 else (it + if (it < 9) 1 else 0).toByte() })
        val encoded = byteArrayOf(80, 78, 71, 17, 4) // Synthetic encoded payload; the oracle below is intentionally not a codec.
        val rasterHash = "69383a3ea68c8e12c19b9f0b604a888357ef9a8c3f180f6e50c60faea74df7c4"
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun readback(file: File, checkpoint: () -> Unit): String? {
            checkpoint()
            return rasterHash.takeIf { file.readBytes().contentEquals(encoded) }
        }
    }
}
