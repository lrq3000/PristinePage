package app.pristinepage

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.model.*
import app.pristinepage.capture.PhotoImportRequest
import app.pristinepage.library.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Consumes the real ID-only discovery through generated Room/Application services. No saved-state Bundle or provider access is used; fixture roots remain private and retained. */
@RunWith(AndroidJUnit4::class)
class ImportLibraryIntegrationTest {
    @Test fun lostUiStateCanRediscoverAndAbandonOnlyItsOwnDurableInsertionAfterReopen() = runBlocking {
        DocumentCatalogIntegrationTest.Fixture().use { f ->
            var graph = f.open()
            val first = graph.store.documents.createDocument("Pending first")
            val second = graph.store.documents.createDocument("Pending second")
            val a = graph.store.imports.beginImport(first, 1, null).parentId
            val b = graph.store.imports.beginImport(second, 1, null).parentId
            graph = f.reopen()
            assertEquals(a, graph.store.catalog.activeImport(first)); assertEquals(b, graph.store.catalog.activeImport(second))
            graph.store.lifecycle.cancelBatch(graph.store.catalog.activeImport(first)!!)
            assertNull(graph.store.catalog.activeImport(first)); assertEquals(b, graph.store.catalog.activeImport(second))
            assertTrue(graph.store.catalog.load(first).pages.isEmpty()); assertTrue(graph.store.catalog.load(second).pages.isEmpty())
            assertEquals(0, f.providerOpens)
        }
    }

