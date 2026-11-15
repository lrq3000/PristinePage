package app.pristinepage.data

import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
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
    private class Harness {
        val f = RepositoryFixture()
        val snapshots = linkedMapOf<String, ExportSnapshotRow>()
        val pages = linkedMapOf<Pair<String, Int>, ExportPageRow>()
        val jobs = linkedMapOf<String, JobRow>()
        var onProof: (() -> Unit)? = null
        var onAdmission: (() -> OperationError?)? = null
        var failPageInsertAt: Int? = null
        private var pageInserts = 0
        var settings = ExportSettings(ExportFormat.PNG, f.pageIds.map { PageOutputSettings(it, OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)) }, null, null, emptyList())
        private val metadata = object : MetadataAccess {
            override val queries: CatalogQueries get() = f.metadata
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
                when (method.name) {
                    "snapshot" -> snapshots[args[0] as String]
                    "exportPages" -> pages.values.filter { it.snapshotId == args[0] }.sortedBy { it.ordinal }
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
            val proof = Proxy.newProxyInstance(type("ExportSourceProof").classLoader, arrayOf(type("ExportSourceProof"))) { _, _, _ ->
                check(!f.metadata.inTransaction); onProof?.invoke(); Unit
            }
            val admission = Proxy.newProxyInstance(type("ExportAdmission").classLoader, arrayOf(type("ExportAdmission"))) { _, _, _ ->
                check(!f.metadata.inTransaction); onAdmission?.invoke()
            }
            return implementation.constructors.single { it.parameterCount == 10 }.newInstance(metadata, queries, proof, admission, "renderer-v1", "rgb8-v1", "reference", "binary64", { 1000L }, Dispatchers.Unconfined)
        }
        suspend fun freeze(expected: Long = 1): Any {
            val service = service(); val method = service.javaClass.methods.single { it.name.startsWith("freezeExport") }
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try { method.invoke(service, f.document.value, expected, settings, continuation) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
        fun nonSnapshotState(): List<Any> = with(f.metadata) { listOf(documents.toMap(), pages.toMap(), sources.toMap(), blobs.toMap(), recipes.toMap(), references.toMap(), undos.toMap(), masks.toMap(), associations.toMap(), maskReferences.toMap()) }
        suspend fun canonical(configured: Boolean): ExportSnapshot {
            val repository = if (configured) {
                val constructor = RoomDocumentRepository::class.java.constructors.singleOrNull { it.parameterCount == 6 }
                    ?: throw AssertionError("Canonical export snapshot injection is absent")
                constructor.newInstance(metadata, RecipeAdmission { _, _ -> null }, { 1000L }, Dispatchers.Unconfined, null, service()) as DocumentRepository
            } else f.repository
            val method = DocumentRepository::class.java.methods.singleOrNull { it.name.startsWith("freezeExport") }
                ?: throw AssertionError("Canonical export freeze is absent")
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try { method.invoke(repository, f.document.value, 1L, settings, continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
            }
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
            assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, error.code); assertTrue(h.snapshots.isEmpty()); assertTrue(h.jobs.isEmpty()); assertEquals(before, h.nonSnapshotState())
        }
    }
}
