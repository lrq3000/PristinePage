package app.pristinepage.data

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.RenderFileExecutionTest.RenderFileFixture
import app.pristinepage.data.RoomFrozenRenderAdmissionTest.Fixture as AdmissionFixture
import app.pristinepage.data.db.ExportPageRow
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.RenderedPageRow
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.files.DerivedFiles
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import app.pristinepage.data.repository.RoomJobLeases.FrozenRenderAcquisition
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
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

    @Test fun admittedJobOriginalBindsIntentExecutionReadyAndTerminalMetadata() = runTest {
        val f = producerAttempt()
        val page = f.frozen.page
        val before = f.frozen.stored()
        val recipeBytes = page.canonicalRecipe.copyOf()
        val intent = f.prepare()
        val journal = checkNotNull(f.journal)
        val expectedPayload = f.expectedJournalPayload(intent)
        assertEquals(f.lease.jobId, intent.jobId)
        assertEquals(f.lease.generation, intent.generation)
        assertEquals(f.lease.generation, intent.attempt)
        assertEquals(f.lease.owner, intent.leaseOwner)
        assertEquals(before.job.canonicalInputSha256, intent.canonicalInputSha256)
        assertEquals(page.pageId, intent.pageId)
        assertEquals(page.recipeRevision, intent.recipeRevision)
        assertEquals(page.recipeHash, intent.recipeSha256)
        assertEquals(page.output, intent.output)
        assertEquals(page.pixelHash, intent.sourcePixelSha256)
        assertEquals(SourceId(f.source.sourceId), intent.sourceId)
        assertEquals(SourceBlobId(f.blob.sourceBlobId), intent.sourceBlobId)
        assertEquals(hash(producerOriginal), intent.sourceByteSha256)
        assertEquals(SourceId(f.source.sourceId), intent.jobPinSourceId)
        assertEquals("INTENT", journal.state)
        assertEquals(intent.transactionId.value, journal.transactionId)
        assertEquals(intent.attempt, journal.attempt)
        assertArrayEquals(expectedPayload, intent.journalPayload())
        assertArrayEquals(expectedPayload, journal.payload)
        f.assertNoFiles(intent)

        var closes = 0
        f.result(ownership = AutoCloseable { closes++ }).use { result ->
            assertEquals(intent.renderId, f.complete(intent, result))
            assertEquals(0, closes)
            assertEquals(18, result.image!!.getUnsigned(2, 1, 2))
        }
        assertEquals(1, closes)
        assertEquals(1, f.writes)
        assertTrue(f.readbacks > 0)
        assertEquals(listOf("COPYING", "SEALED", "MOVED", "COMMITTED"), f.phaseWrites)
        assertEquals(0, page.ordinal)
        assertArrayEquals(recipeBytes, page.canonicalRecipe)
        val job = f.frozen.job
        f.frozen.assertStored(before.copy(job = before.job.copy(state = "SUCCEEDED", leaseOwner = null,
            leaseUntil = null, updatedAt = job.updatedAt)))
        val ready = f.records.single()
        assertEquals(intent.renderId.value, ready.renderId)
        assertEquals(before.job.jobId, ready.jobId)
        assertEquals(page.pageId.value, ready.pageId)
        assertEquals(page.recipeRevision, ready.recipeRevision)
        assertEquals(f.source.sourceId, ready.sourceId)
        assertEquals("READY", ready.state)
        assertEquals("OK", ready.renderStatus)
        assertEquals(page.output.size, PixelSize(ready.width, ready.height))
        assertEquals(intent.finalPath, ready.relativePath)
        assertEquals(hash(encoded), ready.byteSha256)
        assertEquals(encoded.size.toLong(), ready.byteCount)
        assertEquals(rasterHash, ready.rasterPixelSha256)
        assertEquals(hash(ready.execution), ready.executionSha256)
        assertEquals("render-cache-v1:${hash(("pristinepage-ordinary-render-cache-v1\u0000" +
            before.job.idempotenceKey + ready.executionSha256).encodeToByteArray())}", ready.cacheKey)
        assertArrayEquals(encoded, f.file(intent.finalPath).readBytes())

        // Read the persisted envelope, not an expected envelope generated by capture(). The reader authenticates the unchanged v1 key/page/recipe/policy fields.
        val record = OrdinaryRenderExecutionRead.read(ready.execution, f.admitted.proof, ready.executionSha256,
            ready.cacheKey, ready.rasterPixelSha256, page.output.size)
        assertEquals(OrdinaryRenderExecutionRead.SourceRelation.NORMALIZED_COMPATIBLE_REUSE, record.sourceRelation)
        assertEquals(OrdinaryRenderExecutionRead.UnprovenProducer(SourceId(f.source.sourceId), SourceBlobId(f.blob.sourceBlobId),
            hash(producerOriginal)), record.unprovenProducer)
        assertEquals(stages(page.sourceSize, page.output.size), record.stages) // The literal first input is 2×3, not the frozen 3×2.
        assertEquals(listOf("W3", "W1"), record.warnings)

        val committed = checkNotNull(f.journal)
        assertEquals("COMMITTED", committed.state)
        assertEquals(journal.transactionId, committed.transactionId)
        assertArrayEquals(expectedPayload, committed.payload)
        val terminal = f.loadTerminal()
        assertEquals(intent.transactionId, terminal.transactionId)
        assertEquals(intent.renderId, terminal.renderId)
        assertEquals(page.output.size, terminal.size)
        assertEquals(hash(f.seal(intent).readBytes()), terminal.sealSha256)
        assertEquals(hash(encoded), terminal.byteSha256)
        assertEquals(encoded.size.toLong(), terminal.byteCount)
        assertEquals(rasterHash, terminal.rasterSha256)
        assertArrayEquals(expectedPayload, terminal.intentPayload())
    }

    @Test fun producerWriterRefusesOkResultNamingFrozenConsumer() = runTest {
        val f = producerAttempt()
        val intent = f.prepare()
        f.assertRefusesResult(intent, request = f.request().copy(sourceId = f.frozen.page.sourceId.value),
            stages = f.correctStages(), witness = "Frozen consumer was accepted as the producing Source")
    }

    @Test fun producerWriterRefusesOkResultWithWrongEncodedDecodeSize() = runTest {
        val f = producerAttempt()
        val intent = f.prepare()
        val stages = f.correctStages().toMutableList()
        // Only the initial input changes. The resulting stage chain and final raster still have the right normalized shape.
        stages[0] = stages[0].copy(inputSize = f.frozen.page.sourceSize)
        f.assertRefusesResult(intent, request = f.request(), stages = stages,
            witness = "Producer was accepted with the frozen consumer's encoded dimensions")
    }

    @Test fun producerLeaseRefusesForeignAdmissionEvenWhenBothStoresHaveIdenticalRows() = runTest {
        val issuer = AdmissionFixture(traceSourceProjection = false).apply { freeze() }
        val receiver = issuer.forkPendingRender()
        assertNotSame(issuer.catalog, receiver.catalog)
        receiver.assertStored(issuer.stored())
        val foreign = checkNotNull(issuer.admit())
        val local = checkNotNull(receiver.admit())
        val before = receiver.stored()
        receiver.assertStored(issuer.stored())
        assertEquals(foreign.proof.pinnedBinding, local.proof.pinnedBinding)

        val (source, blob) = receiver.bindingFor(receiver.pinIds().single())
        val f = ProducerAttempt(receiver, local, source, blob)
        val writes = mutableListOf<String>()
        f.afterMetadataWrite = { writes += it }
        val refused = f.acquireReceipt(foreign)
        if (refused !is FrozenRenderAcquisition.ProducerResult.Rejected) {
            throw AssertionError("A foreign admission was not refused by the matching local store: $refused")
        }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, refused.error.code)
        assertFalse(refused.error.retryable)
        assertEquals(receiver.jobId.value, refused.error.affectedId)
        assertTrue(writes.isEmpty())
        receiver.assertStored(before)

        f.acquire()
        assertEquals(listOf("RUNNING"), writes)
        assertEquals(receiver.jobId, f.lease.jobId)
        assertEquals("RUNNING", receiver.job.state)
        issuer.assertStored(before)
    }

    @Test fun producerFileJoinRefusesLegacyExecutionWithWrongDecodeSizeEvenWhenSourceMatches() = runTest {
        val frozen = AdmissionFixture(traceSourceProjection = false).apply { freeze() }
        val (source, blob) = frozen.bindingFor(frozen.pinIds().single())
        val f = ProducerAttempt(frozen, checkNotNull(frozen.admit()), source, blob).apply { acquire() }
        val intent = f.prepare()
        val row = checkNotNull(f.journal)
        val payload = row.payload.copyOf()
        val running = frozen.stored()
        assertEquals(frozen.page.sourceId, intent.sourceId)
        assertEquals(frozen.page.sourceBlobId, intent.sourceBlobId)
        assertEquals(frozen.page.byteHash, intent.sourceByteSha256)
        assertEquals(3 to 2, blob.encodedWidth to blob.encodedHeight)
        assertEquals(PixelSize(2, 3), f.correctStages().first().inputSize)

        val legacy = f.result().use { OrdinaryRenderExecution.capture(f.admitted.proof, it) {} }
        try {
            f.completeCaptured(intent, legacy)
            throw AssertionError("The producer file join published a legacy capture with the wrong decode size")
        } catch (failure: RepositoryFailure) {
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
            assertFalse(failure.error.retryable)
            assertEquals(f.lease.jobId.value, failure.error.affectedId)
        }
        f.assertUnstarted(intent, row, payload, running)

        // Only the first input extent changes in the positive control. It uses the normal producer capture and the same writer/readback.
        val corrected = f.correctStages().toMutableList()
        corrected[0] = corrected[0].copy(inputSize = PixelSize(3, 2))
        f.result(stages = corrected).use { assertEquals(intent.renderId, f.complete(intent, it)) }
        assertEquals("READY", f.records.single().state)
    }

    @Test fun producerIntentDoesNotReinterpretAnExistingConsumerBoundAttempt() = runTest {
        val f = producerAttempt()
        val old = f.intents.prepare(f.admitted.proof, f.lease)
        val row = checkNotNull(f.journal)
        val payload = row.payload.copyOf()
        val running = f.frozen.stored()
        assertEquals(f.frozen.page.sourceId, old.sourceId)
        assertEquals(SourceId(f.source.sourceId), old.jobPinSourceId)
        f.refusesProducer("render_intent_binding_invalid", "Producer preparation adopted a historical consumer-bound intent") {
            f.prepare()
        }
        f.assertUnstarted(old, row, payload, running)
    }

    @Test fun producerRejectsSameIdBlobDriftOnPhaseWriteReadback() = runTest {
        for ((attempted, durable) in listOf("INTENT" to null, "COPYING" to "INTENT", "SEALED" to "COPYING",
            "MOVED" to "SEALED", "COMMITTED" to "MOVED", "SUCCEEDED" to "MOVED")) {
            val f = producerAttempt()
            val running = f.frozen.stored()
            val intent = if (attempted == "INTENT") null else f.prepare()
            var drifted = false
            f.afterMetadataWrite = { state ->
                if (state == attempted) {
                    // Change only an immutable field not carried by v1; the IDs, hashes, normalized dimensions and both counters stay valid.
                    f.driftBlobLength()
                    drifted = true
                }
            }
            f.refusesProducer("render_intent_binding_invalid", "Same-ID Blob drift survived $attempted") {
                if (intent == null) f.prepare()
                else f.result().use { f.complete(intent, it) }
            }
            assertTrue("The $attempted write must precede refusal", drifted)
            assertEquals(attempted, durable, f.journal?.state)
            assertTrue("$attempted published READY", f.records.isEmpty())
            assertEquals(if (attempted in setOf("INTENT", "COPYING")) 0 else 1, f.writes)
            f.frozen.assertStored(running)
            if (intent != null && attempted == "COPYING") f.assertNoFiles(intent)
        }
    }

    @Test fun producerExpiryAfterSuccessCasRollsBackReadyAndPreservesMovedBytes() = runTest {
        val f = producerAttempt()
        val intent = f.prepare()
        val running = f.frozen.stored()
        var moved: FileTransactionRow? = null
        f.afterMetadataWrite = { state ->
            when (state) {
                "MOVED" -> moved = f.journal
                "SUCCEEDED" -> {
                    assertEquals("COMMITTED", f.journal?.state)
                    assertEquals("READY", f.records.single().state)
                    f.leaseNow = checkNotNull(running.job.leaseUntil)
                }
            }
        }
        f.result().use { result ->
            val failure = try {
                f.complete(intent, result)
                throw AssertionError("Producer published after expiry at final readback")
            } catch (failure: RepositoryFailure) {
                failure
            }
            assertEquals(OperationError(OperationErrorCode.REVISION_CONFLICT, false, "job_lease_lost", f.lease.jobId.value), failure.error)
        }
        assertEquals(running.job.leaseUntil, f.leaseNow)
        assertSame(checkNotNull(moved), f.journal)
        assertTrue(f.records.isEmpty())
        f.frozen.assertStored(running)
        assertArrayEquals(encoded, f.file(intent.finalPath).readBytes())
        assertEquals(f.journal?.sealSha256, hash(f.seal(intent).readBytes()))
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

    private suspend fun producerAttempt(): ProducerAttempt {
        val f = AdmissionFixture(traceSourceProjection = false).apply { freeze() }
        val originalJobInput = f.job.input.copyOf()
        val (consumer, consumerBlob) = f.bindingFor(f.pinIds().single())
        f.retainEquivalentOriginal()
        f.attachEquivalentPin(replace = true)
        val (alternate, alternateBlob) = f.bindingFor(f.pinIds().single())
        // Seed these independent source-byte metadata values before admission; the EXIF-6 original is encoded 2×3 and normalizes to A's 3×2.
        val source = alternate.copy(exifOrientation = 6)
        val blob = alternateBlob.copy(encodedWidth = 2, encodedHeight = 3, byteSha256 = hash(producerOriginal),
            byteCount = producerOriginal.size.toLong())
        f.catalog.sources[source.sourceId] = source
        f.catalog.blobs[blob.sourceBlobId] = blob
        assertEquals(f.page.sourceId.value, consumer.sourceId)
        assertEquals(3 to 2, consumerBlob.encodedWidth to consumerBlob.encodedHeight)
        assertNotEquals(consumer.sourceId, source.sourceId)
        assertNotEquals(consumerBlob.sourceBlobId, blob.sourceBlobId)
        assertNotEquals(consumerBlob.byteSha256, blob.byteSha256)
        assertEquals(consumer.pixelSha256, source.pixelSha256)
        assertEquals(consumer.conversionFingerprint, source.conversionFingerprint)
        assertEquals(listOf(source.sourceId), f.pinIds())
        val admitted = checkNotNull(f.admit())
        assertSame(f.page, admitted.proof.page)
        assertEquals(source, admitted.proof.pinnedBinding.source)
        assertEquals(blob, admitted.proof.pinnedBinding.blob)
        assertEquals(listOf(1), f.casResults)
        assertArrayEquals(originalJobInput, f.job.input)
        return ProducerAttempt(f, admitted, source, blob).apply { acquire() }
    }

    // Only the row ports are doubled. Reuse the admitted freeze/CAS fixture, live lease code, real private-file protocol, and the existing completion raster/readback control.
    private class ProducerAttempt(
        val frozen: AdmissionFixture,
        val admitted: RoomFrozenRenderAdmission.Admitted,
        val source: SourceRow,
        val blob: SourceBlobRow,
    ) : JobLeaseQueries, RenderIntentQueries, RenderCompletionQueries {
        var journal: FileTransactionRow? = null
            private set
        val records = mutableListOf<RenderedPageRow>()
        val phaseWrites = mutableListOf<String>()
        var writes = 0
            private set
        var readbacks = 0
            private set
        var afterMetadataWrite: (String) -> Unit = {}
        var leaseNow = 100L
        private lateinit var acquired: FrozenRenderAcquisition.Acquired
        val lease: JobLease get() = acquired.lease
        private val root = Files.createTempDirectory(File("build").toPath(), "render-producer-test-").toFile().canonicalFile
        private val live = LeaseOwnerLiveness { if (it == producerOwner) LeaseOwnerState.ALIVE else LeaseOwnerState.UNKNOWN }
        private val catalog = frozen.catalog
        private val metadata = object : MetadataAccess {
            override val queries = catalog
            override fun <T> transaction(action: () -> T): T {
                val beforeJob = frozen.job
                val beforeJournal = journal
                val beforeRecords = records.toList()
                return try {
                    catalog.transaction(action)
                } catch (failure: Throwable) {
                    frozen.job = beforeJob
                    journal = beforeJournal
                    records.clear()
                    records.addAll(beforeRecords)
                    throw failure
                }
            }
        }
        private var auditTime = 3000L
        private val audit = { auditTime++ }
        private val ids = listOf("50000000-0000-0000-0000-000000000000", "60000000-0000-0000-0000-000000000000").iterator()
        val intents = RoomRenderIntents(metadata, this, this, ::jobBounds, ::pins, live, audit, { leaseNow }, ids::next, Dispatchers.Unconfined)
        private val files = DerivedFiles(root, DirectorySync { check(!catalog.inTransaction); true })
        private val executor = RoomRenderFileExecution(intents, files, Dispatchers.Unconfined)
        private val completion = RoomRenderCompletion(executor, this, Dispatchers.Unconfined)
        private val acquisition = FrozenRenderAcquisition(metadata, this, live, ::jobBounds, ::identity,
            ::pins, catalog::sourceReadIsBounded, ::renderJobLeaseReadIsBounded, audit, { leaseNow }, Dispatchers.Unconfined)

        suspend fun acquireReceipt(receipt: RoomFrozenRenderAdmission.Admitted): FrozenRenderAcquisition.ProducerResult =
            acquisition.acquireForProducer(receipt, producerOwner, 50L)

        suspend fun acquire() {
            val result = acquireReceipt(admitted)
            check(result is FrozenRenderAcquisition.Acquired) { "Expected live lease for the actual receipt, got $result" }
            assertSame(admitted, result.admitted)
            acquired = result
        }

        suspend fun prepare(): RenderFileIntent = intents.prepareForProducer(acquired)

        suspend fun complete(intent: RenderFileIntent, result: RenderResult): RenderId =
            completion.completeForProducer(acquired, intent, result, encoded.size.toLong(), ::writePayload, ::readPayload)

        suspend fun completeCaptured(intent: RenderFileIntent, execution: OrdinaryRenderExecution): RenderId =
            executor.completionAttempt(this).complete(acquired, intent, execution, encoded.size.toLong(), ::writePayload, ::readPayload)

        private fun writePayload(output: OutputStream) {
            check(!catalog.inTransaction && journal?.state == "COPYING")
            writes++
            output.write(encoded)
        }

        private fun readPayload(file: File, checkpoint: () -> Unit): String? {
            check(!catalog.inTransaction)
            readbacks++
            return readback(file, checkpoint)
        }

        fun request(): RenderRequest = frozen.page.let { page ->
            RenderRequest(source.sourceId, page.pixelHash, page.pageId.value, page.recipeRevision,
                RecipeCodec.decode(page.canonicalRecipe), page.output, 0, null, FallbackPolicy.FAIL)
        }

        fun driftBlobLength() {
            check(catalog.inTransaction)
            catalog.blobs[blob.sourceBlobId] = blob.copy(byteCount = blob.byteCount + 1)
            check(catalog.sourceReadIsBounded(source.sourceId) == true)
        }

        fun correctStages() = stages(frozen.page.sourceSize, frozen.page.output.size)
        fun result(
            request: RenderRequest = request(), stages: List<StageRecord> = correctStages(), ownership: AutoCloseable? = null,
        ) = RenderResult(request, RenderStatus.OK, raster(), stages, listOf("W3", "W1"), ownership)

        suspend fun assertRefusesResult(intent: RenderFileIntent, request: RenderRequest, stages: List<StageRecord>, witness: String) {
            val row = checkNotNull(journal)
            val payload = row.payload.copyOf()
            val running = frozen.stored()
            var closes = 0
            result(request, stages, AutoCloseable { closes++ }).use { result ->
                refusesProducer("render_execution_invalid", witness) { complete(intent, result) }
                assertEquals(0, closes)
                assertEquals(18, result.image!!.getUnsigned(2, 1, 2))
            }
            assertEquals(1, closes)
            assertUnstarted(intent, row, payload, running)
        }

        suspend fun refusesProducer(key: String, witness: String, action: suspend () -> Unit) {
            val failure = try {
                action()
                throw AssertionError(witness)
            } catch (failure: RepositoryFailure) {
                failure
            }
            assertEquals(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, key, lease.jobId.value), failure.error)
        }

        fun file(path: String) = File(root, path)
        fun seal(intent: RenderFileIntent) = File(file(intent.stagingPath).parentFile, "derived-seal.json")
        fun assertNoFiles(intent: RenderFileIntent) {
            assertFalse(file(intent.stagingPath).exists())
            assertFalse(seal(intent).exists())
            assertFalse(file(intent.finalPath).exists())
        }

        fun assertUnstarted(intent: RenderFileIntent, row: FileTransactionRow, payload: ByteArray, running: RoomFrozenRenderAdmissionTest.Stored) {
            assertEquals("INTENT", row.state)
            val current = checkNotNull(journal)
            assertEquals(row.copy(payload = current.payload), current)
            assertArrayEquals(payload, current.payload)
            frozen.assertStored(running)
            assertTrue(phaseWrites.isEmpty())
            assertTrue(records.isEmpty())
            assertEquals(0, writes)
            assertEquals(0, readbacks)
            assertNoFiles(intent)
        }

        // Build the fixed v1 journal vector from the fixture's independently known IDs/rows and live lease, not from a copied or self-hashed intent.
        fun expectedJournalPayload(intent: RenderFileIntent): ByteArray {
            val page = frozen.page
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { wire ->
                wire.writeBytes("pristinepage-ordinary-render-intent-v1\u0000")
                wire.writeBytes(intent.transactionId.value + intent.renderId.value + frozen.job.jobId + lease.owner)
                wire.writeLong(lease.generation)
                wire.writeLong(lease.generation)
                wire.writeBytes(frozen.job.canonicalInputSha256 + page.pageId.value + source.sourceId + blob.sourceBlobId +
                    source.sourceId + hash(producerOriginal) + page.pixelHash)
                wire.writeLong(page.recipeRevision)
                wire.writeBytes(page.recipeHash)
                wire.writeInt(page.output.size.width)
                wire.writeInt(page.output.size.height)
            }
            return bytes.toByteArray()
        }

        suspend fun loadTerminal(): RoomTerminalRenderReads.Metadata {
            return RoomTerminalRenderReads(metadata, profile, Dispatchers.Unconfined, ::jobBounds, ::identity, ::pins,
                catalog::sourceReadIsBounded,
                { id -> sql { job(id)?.takeIf { it.state == "SUCCEEDED" && it.leaseOwner == null && it.leaseUntil == null &&
                    it.attempt == it.leaseGeneration && it.errorCode == null }?.attempt } },
                { id -> sql { records.filter { it.jobId == id }.take(2).map { it.renderId } } },
                { id, owner, page, revision, width, height, cap -> sql {
                    records.singleOrNull { it.renderId == id }?.let { it.jobId == owner && it.pageId == page &&
                        it.recipeRevision == revision && it.state == "READY" && it.renderStatus == "OK" &&
                        it.width == width && it.height == height && it.execution.size in 1..cap }
                } },
                { id -> sql { records.singleOrNull { it.renderId == id } } },
                ::renderIntentReadIsBounded, ::renderIntentRow,
            ).load(frozen.jobId, frozen.page)
        }

        private fun <T> sql(action: () -> T): T {
            check(catalog.inTransaction)
            return action()
        }

        private fun jobBounds(jobId: String, extent: Int) = job(jobId)?.let { it.input.size == extent }
        private fun identity(jobId: String) = job(jobId)?.let {
            FrozenRenderJobIdentityRow(it.jobId, it.kind, it.idempotenceKey, it.canonicalInputSha256, it.input)
        }
        private fun pins(id: String) = sql { if (id == frozen.jobId.value) frozen.pinIds() else emptyList() }
        override fun job(id: String) = sql { frozen.job.takeIf { it.jobId == id } }
        override fun updateLease(row: JobRow, expectedGeneration: Long) = sql {
            if (job(row.jobId)?.leaseGeneration != expectedGeneration) 0 else {
                frozen.job = row
                afterMetadataWrite(row.state)
                1
            }
        }
        override fun renderJobLeaseReadIsBounded(jobId: String) = job(jobId)?.let { true }
        override fun renderIntentReadIsBounded(jobId: String, attempt: Long, expectedPayloadBytes: Int) =
            renderIntentRow(jobId, attempt)?.let { it.payload.size == expectedPayloadBytes }
        override fun renderIntentHasMalformedAttempts(jobId: String) = sql { false }
        override fun renderIntentRow(jobId: String, attempt: Long) = sql {
            journal?.takeIf { it.kind == "RENDER" && it.ownerId == jobId && it.attempt == attempt }
        }
        override fun insertRenderIntent(row: FileTransactionRow) = sql {
            check(journal == null)
            journal = row
            afterMetadataWrite(row.state)
        }
        override fun markRenderIntentCopying(transactionId: String, jobId: String, attempt: Long, updatedAt: Long) = sql {
            val old = renderIntentRow(jobId, attempt)
            if (old == null || old.transactionId != transactionId || old.state != "INTENT") 0
            else advanceRenderFilePhase(old, old.copy(state = "COPYING", updatedAt = updatedAt))
        }
        override fun advanceRenderFilePhase(previous: FileTransactionRow, next: FileTransactionRow) = sql {
            val old = renderIntentRow(previous.ownerId, previous.attempt)
            if (old == null || !old.payload.contentEquals(previous.payload) || old.copy(payload = previous.payload) != previous) 0
            else {
                phaseWrites += next.state
                journal = next
                afterMetadataWrite(next.state)
                1
            }
        }
        override fun renderCompletionOccupied(renderId: String, jobId: String, cacheKey: String, relativePath: String) = sql {
            records.any { it.renderId == renderId || it.jobId == jobId || it.cacheKey == cacheKey || it.relativePath == relativePath }
        }
        override fun insertReadyRender(row: RenderedPageRow) = sql {
            check(!renderCompletionOccupied(row.renderId, row.jobId, row.cacheKey, row.relativePath))
            records.add(row.copy(execution = row.execution.copyOf()))
            Unit
        }
        override fun readyRenderMatches(row: RenderedPageRow) = sql {
            records.singleOrNull()?.let { it.execution.contentEquals(row.execution) && it.copy(execution = row.execution) == row } == true
        }
        override fun commitMovedRender(previous: FileTransactionRow, updatedAt: Long): Int {
            check(previous.state == "MOVED")
            return advanceRenderFilePhase(previous, previous.copy(state = "COMMITTED", updatedAt = updatedAt))
        }
    }

    private companion object {
        private fun stages(source: PixelSize, output: PixelSize) = listOf(
            StageRecord("source_decode_v1", 1, null, "OK", PixelSize(2, 3), source, listOf("SOURCE_PIXELS_VERIFIED")),
            StageRecord("geometry_none_v1", 1, null, "EXACT_PERMUTATION", source, output, emptyList()),
            StageRecord("none_v1", 1, null, "EXACT_IDENTITY", output, output, listOf("W2", "W1")),
        )

        val profile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")
        const val producerOwner = "40000000-0000-0000-0000-000000000000"
        val producerOriginal = byteArrayOf(23, 1, 4, 9, 8, 7) // Source-byte identity only; not decoded or represented as a real PNG.

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