    @Test fun nativeDiscoveryRefusesAmbiguousOrOversizedBatchIdentitiesWithoutMaterializingPrivateReferences() = runBlocking {
        DocumentCatalogIntegrationTest.Fixture().use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Discovery corrupt fixture")
            val batch = graph.store.imports.beginImport(doc, 1, null).parentId
            f.write("INSERT INTO ImportSession(sessionId,kind,documentId,state,expectedMembershipToken,createdAt,expiresAt) VALUES (?,'BATCH',?,'WAITING_SELECTION',?,0,1)", UUID.randomUUID().toString(), doc.value, "a".repeat(64))
            refused { graph.store.catalog.activeImport(doc) }
            f.write("UPDATE ImportSession SET state='CANCELED' WHERE sessionId<>?", batch.value)
            f.write("UPDATE ImportSession SET sessionId=? WHERE sessionId=?", "x".repeat(4096), batch.value)
            refused { graph.store.catalog.activeImport(doc) }
            assertEquals(0, f.providerOpens)
        }
    }

    @Test fun realApplicationAdapterPublishesOrderedMixedSessionOutcomesAndOwnedSavedPages() = runBlocking {
        DocumentCatalogIntegrationTest.Fixture().use { f ->
            val graph = f.open(); val actions = ApplicationLibraryActions { graph }
            val doc = actions.create("Native session mixed results")
            withSession(actions, doc) { session ->
                withContext(Dispatchers.Main) { session.accept(LibraryEvent.BeginImport) }
                val request = withTimeout(10_000) { session.state.first { it.request != null && it.launchPending } }.request!!
                withContext(Dispatchers.Main) { session.accept(LibraryEvent.PhotoResult(request, LibraryPhotoResult.Selected(listOf(f.good, f.bad, f.good), 0))) }
                val state = withTimeout(10_000) { session.state.first { it.phase == ImportPhase.COMPLETE && !it.loading } }
                assertEquals(3, state.results.size); assertTrue(state.results[1] is IngestResult.Rejected)
                val accepted = listOf(state.results[0] as IngestResult.Committed, state.results[2] as IngestResult.Committed)
                assertEquals(listOf(doc, doc), accepted.map { it.documentId })
                assertEquals(accepted.map { it.pageId }, state.document!!.pages.map { it.pageId })
                assertEquals(2, state.document.document.pageCount); assertNull(session.identity().pending)
                assertArrayEquals(f.png, graph.store.committedSources.load(accepted[0].sourceId).file.readBytes())
                assertEquals(3, f.providerOpens); assertEquals(0L, graph.budget.usedBytes)
            }
        }
    }

    @Test fun realCancellationAcknowledgesTheFenceButWaitsForBlockedSourceIoAndKeepsTheFirstPage() = runBlocking { blockedCancellation(false) }

    @Test fun readFailureAndDuplicateResultCannotDetachTheActualBlockedConsumerFromCancellation() = runBlocking { blockedCancellation(true) }

    private suspend fun blockedCancellation(withReadFailure: Boolean) {
        DocumentCatalogIntegrationTest.Fixture().use { f ->
            val graph = f.open(); val delegate = ApplicationLibraryActions { graph }
            val fenced = CompletableDeferred<Unit>()
            var failRead = false; var deliveries = 0
            val actions = object : LibraryActions by delegate {
                override suspend fun cancel(request: PhotoImportRequest) { delegate.cancel(request); fenced.complete(Unit) }
                override suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary> {
                    if (failRead) throw java.io.IOException("Synthetic metadata read failure")
                    return delegate.list(after, limit)
                }
                override suspend fun deliver(request: PhotoImportRequest, selection: LibraryPhotoResult): List<IngestResult> { deliveries++; return delegate.deliver(request, selection) }
            }
            val doc = actions.create("Native session cancellation")
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.beforeProvider = { _, count -> if (count == 2) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
            withSession(actions, doc) { session ->
                try {
                    withContext(Dispatchers.Main) { session.accept(LibraryEvent.BeginImport) }
                    val request = withTimeout(10_000) { session.state.first { it.request != null && it.launchPending } }.request!!
                    withContext(Dispatchers.Main) { session.accept(LibraryEvent.PhotoResult(request, LibraryPhotoResult.Selected(listOf(f.good, f.good, f.good), 0))) }
                    assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                    if (withReadFailure) {
                        withContext(Dispatchers.Main) { failRead = true; session.accept(LibraryEvent.Refresh) }
                        withTimeout(10_000) { session.state.first { it.error?.code == OperationErrorCode.STORAGE_FAILED } }
                        withContext(Dispatchers.Main) {
                            assertEquals(ImportPhase.COPYING, session.state.value.phase)
                            session.accept(LibraryEvent.PhotoResult(request, LibraryPhotoResult.Selected(listOf(f.good, f.good, f.good), 0)))
                            assertEquals(1, deliveries); failRead = false
                        }
                    }
                    withContext(Dispatchers.Main) { session.accept(LibraryEvent.CancelImport) }
                    withTimeout(10_000) { fenced.await() }
                    assertEquals(ImportPhase.CANCELING, session.state.value.phase)
                    assertNull(graph.store.catalog.activeImport(doc))
                    release.countDown()
                    val state = withTimeout(10_000) { session.state.first { it.phase == ImportPhase.CANCELED && !it.loading && it.document?.pages?.size == 1 } }
                    assertNull(session.identity().pending); assertEquals(1, state.document!!.document.pageCount)
                    assertEquals(2, f.providerOpens); assertEquals(0L, graph.budget.usedBytes)
                    assertEquals(1, deliveries)
                    assertArrayEquals(f.png, graph.store.committedSources.load(state.document.pages.single().sourceId).file.readBytes())
                } finally { release.countDown(); f.beforeProvider = { _, _ -> } }
            }
        }
    }

    @Test fun missingAndWrongKindSavedRequestsRecoverThroughRealMetadataWithoutProviderIo() = runBlocking {
        DocumentCatalogIntegrationTest.Fixture().use { f ->
            val graph = f.open(); val actions = ApplicationLibraryActions { graph }
            val doc = actions.create("Unusable saved request recovery")
            withSession(actions, doc, ImportSessionId(UUID.randomUUID().toString())) { session ->
                withContext(Dispatchers.Main) { session.accept(LibraryEvent.CancelImport) }
                withTimeout(10_000) { session.state.first { it.request == null && !it.loading && it.error?.code == OperationErrorCode.SESSION_EXPIRED } }
                withContext(Dispatchers.Main) { session.accept(LibraryEvent.BeginImport) }
                val ready = withTimeout(10_000) { session.state.first { it.request != null && it.launchPending } }
                assertEquals(graph.store.catalog.activeImport(doc)!!.value, ready.request!!.batchId)
                withContext(Dispatchers.Main) { session.accept(LibraryEvent.CancelImport) }
                withTimeout(10_000) { session.state.first { it.request == null && !it.loading } }
            }
            val parent = graph.store.imports.beginImport(doc, 1, null).parentId
            val child = graph.store.selections.prepareSelection(parent, listOf(f.good)).childIds.single()
            withSession(actions, doc, child) { session ->
                withContext(Dispatchers.Main) { session.accept(LibraryEvent.CancelImport) }
                val recovered = withTimeout(10_000) { session.state.first { it.request?.batchId == parent.value && !it.loading } }
                assertFalse(recovered.launchPending); assertEquals(ImportPhase.INTERRUPTED, recovered.phase)
                assertEquals(parent, graph.store.catalog.activeImport(doc))
                withContext(Dispatchers.Main) { session.accept(LibraryEvent.CancelImport) }
                withTimeout(10_000) { session.state.first { it.request == null && !it.loading } }
            }
            assertNull(graph.store.catalog.activeImport(doc)); assertTrue(graph.store.catalog.load(doc).pages.isEmpty())
            assertEquals(0, f.providerOpens); assertEquals(0L, graph.budget.usedBytes)
        }
    }

    private companion object {
        suspend fun withSession(actions: LibraryActions, doc: DocumentId, pending: ImportSessionId? = null, body: suspend (LibrarySession) -> Unit) {
            val owner = SupervisorJob(); val scope = CoroutineScope(owner + Dispatchers.Main.immediate)
            try {
                val session = withContext(Dispatchers.Main) { ImportLibrarySession(actions, LibraryIdentity(doc, pending), scope) }
                withTimeout(10_000) { session.state.first { !it.loading && it.document != null } }
                body(session)
            } finally { owner.cancelAndJoin() }
        }
        suspend fun refused(action: suspend () -> Any?) {
            try { action(); fail("Malformed insertion identity was accepted") }
            catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code) }
        }
    }
}
