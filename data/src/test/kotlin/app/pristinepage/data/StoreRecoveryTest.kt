package app.pristinepage.data

import app.pristinepage.data.db.UndoEntryRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Compose the real audit/expiry/repository classes; only Room SQL and external ingest work are substituted. No mock report replaces reference/expiry behavior. Native SQL/wiring remains a separate gate. */
class StoreRecoveryTest {
    private val empty = RecoveryReport(0, 0, 0, 0, 0, emptyList())
    private fun ingest(action: suspend () -> RecoveryReport = { empty }) = object : RecoveryCoordinator {
        override suspend fun reconcile() = action()
    }
    private fun service(f: RepositoryFixture, ingest: RecoveryCoordinator = ingest(), metadata: MetadataAccess = f.metadata): RecoveryCoordinator {
        val type = try { Class.forName("app.pristinepage.data.repository.RoomStoreRecovery") }
        catch (_: ClassNotFoundException) { throw AssertionError("Composed store recovery is absent") }
        return type.constructors.single { it.parameterCount == 5 }.newInstance(metadata, ingest, RoomReferenceAudit(f.metadata, MemoryReferenceAuditQueries(f.metadata), Dispatchers.Unconfined), RoomUndoRetention(f.metadata, { 100_000_000L }, Dispatchers.Unconfined), Dispatchers.Unconfined) as RecoveryCoordinator
    }
    private fun state(f: RepositoryFixture): List<Any> = with(f.metadata) {
        listOf(documents.toMap(), pages.toMap(), sources.toMap(), blobs.toMap(), recipes.toMap(), references.toMap(), masks.toMap(), associations.toMap(), maskReferences.toMap())
    }
    private suspend fun edit(f: RepositoryFixture): String = checkNotNull((f.repository.rotatePage(f.pageIds[0], 1, 1) as DocumentMutationResult.Applied).undoId).value
    private suspend fun canonical(repository: DocumentRepository): RecoveryReport {
        val method = DocumentRepository::class.java.methods.singleOrNull { it.name == "reconcile" } ?: throw AssertionError("Canonical repository recovery is absent")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try { method.invoke(repository, continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
    private fun listing(f: RepositoryFixture, action: (String?, Int) -> List<String>): MetadataAccess {
        val projected = Proxy.newProxyInstance(CatalogQueries::class.java.classLoader, arrayOf(CatalogQueries::class.java)) { _, method, args ->
            if (method.name == "retainedDocumentIdsAfter") action(args[0] as String?, args[1] as Int)
            else try { method.invoke(f.metadata, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
        } as CatalogQueries
        return object : MetadataAccess {
            override val queries = projected
            override fun <T> transaction(action: () -> T): T = f.metadata.transaction(action)
        }
    }
    private fun retainedDocuments(f: RepositoryFixture, count: Int): List<String> = (0 until count).map { index ->
        val id = UUID.randomUUID().toString()
        f.metadata.documents[id] = f.metadata.documents.getValue(f.document.value).copy(documentId = id, lifecycle = listOf("ACTIVE", "TOMBSTONED", "PURGING")[index % 3])
        val undo = UUID.randomUUID().toString()
        f.metadata.undos[undo] = UndoEntryRow(undo, id, byteArrayOf(1), byteArrayOf(2), "PAGE_REORDER", 1, 10L, "AVAILABLE")
        id
    }

    @Test fun composedRecoveryExpiresActualUndoAndIncludesRealAuditObservations() = runTest {
        val f = RepositoryFixture(); val undo = edit(f); val before = state(f)
        val issue = RecoveryIssue(UUID.randomUUID().toString(), "INTENT", null, RecoveryAction.RETRY)
        val report = service(f, ingest { check(!f.metadata.inTransaction); RecoveryReport(3, 1, 1, 0, 2, listOf(issue)) }).reconcile()
        assertEquals(RecoveryReport(11, 2, 1, 0, 2, listOf(issue)), report)
        assertEquals("EXPIRED", f.metadata.undos.getValue(undo).state); assertEquals(before, state(f))
    }

    @Test fun everyRetainedDocumentIncludingTombstonesExpiresBeyondOneSqlPage() = runTest {
        val f = RepositoryFixture(); retainedDocuments(f, 70); val before = state(f)
        assertEquals(RecoveryReport(77, 70, 0, 0, 0, emptyList()), service(f).reconcile())
        assertTrue(f.metadata.undos.values.all { it.state == "EXPIRED" }); assertEquals(before, state(f))
    }

    @Test fun combinedIssuesAreCappedButBlockedCountIncludesLaterAudits() = runTest {
        val f = RepositoryFixture(); val source = f.metadata.sources.getValue(f.source.value)
        f.metadata.sources[source.sourceId] = source.copy(refCount = -1)
        val blob = f.metadata.blobs.getValue(source.sourceBlobId); f.metadata.blobs[blob.sourceBlobId] = blob.copy(sourceCount = 99)
        val issues = List(128) { RecoveryIssue(UUID.randomUUID().toString(), "INTENT", null, RecoveryAction.RETRY) }; val before = state(f)
        val result = service(f, ingest { RecoveryReport(130, 0, 0, 0, 130, issues) }).reconcile()
        assertEquals(137L, result.inspected); assertEquals(132L, result.blocked); assertEquals(issues, result.safeIssues); assertEquals(before, state(f))
    }

    @Test fun repeatPassDoesNotReexpireOrMutateHistoryPinsAndRevisions() = runTest {
        val f = RepositoryFixture(); edit(f); val recovery = service(f)
        assertEquals(1L, recovery.reconcile().completed); val before = state(f); val undos = f.metadata.undos.toMap()
        assertEquals(RecoveryReport(7, 0, 0, 0, 0, emptyList()), recovery.reconcile())
        assertEquals(before, state(f)); assertEquals(undos, f.metadata.undos)
    }

    @Test fun ingestCancellationDoesNotStartExpiryAndIsNotAZeroSuccess() = runTest {
        val f = RepositoryFixture(); val undo = edit(f); val before = state(f)
        var canceled = false
        try { service(f, ingest { throw kotlinx.coroutines.CancellationException("synthetic cancellation") }).reconcile() }
        catch (_: kotlinx.coroutines.CancellationException) { canceled = true }
        assertTrue(canceled); assertEquals("AVAILABLE", f.metadata.undos.getValue(undo).state); assertEquals(before, state(f))
    }

    @Test fun malformedWholeListingPageRejectsBeforeAnyMemberExpiry() = runTest {
        for (fault in listOf("EMPTY_ID", "DUPLICATE", "UNSORTED", "OVERSIZED")) {
            val f = RepositoryFixture(); val undo = edit(f); val id = f.document.value; val valid = listOf(id, UUID.randomUUID().toString()).sorted()
            val ids = when (fault) { "EMPTY_ID" -> listOf(""); "DUPLICATE" -> listOf(id, id); "UNSORTED" -> valid.reversed(); else -> List(65) { id } }
            val error = try { service(f, metadata = listing(f) { _, limit -> check(limit == 64); ids }).reconcile(); throw AssertionError("Malformed document listing was accepted") }
            catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); assertFalse(error.retryable)
            assertEquals("invalid_store_recovery_listing", error.safeMessageKey); assertEquals("AVAILABLE", f.metadata.undos.getValue(undo).state)
        }
    }

    @Test fun laterListingFailurePreservesEarlierExpiryCommitsAndSanitizesPrivateText() = runTest {
        val f = RepositoryFixture(); val ids = retainedDocuments(f, 70).sorted()
        val metadata = listing(f) { after, _ -> if (after == null) ids.take(64) else throw IOException("content://private-provider/title") }
        val error = try { service(f, metadata = metadata).reconcile(); throw AssertionError("Incomplete listing returned success") }
        catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.STORAGE_FAILED, error.code); assertEquals("store_recovery_listing_failed", error.safeMessageKey); assertNull(error.affectedId)
        assertEquals(64, f.metadata.undos.values.count { it.state == "EXPIRED" }); assertEquals(6, f.metadata.undos.values.count { it.state == "AVAILABLE" })
    }

    @Test fun overlappingComposedPassesWaitForTheFirstInsteadOfDuplicatingItsWork() = runTest {
        val f = RepositoryFixture(); edit(f); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val recovery = service(f, ingest { calls++; if (calls == 1) { entered.complete(Unit); release.await() }; empty })
        val results = mutableListOf<RecoveryReport>()
        val first = launch(Dispatchers.Unconfined) { results += recovery.reconcile() }; entered.await()
        val second = launch(Dispatchers.Unconfined) { results += recovery.reconcile() }
        assertEquals(1, calls); assertTrue(results.isEmpty()); release.complete(Unit); first.join(); second.join()
        assertEquals(listOf(1L, 0L), results.map { it.completed }); assertEquals(2, calls)
    }

    @Test fun canonicalRepositoryEntryRunsTheConfiguredRealComposition() = runTest {
        val f = RepositoryFixture(); val undo = edit(f); val recovery = service(f)
        val constructor = RoomDocumentRepository::class.java.constructors.singleOrNull { it.parameterCount == 5 }
            ?: throw AssertionError("Canonical repository recovery injection is absent")
        val repository = constructor.newInstance(f.metadata, RecipeAdmission { _, _ -> null }, { 1000L }, Dispatchers.Unconfined, recovery) as DocumentRepository
        assertEquals(RecoveryReport(8, 1, 0, 0, 0, emptyList()), canonical(repository))
        assertEquals("EXPIRED", f.metadata.undos.getValue(undo).state)
    }

    @Test fun unconfiguredRepositoryCannotPretendRecoveryCompleted() = runTest {
        val f = RepositoryFixture(); val undo = edit(f)
        val error = try { canonical(f.repository); throw AssertionError("Unconfigured recovery returned success") }
        catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, error.code); assertFalse(error.retryable); assertEquals("recovery_unavailable", error.safeMessageKey)
        assertEquals("AVAILABLE", f.metadata.undos.getValue(undo).state)
    }

    @Test fun typedIngestFailureIsPreservedButRawFailureIsSanitized() = runTest {
        for (typed in listOf(false, true)) {
            val f = RepositoryFixture(); val undo = edit(f); val safe = OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "source_changed", f.source.value)
            val error = try { service(f, ingest { if (typed) throw RepositoryFailure(safe) else throw IOException("content://private-provider/title") }).reconcile(); throw AssertionError("Failed recovery returned success") }
            catch (failure: RepositoryFailure) { failure.error }
            if (typed) assertEquals(safe, error) else assertEquals(OperationError(OperationErrorCode.STORAGE_FAILED, true, "store_recovery_failed", null), error)
            assertEquals("AVAILABLE", f.metadata.undos.getValue(undo).state)
        }
    }

    @Test fun negativeOrOverflowingComponentCountsNeverBecomeACleanReport() = runTest {
        val reports = listOf(RecoveryReport(-1, 0, 0, 0, 0, emptyList()), RecoveryReport(0, -1, 0, 0, 0, emptyList()), RecoveryReport(0, 0, -1, 0, 0, emptyList()), RecoveryReport(0, 0, 0, -1, 0, emptyList()), RecoveryReport(0, 0, 0, 0, -1, emptyList()), RecoveryReport(0, 1, 0, 0, 0, emptyList()), RecoveryReport(Long.MAX_VALUE, 0, 0, 0, 0, emptyList()))
        for (report in reports) {
            val f = RepositoryFixture(); val undo = edit(f)
            val error = try { service(f, ingest { report }).reconcile(); throw AssertionError("Invalid aggregate counts were accepted") }
            catch (failure: RepositoryFailure) { failure.error }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.code); assertEquals("invalid_store_recovery_report", error.safeMessageKey)
            assertEquals("AVAILABLE", f.metadata.undos.getValue(undo).state)
        }
    }
}
