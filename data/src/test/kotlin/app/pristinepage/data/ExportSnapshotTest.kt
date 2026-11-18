package app.pristinepage.data

import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.*
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Assert.*
import org.junit.Test

/** Real freeze business logic; only unavailable Room and external file/capability checks are projected. A native transaction/file-proof gate must follow before freeze acceptance. No rendering or source file is fabricated by these metadata tests. */
class ExportSnapshotTest {
    private class Harness(count: Int = 2, private val backend: String = "reference") {
        val f = RepositoryFixture(count)
        val snapshots = linkedMapOf<String, ExportSnapshotRow>()
        val pages = linkedMapOf<Pair<String, Int>, ExportPageRow>()
        val jobs = linkedMapOf<String, JobRow>()
        var onProof: (() -> Unit)? = null
        var onAdmission: (() -> OperationError?)? = null
        var fileProof: ExportSourceProof? = null
        var refusedGuard: String? = null
        var refusedRecipe: Pair<String, Long>? = null
        val falseGuards = mutableListOf<String>()
        val falseRecipeKeys = mutableListOf<Pair<String, Long>>()
        val sourceGuardResults = mutableListOf<Pair<String, Boolean?>>()
        val missingBlobLinkReads = mutableListOf<String>()
        val recipeGuardResults = mutableListOf<Pair<Pair<String, Long>, Boolean?>>()
        val typedReads = mutableListOf<String>()
        val recipeReads = mutableListOf<Pair<String, Long>>()
        val extentReads = mutableListOf<Pair<String, Long>>()
        val writes = mutableListOf<String>()
        var failPageInsertAt: Int? = null
        var snapshotBounded = true
        var pagesBounded = true
        private var pageInserts = 0
        var settings = ExportSettings(ExportFormat.PNG, f.pageIds.map { PageOutputSettings(it, OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)) }, null, null, emptyList())
        private val catalog = object : CatalogQueries by f.metadata, EditablePageQueries by f.metadata {
            private fun guard(kind: String, real: Boolean?): Boolean? {
                check(f.metadata.inTransaction)
                return if (kind == refusedGuard && real == true) false.also { falseGuards += kind } else real
            }
            override fun documentReadIsBounded(documentId: String) = guard("document", f.metadata.documentReadIsBounded(documentId))
            override fun activePagesReadIsBounded(documentId: String) = guard("pages", f.metadata.activePagesReadIsBounded(documentId)) == true
            override fun sourceReadIsBounded(id: String) =
                guard("source/blob", f.metadata.sourceReadIsBounded(id)).also { sourceGuardResults += id to it }
            override fun sourceBlobLinkIsMissing(id: String) =
                f.metadata.sourceBlobLinkIsMissing(id).also { missingBlobLinkReads += id }
            override fun recipeReadIsBounded(pageId: String, revision: Long): Boolean? {
                val real = f.metadata.recipeReadIsBounded(pageId, revision)
                val key = pageId to revision
                // Model the raw INTEGER alias while retaining the same valid typed row. An earlier refusal cannot credit this exact false branch.
                val result = if (key == refusedRecipe && real == true) false.also { falseRecipeKeys += key } else real
                recipeGuardResults += key to result
                return result
            }
            override fun recipeBlobByteCount(pageId: String, revision: Long) =
                f.metadata.recipeBlobByteCount(pageId, revision).also { extentReads += pageId to revision }
            override fun document(id: String) = f.metadata.document(id).also { typedReads += "document" }
            override fun activePages(documentId: String) = f.metadata.activePages(documentId).also { typedReads += "pages" }
            override fun source(id: String) = f.metadata.source(id).also { typedReads += "source" }
            override fun sourceBlob(id: String) = f.metadata.sourceBlob(id).also { typedReads += "blob" }
            override fun recipe(pageId: String, revision: Long) = f.metadata.recipe(pageId, revision).also { recipeReads += pageId to revision }
            override fun recipeMasks(pageId: String, revision: Long) = f.metadata.recipeMasks(pageId, revision).also { typedReads += "masks" }
            override fun insertReference(row: SourceReferenceRow) = f.metadata.insertReference(row).also { writes += "pin" }
            override fun setSourceRefCount(sourceId: String, count: Long) = f.metadata.setSourceRefCount(sourceId, count).also { writes += "source-count" }
        }
        private val metadata = object : MetadataAccess {
            override val queries: CatalogQueries get() = catalog
            override fun <T> transaction(action: () -> T): T = f.metadata.transaction {
                val s = snapshots.toMap(); val p = pages.toMap(); val j = jobs.toMap()
                try { action() } catch (failure: Throwable) {
                    snapshots.clear(); snapshots.putAll(s); pages.clear(); pages.putAll(p); jobs.clear(); jobs.putAll(j)
                    throw failure
                }
            }
        }
        private fun type(name: String): Class<*> = try { Class.forName("app.pristinepage.data.repository.$name") }
        catch (_: ClassNotFoundException) { throw AssertionError("Atomic immutable export freeze is absent: $name") }
        private fun service(): Any {
            val implementation = type("RoomExportSnapshots")
            val queries = Proxy.newProxyInstance(type("ExportSnapshotQueries").classLoader, arrayOf(type("ExportSnapshotQueries"))) { _, method, args ->
                check(f.metadata.inTransaction) { "Snapshot/job query must use the original metadata transaction" }
                if (method.name.startsWith("insert")) writes += method.name
                when (method.name) {
                    "snapshotMetadataIsBounded" -> if (args[0] in snapshots) snapshotBounded else null
                    "exportPagesAreBounded" -> pagesBounded
                    "snapshot" -> { check(snapshotBounded) { "Header BLOB materialized before scalar bounds refusal" }; snapshots[args[0] as String] }
                    "exportPages" -> { check(pagesBounded) { "Page BLOBs materialized before scalar bounds refusal" }; pages.values.filter { it.snapshotId == args[0] }.sortedBy { it.ordinal } }
                    "insertSnapshot" -> { val row = args[0] as ExportSnapshotRow; check(snapshots.putIfAbsent(row.snapshotId, row) == null); Unit }
                    "insertExportPage" -> {
                        if (++pageInserts == failPageInsertAt) throw IOException("content://private-provider/snapshot-write-fault")
                        val row = args[0] as ExportPageRow; check(pages.putIfAbsent(row.snapshotId to row.ordinal, row) == null); Unit
                    }
                    "renderJobByKey" -> jobs.values.singleOrNull { it.idempotenceKey == args[0] }
                    "jobSourceReferences" -> f.metadata.references.values.filter { it.ownerType == "JOB" && it.ownerId == args[0] }
                    "insertExportJob" -> { val row = args[0] as JobRow; check(jobs.values.none { it.idempotenceKey == row.idempotenceKey }); check(jobs.putIfAbsent(row.jobId, row) == null); Unit }
                    else -> error("Unexpected snapshot query ${method.name}")
                }
            }
            val proof = ExportSourceProof { binding ->
                assertFalse("Source proof must run outside the transaction", f.metadata.inTransaction)
                fileProof?.verify(binding)
                onProof?.invoke()
            }
            val admission = ExportAdmission {
                assertFalse("Admission must run outside the transaction", f.metadata.inTransaction)
                onAdmission?.invoke()
            }
            return implementation.constructors.single { it.parameterCount == 10 }.newInstance(metadata, queries, proof, admission, "renderer-v1", "rgb8-v1", backend, "binary64", { 1000L }, Dispatchers.Unconfined)
        }
        suspend fun freeze(expected: Long = 1): Any {
            val service = service(); val method = service.javaClass.methods.single { it.name.startsWith("freezeExport") }
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try { method.invoke(service, f.document.value, expected, settings, continuation) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
        suspend fun load(id: String = snapshots.keys.single()): ExportSnapshot {
            val service = service(); val method = service.javaClass.methods.singleOrNull { it.name.startsWith("load") }
                ?: throw AssertionError("Committed immutable snapshot retrieval is absent")
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try { method.invoke(service, id, continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
        fun nonSnapshotState(): List<Any> = with(f.metadata) { listOf(documents.toMap(), pages.toMap(), sources.toMap(), blobs.toMap(), recipes.toMap(), references.toMap(), undos.toMap(), masks.toMap(), associations.toMap(), maskReferences.toMap()) }
        suspend fun canonical(configured: Boolean, document: DocumentId = f.document): ExportSnapshot {
            val repository = if (configured) {
                val constructor = RoomDocumentRepository::class.java.constructors.singleOrNull { it.parameterCount == 6 }
                    ?: throw AssertionError("Canonical export snapshot injection is absent")
                constructor.newInstance(metadata, RecipeAdmission { _, _ -> null }, { 1000L }, Dispatchers.Unconfined, null, service()) as DocumentRepository
            } else f.repository
            val method = DocumentRepository::class.java.methods.singleOrNull { it.name.startsWith("freezeExport") }
                ?: throw AssertionError("Canonical export freeze is absent")
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try { method.invoke(repository, document.value, 1L, settings, continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
        suspend fun freezeError(document: DocumentId = f.document): OperationError? =
            try { canonical(true, document); null } catch (failure: RepositoryFailure) { failure.error }

        fun assertNoWrites(before: List<Any>) {
            assertEquals("No attempted write (not just no surviving write)", emptyList<String>(), writes)
            assertTrue(snapshots.isEmpty()); assertTrue(pages.isEmpty()); assertTrue(jobs.isEmpty())
            assertEquals(before, nonSnapshotState())
        }
    }
    private fun get(target: Any, name: String) = target.javaClass.methods.single { it.name.startsWith(name) && it.parameterCount == 0 }.invoke(target)

    @Test fun atomicFreezeCreatesCompleteOrderedRowsSourcePinsAndPendingJobIntents() = runTest {
        val h = Harness(); val frozen = h.freeze(); val row = h.snapshots.values.single()
        assertEquals(1L, get(frozen, "getDocumentRevision")); assertEquals(row.snapshotSha256, get(frozen, "getSnapshotHash"))
        assertEquals(2, h.pages.size); assertEquals(h.f.pageIds.map { it.value }, h.pages.values.sortedBy { it.ordinal }.map { it.originalPageId })
        assertEquals("FROZEN", row.state); assertEquals(3, h.jobs.size)
        assertEquals(2, h.jobs.values.count { it.kind == "PAGE_RENDER" }); assertEquals(1, h.jobs.values.count { it.kind == "EXPORT_ASSEMBLY" })
        assertTrue(h.jobs.values.all { it.state == "PENDING" && it.leaseOwner == null && it.leaseUntil == null && it.leaseGeneration == 0L })
        assertEquals(2, h.f.metadata.references.values.count { it.ownerType == "EXPORT_PAGE" }); assertEquals(2, h.f.metadata.references.values.count { it.ownerType == "JOB" })
        assertEquals(8L, h.f.metadata.sources.getValue(h.f.source.value).refCount); assertEquals(1L, h.f.metadata.documents.getValue(h.f.document.value).documentRevision)
    }

    @Test fun exportUsesFrozenOrderAndRecipesAfterLaterRotationAndReorder() = runTest {
        val h = Harness(); h.freeze(); val saved = h.pages.values.sortedBy { it.ordinal }.map { it.copy(recipe = it.recipe.copyOf()) }
        assertTrue(h.f.repository.rotatePage(h.f.pageIds[0], 1, 1) is DocumentMutationResult.Applied)
        assertTrue(h.f.repository.reorderPages(h.f.document, 2, h.f.pageIds.reversed()) is DocumentMutationResult.Applied)
        assertEquals(h.f.pageIds.map { it.value }, saved.map { it.originalPageId })
        for ((before, after) in saved.zip(h.pages.values.sortedBy { it.ordinal })) {
            assertArrayEquals(before.recipe, after.recipe); assertEquals(before.recipeSha256, after.recipeSha256); assertEquals(before.ordinal, after.ordinal)
            assertEquals(0, RecipeCodec.decode(after.recipe).geometry.outputQuarterTurns)
        }
        assertEquals(3L, h.f.metadata.documents.getValue(h.f.document.value).documentRevision)
    }

    @Test fun changedDocumentRevisionDuringAdmissionCannotPublishStaleSnapshot() = runTest {
        val h = Harness(); h.onAdmission = { val row = h.f.metadata.documents.getValue(h.f.document.value); h.f.metadata.documents[row.documentId] = row.copy(documentRevision = 2); null }
        val failure = try { h.freeze(); throw AssertionError("Stale reviewed revision froze") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.REVISION_CONFLICT, failure.code); assertTrue(h.snapshots.isEmpty()); assertTrue(h.pages.isEmpty()); assertTrue(h.jobs.isEmpty())
        assertEquals(4L, h.f.metadata.countSourceReferences(h.f.source.value))
    }

    @Test fun lateSnapshotPageWriteFailureRollsBackAllRowsPinsJobsAndCounters() = runTest {
        val h = Harness(); val before = h.nonSnapshotState(); h.failPageInsertAt = 2
        val failure = try { h.freeze(); throw AssertionError("Late failing freeze returned success") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.STORAGE_FAILED, failure.code); assertTrue(failure.retryable)
        assertTrue(h.snapshots.isEmpty()); assertTrue(h.pages.isEmpty()); assertTrue(h.jobs.isEmpty()); assertEquals(before, h.nonSnapshotState())
    }

    @Test fun failedSourceProofCommitsNoSnapshotOrOwnershipAndDoesNotInvokeAdmission() = runTest {
        val h = Harness(); val before = h.nonSnapshotState(); val error = OperationError(OperationErrorCode.SOURCE_UNAVAILABLE, false, "source_unavailable", h.f.source.value)
        h.onProof = { throw RepositoryFailure(error) }; h.onAdmission = { throw AssertionError("Admission cannot run after failed source proof") }
        val failure = try { h.freeze(); throw AssertionError("Unverified source froze") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(error, failure); assertTrue(h.snapshots.isEmpty()); assertTrue(h.jobs.isEmpty()); assertEquals(before, h.nonSnapshotState())
    }

    @Test fun unboundedRecipeRefusesBeforeTypedReadOrSourceProof() = runTest {
        val h = Harness()
        val key = h.f.pageIds.first().value to 1L
        h.refusedRecipe = key
        val before = h.nonSnapshotState()
        var proofs = 0
        var admissions = 0
        h.onProof = { proofs++ }
        h.onAdmission = { admissions++; null }

        val error = h.freezeError()

        assertEquals("Typed recipe read despite a failed raw bound", emptyList<Pair<String, Long>>(), h.recipeReads)
        assertEquals("The selected raw guard must actually return false", listOf(key), h.falseRecipeKeys)
        assertEquals(0, proofs); assertEquals(0, admissions)
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error?.code)
        assertFalse(checkNotNull(error).retryable)
        h.assertNoWrites(before)
    }

    @Test fun malformedPresentDocumentMembershipAndSourceChainStopTheirTypedReads() = runTest {
        for ((guard, forbidden) in listOf("document" to setOf("document"), "pages" to setOf("pages"), "source/blob" to setOf("source", "blob"))) {
            val h = Harness()
            h.refusedGuard = guard
            val before = h.nonSnapshotState()
            h.onProof = { throw AssertionError("$guard refusal reached proof") }
            h.onAdmission = { throw AssertionError("$guard refusal reached admission") }

            val error = h.freezeError()

            assertTrue("$guard projected ${h.typedReads}", forbidden.none { it in h.typedReads })
            assertEquals(listOf(guard), h.falseGuards)
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error?.code)
            assertFalse(checkNotNull(error).retryable)
            h.assertNoWrites(before)
        }
    }

    @Test fun missingDocumentRefusesWithoutProjectionOrProof() = runTest {
        val h = Harness()
        val before = h.nonSnapshotState()
        h.onProof = { throw AssertionError("Missing document reached proof") }
        h.onAdmission = { throw AssertionError("Missing document reached admission") }

        val error = h.freezeError(DocumentId("00000000-0000-0000-0000-000000000099"))

        assertEquals(OperationErrorCode.SOURCE_UNAVAILABLE, error?.code)
        assertFalse(checkNotNull(error).retryable)
        assertTrue("An absent document needs no typed fallback", h.typedReads.isEmpty())
        h.assertNoWrites(before)
    }

    @Test fun missingSelectedRecipePreservesHistoryMissingBeforeTypedRead() = runTest {
        val h = Harness()
        val key = h.f.pageIds.first().value to 1L
        val decoy = h.f.pageIds.last().value to 1L
        checkNotNull(h.f.metadata.recipes.remove(key))
        assertTrue(decoy in h.f.metadata.recipes)
        val before = h.nonSnapshotState()
        h.onProof = { throw AssertionError("Missing recipe reached source proof") }
        h.onAdmission = { throw AssertionError("Missing recipe reached admission") }

        val error = h.freezeError()

        assertEquals(listOf(key to null), h.recipeGuardResults)
        assertTrue(h.recipeReads.isEmpty()); assertTrue(h.extentReads.isEmpty())
        h.assertNoWrites(before)
        assertEquals(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "recipe_history_missing", key.first), error)
    }

    @Test fun malformedPresentRecipeStaysInvalidRatherThanMissingBeforeTypedRead() = runTest {
        val h = Harness()
        val key = h.f.pageIds.first().value to 1L
        val row = h.f.metadata.recipes.getValue(key)
        h.f.metadata.recipes[key] = row.copy(canonicalRecipe = byteArrayOf())
        val before = h.nonSnapshotState()
        h.onProof = { throw AssertionError("Malformed recipe reached source proof") }
        h.onAdmission = { throw AssertionError("Malformed recipe reached admission") }

        val error = h.freezeError()

        assertEquals(listOf(key to false), h.recipeGuardResults)
        assertTrue(h.recipeReads.isEmpty()); assertTrue(h.extentReads.isEmpty())
        h.assertNoWrites(before)
        assertEquals(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "export_recipe_metadata_invalid", key.first), error)
    }

    @Test fun missingSelectedSourcePreservesSourceUnavailableBeforeTypedRead() = runTest {
        val h = Harness()
        checkNotNull(h.f.metadata.sources.remove(h.f.source.value))
        h.assertRefusedSourceChain(null, "source_unavailable")
    }

    @Test fun missingExactlyLinkedBlobPreservesSourceUnavailableBeforeTypedRead() = runTest {
        val h = Harness()
        val source = h.f.metadata.sources.getValue(h.f.source.value)
        val missing = "00000000-0000-0000-0000-0000000000a1"
        check(missing !in h.f.metadata.blobs && source.sourceBlobId in h.f.metadata.blobs)
        h.f.metadata.sources[source.sourceId] = source.copy(sourceBlobId = missing)
        h.assertRefusedSourceChain(false, "source_unavailable")
    }

    @Test fun malformedPresentBlobStaysInvalidRatherThanMissingBeforeTypedRead() = runTest {
        val h = Harness()
        val blob = h.f.metadata.blobs.values.single()
        h.f.metadata.blobs[blob.sourceBlobId] = blob.copy(byteCount = 0)
        h.assertRefusedSourceChain(false, "export_source_metadata_invalid")
    }

    @Test fun noncanonicalBlobLinkStaysInvalidEvenWithoutAMatchingBlob() = runTest {
        val h = Harness()
        val source = h.f.metadata.sources.getValue(h.f.source.value)
        val malformed = "00000000-0000-0000-0000-0000000000A1"
        check(malformed !in h.f.metadata.blobs)
        h.f.metadata.sources[source.sourceId] = source.copy(sourceBlobId = malformed)
        h.assertRefusedSourceChain(false, "export_source_metadata_invalid")
    }

    private suspend fun Harness.assertRefusedSourceChain(guard: Boolean?, key: String) {
        val before = nonSnapshotState()
        onProof = { throw AssertionError("$key reached source proof") }
        onAdmission = { throw AssertionError("$key reached admission") }

        val error = freezeError()

        assertEquals(listOf(f.source.value to guard), sourceGuardResults)
        assertEquals(if (guard == false) listOf(f.source.value) else emptyList(), missingBlobLinkReads)
        assertFalse("Source/Blob projected before refusal: $typedReads", typedReads.any { it == "source" || it == "blob" })
        assertTrue(recipeReads.isEmpty())
        assertNoWrites(before)
        assertEquals(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, key, f.source.value), error)
    }

    @Test fun validSourceAndRecipeGuardsReachAnIndependentNoWriteAdmissionVeto() = runTest {
        val h = Harness()
        val before = h.nonSnapshotState()
        val keys = h.f.pageIds.map { it.value to 1L }
        val veto = OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "fixture_valid_chain_veto", h.f.document.value)
        var proofs = 0
        var admissions = 0
        h.onProof = { proofs++ }
        h.onAdmission = { admissions++; veto }

        val error = h.freezeError()

        assertEquals(setOf(h.f.source.value to true), h.sourceGuardResults.toSet())
        assertTrue(h.missingBlobLinkReads.isEmpty())
        assertEquals(keys.map { it to true }, h.recipeGuardResults)
        assertEquals(keys, h.recipeReads)
        assertEquals(1, proofs); assertEquals(1, admissions)
        h.assertNoWrites(before)
        assertEquals(veto, error)
    }

    @Test fun finalPrepareRejectsRawAliasIntroducedAfterRealFileProofAndAdmission() = runTest {
        val h = Harness()
        val keys = h.f.pageIds.map { it.value to 1L }
        val fixtureRoot = File("build/freeze-prepare-fixtures").apply { mkdirs() }
        val files = SourceFiles(Files.createTempDirectory(fixtureRoot.toPath(), "original-proof-").toFile())
        val blob = h.f.metadata.blobs.values.single()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        files.finalFile(blob.sourceBlobId).apply { checkNotNull(parentFile).mkdirs(); writeBytes(bytes) }
        h.f.metadata.blobs[blob.sourceBlobId] = blob.copy(byteSha256 = digest)
        val clean = h.nonSnapshotState()
        h.fileProof = FileExportSourceProof(files, Dispatchers.Unconfined)
        var verified = 0
        var admitted = 0
        h.onProof = { verified++ }
        h.onAdmission = {
            assertEquals(1, verified) // Both pages have the same original and only count a completed real file proof.
            assertEquals(keys, h.recipeReads)
            assertTrue(h.falseRecipeKeys.isEmpty())
            h.assertNoWrites(clean)
            admitted++
            h.refusedRecipe = keys.first()
            null
        }

        val error = h.freezeError()

        assertEquals(1, admitted); assertEquals(1, verified)
        assertEquals("Final prepare accepted or misclassified indistinguishable typed data", OperationErrorCode.INTEGRITY_FAILED, error?.code)
        assertFalse(checkNotNull(error).retryable)
        assertEquals(listOf(keys.first()), h.falseRecipeKeys)
        assertEquals("Final preparation must not re-project the selected recipe", keys, h.recipeReads)
        h.assertNoWrites(clean)
    }

    @Test fun aggregateMetadataAdmitsOneBelowAndExactlyAtLimitButNeverProjectsAboveIt() = runTest {
        // A non-default backend changes the provider descriptor extent; the fixture math does not call the production encoder or budget.
        val backend = "reference-longer-backend"
        val descriptorBytes = """{"backend":"reference-longer-backend","encodingVersion":"rgb8-v1","precision":"binary64","providers":[],"version":1}""".length
        val count = 16
        val settingsBytes = 49 + 64 * count // PNG header/trailer + UUID, dimensions and FAIL text per reviewed page.
        val recipeAllowance = 8 * 1024 * 1024 - (4096 + settingsBytes + count * (4096 + 2 + descriptorBytes))
        for (delta in -1..1) {
            val h = Harness(count, backend)
            val full = ByteArray(524288)
            val keys = h.f.pageIds.map { it.value to 1L }
            for ((i, key) in keys.withIndex()) {
                val bytes = if (i < count - 1) full else ByteArray(recipeAllowance - (count - 1) * full.size + delta)
                h.f.metadata.recipes[key] = h.f.metadata.recipes.getValue(key).copy(canonicalRecipe = bytes)
            }
            val before = h.nonSnapshotState()
            h.onProof = { throw AssertionError("Undecodable synthetic recipe reached file proof") }
            h.onAdmission = { throw AssertionError("Undecodable synthetic recipe reached admission") }

            val error = h.freezeError()

            // The in-bound fixture deliberately contains undecodable bytes, so the first typed read is a discriminator, not a claimed valid freeze.
            assertEquals("Typed recipe projection at limit $delta", if (delta > 0) emptyList() else keys.take(1), h.recipeReads)
            assertEquals("Charge exactly the selected revisions", keys, h.extentReads)
            assertEquals(if (delta > 0) OperationErrorCode.INTEGRITY_FAILED else OperationErrorCode.UNSUPPORTED_VERSION, error?.code)
            assertFalse(checkNotNull(error).retryable)
            h.assertNoWrites(before)
        }
    }

    @Test fun unsupportedCapabilityRejectsTheEntireReviewedExportWithoutSubstitution() = runTest {
        val h = Harness(); val before = h.nonSnapshotState(); val error = OperationError(OperationErrorCode.RESOURCE_LIMIT, false, "export_not_admitted", null)
        h.onAdmission = { error }
        val failure = try { h.freeze(); throw AssertionError("Unadmitted export froze") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(error, failure); assertTrue(h.snapshots.isEmpty()); assertTrue(h.jobs.isEmpty()); assertEquals(before, h.nonSnapshotState())
    }

    @Test fun sourceThatStopsBeingReadyDuringPreflightCannotGainExportPins() = runTest {
        val h = Harness(); h.onAdmission = { val source = h.f.metadata.sources.getValue(h.f.source.value); h.f.metadata.sources[source.sourceId] = source.copy(state = "QUARANTINED"); null }
        val failure = try { h.freeze(); throw AssertionError("Changed source froze") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.code); assertTrue(h.snapshots.isEmpty()); assertEquals(4L, h.f.metadata.countSourceReferences(h.f.source.value))
    }

    @Test fun unresolvedImportAndUnsupportedStoredRecipeRefuseBeforeSourceIo() = runTest {
        for (future in listOf(false, true)) {
            val h = Harness()
            if (future) { val key = h.f.pageIds[0].value to 1L; h.f.metadata.recipes[key] = h.f.metadata.recipes.getValue(key).copy(recipeSchemaVersion = 2) }
            else h.f.metadata.pendingImports += h.f.document.value
            val before = h.nonSnapshotState(); h.onProof = { throw AssertionError("Invalid metadata reached source IO") }
            val failure = try { h.freeze(); throw AssertionError("Invalid pending/future metadata froze") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(if (future) OperationErrorCode.UNSUPPORTED_VERSION else OperationErrorCode.REVISION_CONFLICT, failure.code)
            assertTrue(h.snapshots.isEmpty()); assertEquals(before, h.nonSnapshotState())
        }
    }

    @Test fun repeatedIntentionalFreezeKeepsSnapshotsDistinctButReusesExactPageJobs() = runTest {
        val h = Harness(); h.freeze(); h.freeze()
        assertEquals(2, h.snapshots.size); assertEquals(1, h.snapshots.values.map { it.snapshotSha256 }.toSet().size); assertEquals(4, h.pages.size)
        assertEquals(4, h.jobs.size); assertEquals(2, h.jobs.values.count { it.kind == "PAGE_RENDER" }); assertEquals(2, h.jobs.values.count { it.kind == "EXPORT_ASSEMBLY" })
        assertEquals(4, h.f.metadata.references.values.count { it.ownerType == "EXPORT_PAGE" }); assertEquals(2, h.f.metadata.references.values.count { it.ownerType == "JOB" })
        assertEquals(10L, h.f.metadata.sources.getValue(h.f.source.value).refCount)
    }

    @Test fun cancellationFromAdmissionCannotStartTheFreezeTransactionWrites() = runTest {
        val h = Harness(); val before = h.nonSnapshotState(); val job = Job(); h.onAdmission = { job.cancel(); null }
        var canceled = false
        try { withContext(job) { h.freeze() } } catch (_: CancellationException) { canceled = true }
        assertTrue(canceled); assertTrue(h.snapshots.isEmpty()); assertTrue(h.jobs.isEmpty()); assertEquals(before, h.nonSnapshotState())
    }

    @Test fun canonicalRepositoryPublishesTheConfiguredSnapshotInsteadOfAStub() = runTest {
        val h = Harness(); val frozen = h.canonical(true)
        assertEquals(h.f.document, frozen.documentId); assertEquals(2, frozen.pages.size); assertEquals(1, h.snapshots.size); assertEquals(3, h.jobs.size)
    }

    @Test fun unconfiguredCanonicalFreezeCannotReturnAFalseSnapshot() = runTest {
        val h = Harness(); val before = h.nonSnapshotState()
        val error = try { h.canonical(false); throw AssertionError("Unconfigured freeze succeeded") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, error.code); assertEquals("export_freeze_unavailable", error.safeMessageKey)
        assertEquals(before, h.nonSnapshotState()); assertTrue(h.snapshots.isEmpty())
    }

    @Test fun sameKeyWithDifferentBytesCannotGainAnotherConsumerOrJobPin() = runTest {
        val h = Harness(); h.freeze(); val pageJob = h.jobs.values.first { it.kind == "PAGE_RENDER" }
        h.jobs[pageJob.jobId] = pageJob.copy(input = pageJob.input + byteArrayOf(0)); val before = h.nonSnapshotState()
        val error = try { h.freeze(); throw AssertionError("Corrupt reused job was accepted") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); assertEquals(1, h.snapshots.size); assertEquals(2, h.pages.size); assertEquals(3, h.jobs.size)
        assertEquals(before, h.nonSnapshotState())
    }

    @Test fun missingReusableJobSourcePinIsNotHiddenByNewExportPagePins() = runTest {
        val h = Harness(); h.freeze(); val job = h.jobs.values.first { it.kind == "PAGE_RENDER" }
        h.f.metadata.references.entries.removeIf { it.value.ownerType == "JOB" && it.value.ownerId == job.jobId }
        val before = h.nonSnapshotState()
        val error = try { h.freeze(); throw AssertionError("Unowned reused render job was accepted") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); assertEquals(1, h.snapshots.size); assertEquals(before, h.nonSnapshotState())
    }

    @Test fun publicSnapshotListsAndBlobCopiesCannotRewriteFrozenRows() = runTest {
        val h = Harness(); val frozen = h.freeze() as ExportSnapshot; val page = frozen.pages.first(); val bytes = page.canonicalRecipe
        page.canonicalRecipe.fill(0); page.masks.fill(0); page.providers.fill(0)
        assertArrayEquals(bytes, page.canonicalRecipe); assertArrayEquals(bytes, h.pages.values.first().recipe)
        @Suppress("UNCHECKED_CAST") val exposed = frozen.pages as MutableList<ExportPageSnapshot>
        assertThrows(UnsupportedOperationException::class.java) { exposed.clear() }; assertEquals(2, frozen.pages.size)
        h.pages.values.first().recipe.fill(0); assertArrayEquals(bytes, page.canonicalRecipe)
    }

    @Test fun maskProviderAndAcceptedDegradedPathsRefuseWithoutSheddingTheirDependencies() = runTest {
        for (kind in listOf("MASK", "PROVIDER", "DEGRADED")) {
            val h = Harness(); val page = h.f.pageIds.first()
            when (kind) {
                "MASK" -> h.f.protectionMask(page, 1)
                "PROVIDER" -> {
                    val recipe = h.f.initial.copy(appearance = app.pristinepage.core.recipe.AppearanceRecipe.ProviderAppearanceV1("neural", 1, "a".repeat(64), "variant", "b".repeat(64)))
                    val key = page.value to 1L; val row = h.f.metadata.recipes.getValue(key)
                    h.f.metadata.recipes[key] = row.copy(canonicalRecipe = RecipeCodec.encode(recipe), recipeSha256 = RecipeCodec.hash(recipe))
                }
                else -> h.settings = ExportSettings(ExportFormat.PNG, h.settings.pages.mapIndexed { i, p -> if (i == 0) p.copy(fallbackPolicy = FallbackPolicy.KEEP_STAGE_INPUT) else p }, null, null, listOf(AcceptedExecution(page, 1, "a".repeat(64), RenderId(java.util.UUID.randomUUID().toString()), PixelSize(3, 2), "b".repeat(64))))
            }
            val before = h.nonSnapshotState(); h.onProof = { throw AssertionError("Unimplemented retention path reached file proof") }
            val error = try { h.freeze(); throw AssertionError("Unimplemented path silently dropped dependencies") } catch (failure: RepositoryFailure) { failure.error }
            if (kind == "MASK") assertFalse("Mask capability requires only association presence", "masks" in h.typedReads)
            assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, error.code); assertTrue(h.snapshots.isEmpty()); assertTrue(h.jobs.isEmpty()); assertEquals(before, h.nonSnapshotState())
        }
    }

    @Test fun loadedSnapshotIgnoresLaterLivePageRemovalAndRecipeChanges() = runTest {
        val h = Harness(); val frozen = h.freeze() as ExportSnapshot
        h.f.metadata.pages.clear(); h.f.metadata.recipes.clear()
        h.onProof = { throw AssertionError("Metadata lookup must not read source files") }; h.onAdmission = { throw AssertionError("Metadata lookup must not rerun admission") }
        val before = h.nonSnapshotState(); val loaded = h.load()
        assertEquals(frozen.snapshotId, loaded.snapshotId); assertEquals(frozen.snapshotHash, loaded.snapshotHash)
        assertEquals(frozen.pages.map { it.pageId }, loaded.pages.map { it.pageId }); assertEquals(1L, loaded.documentRevision)
        for (page in loaded.pages) assertEquals(0, RecipeCodec.decode(page.canonicalRecipe).geometry.outputQuarterTurns)
        assertEquals(before, h.nonSnapshotState()); assertEquals(1, h.snapshots.size); assertEquals(3, h.jobs.size)
    }

    @Test fun missingCommittedSnapshotIsAnExplicitFailureNotAnEmptyDocument() = runTest {
        val h = Harness(); val before = h.nonSnapshotState()
        val error = try { h.load(java.util.UUID.randomUUID().toString()); throw AssertionError("Absent snapshot loaded") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.SOURCE_UNAVAILABLE, error.code); assertTrue(h.snapshots.isEmpty()); assertEquals(before, h.nonSnapshotState())
    }

    @Test fun changedFrozenContentOrStoredHashCannotBeLoadedAsTheReviewedSnapshot() = runTest {
        for (fault in listOf("HASH", "RECIPE", "ROW_SNAPSHOT", "ORDINAL", "SOURCE")) {
            val h = Harness(); h.freeze(); val snapshot = h.snapshots.values.single(); val page = h.pages.values.first(); val key = page.snapshotId to page.ordinal
            when (fault) {
                "HASH" -> h.snapshots[snapshot.snapshotId] = snapshot.copy(snapshotSha256 = "f".repeat(64))
                "RECIPE" -> h.pages[key] = page.copy(recipe = byteArrayOf(0))
                "ROW_SNAPSHOT" -> h.pages[key] = page.copy(snapshotId = java.util.UUID.randomUUID().toString())
                "ORDINAL" -> h.pages[key] = page.copy(ordinal = 7)
                else -> h.pages[key] = page.copy(sourceId = java.util.UUID.randomUUID().toString())
            }
            val rows = h.pages.toMap(); val records = h.snapshots.toMap(); val before = h.nonSnapshotState()
            val error = try { h.load(); throw AssertionError("Changed frozen identity accepted") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); assertEquals(rows, h.pages); assertEquals(records, h.snapshots); assertEquals(before, h.nonSnapshotState())
        }
    }

    @Test fun unknownTerminalAndExpiredSnapshotsCannotAuthorizeConsumption() = runTest {
        for ((state, code) in listOf("FUTURE_STATE" to OperationErrorCode.UNSUPPORTED_VERSION, "CANCELED" to OperationErrorCode.CANCELED, "FAILED" to OperationErrorCode.PROCESSING_FAILED, "EXPIRED" to OperationErrorCode.SESSION_EXPIRED, "FROZEN" to OperationErrorCode.SESSION_EXPIRED)) {
            val h = Harness(); h.freeze(); val row = h.snapshots.values.single(); h.snapshots[row.snapshotId] = row.copy(state = state, retainedUntil = if (state == "FROZEN") 1000 else Long.MAX_VALUE)
            h.pagesBounded = false
            val before = h.snapshots.toMap()
            val error = try { h.load(); throw AssertionError("Unusable lifecycle loaded") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(code, error.code); assertEquals(before, h.snapshots)
        }
    }

    @Test fun unsupportedOrNoncanonicalSettingsBytesStayPreservedOnLoadFailure() = runTest {
        for (future in listOf(false, true)) {
            val h = Harness(); h.freeze(); val row = h.snapshots.values.single(); val bytes = if (future) byteArrayOf(0) else row.settings + byteArrayOf(0)
            h.snapshots[row.snapshotId] = row.copy(settings = bytes)
            val error = try { h.load(); throw AssertionError("Unknown settings bytes loaded") } catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.UNSUPPORTED_VERSION, error.code); assertArrayEquals(bytes, h.snapshots.getValue(row.snapshotId).settings)
        }
    }

    @Test fun loadedPublicValuesOwnEveryBlobAcrossRepeatedLookups() = runTest {
        val h = Harness(); h.freeze(); val first = h.load(); val bytes = first.pages.first().canonicalRecipe
        first.pages.first().canonicalRecipe.fill(0); first.pages.first().providers.fill(0); first.pages.first().masks.fill(0)
        val second = h.load(); assertArrayEquals(bytes, second.pages.first().canonicalRecipe)
        h.pages.values.first().recipe.fill(0); assertArrayEquals(bytes, first.pages.first().canonicalRecipe); assertArrayEquals(bytes, second.pages.first().canonicalRecipe)
    }

    @Test fun oversizedPersistedSnapshotHeaderRefusesBeforeBlobMaterialization() = runTest {
        val h = Harness(); h.freeze(); h.snapshotBounded = false; val before = h.snapshots.toMap()
        val error = try { h.load(); throw AssertionError("Unbounded persisted snapshot header was accepted") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); assertEquals(before, h.snapshots)
    }

    @Test fun unboundedOrLossyPageProjectionCannotReachVerifiedLoad() = runTest {
        val h = Harness(); h.freeze(); h.pagesBounded = false; val before = h.pages.toMap()
        val error = try { h.load(); throw AssertionError("Unbounded or lossy page projection was accepted") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); assertEquals(before, h.pages)
    }

    @Test fun retainedFutureFrozenRecipeIsUnsupportedRatherThanReportedCorrupt() = runTest {
        val h = Harness(); h.freeze(); val row = h.pages.values.first(); h.pages[row.snapshotId to row.ordinal] = row.copy(recipeSchemaVersion = 2); val before = h.pages.toMap()
        val error = try { h.load(); throw AssertionError("Future frozen recipe executed") } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.UNSUPPORTED_VERSION, error.code); assertEquals(before, h.pages)
    }
}
