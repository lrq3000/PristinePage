package app.pristinepage

import app.pristinepage.data.model.*
import app.pristinepage.capture.PhotoImportController
import app.pristinepage.data.repository.BoundSourceIngest
import app.pristinepage.data.repository.ImportSelections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Controller ordering/result policy runs on host. The separately tested data selection port and source-store boundary are injected; native Uri/picker/grant and real Room/codec evidence remain separate. */
class PhotoImportControllerTest {
    private class Fixture {
        val parent = ImportSessionId(UUID.randomUUID().toString())
        val doc = DocumentId(UUID.randomUUID().toString())
        val children = List(3) { ImportSessionId(UUID.randomUUID().toString()) }
        val refs = listOf("content://fixture.documents/one", "content://fixture.documents/two", "content://fixture.documents/one")
        val results = children.mapIndexed { ordinal, child -> when (ordinal) {
            0 -> IngestResult.Committed(child, doc, PageId(UUID.randomUUID().toString()), SourceId(UUID.randomUUID().toString()), "a".repeat(64), "b".repeat(64), 2)
            1 -> IngestResult.Rejected(child, OperationError(OperationErrorCode.UNSUPPORTED_FORMAT, false, "synthetic_corrupt_input", child.value))
            else -> IngestResult.Pending(child, FileTransactionState.RETRYABLE)
        } }
        var prepareCount = 0
        val ingested = mutableListOf<Pair<String, ImportSessionId>>()
        var prepared: ImportBatch? = null
        var onPrepare: () -> Unit = {}
        var onIngest: suspend (Int) -> Unit = {}
        var overrideResult: IngestResult? = null
        suspend fun call(references: List<String> = refs): List<IngestResult> {
            val selection = ImportSelections { id, selected ->
                prepareCount++; assertEquals(parent, id); assertEquals(references.toList(), selected); onPrepare()
                prepared ?: ImportBatch(parent, if (selected.isEmpty()) emptyList() else children.take(selected.size))
            }
            val source = object : BoundSourceIngest {
                override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult {
                    val ordinal = children.indexOf(session); check(ordinal >= 0)
                    assertEquals(refs[ordinal], boundReference); ingested += boundReference to session; onIngest(ordinal)
                    return overrideResult ?: results[ordinal]
                }
            }
            return PhotoImportController(selection, source).importProviderReferences(references, parent)
        }
    }
    private suspend fun error(f: Fixture, refs: List<String> = f.refs): OperationError = try { f.call(refs); throw AssertionError("Invalid batch was accepted") } catch (failure: RepositoryFailure) { failure.error }

    @Test fun allOrderedMixedOutcomesRemainVisibleAndDuplicateUrisHaveDistinctChildIds() = runTest {
        val f = Fixture(); assertEquals(f.results, f.call()); assertEquals(f.refs.zip(f.children), f.ingested); assertEquals(1, f.prepareCount)
    }

    @Test fun callbackReplayUsesTheSameChildIdsRatherThanCreatingANewImport() = runTest {
        val f = Fixture(); assertEquals(f.call(), f.call()); assertEquals(f.refs.zip(f.children) + f.refs.zip(f.children), f.ingested)
    }

    @Test fun emptyPickerResultIsExplicitCancellationNotAnEmptySuccessfulList() = runTest {
        val f = Fixture(); val result = f.call(emptyList()).single() as IngestResult.Rejected
        assertEquals(f.parent, result.sessionId); assertEquals(OperationErrorCode.CANCELED, result.error.code); assertEquals(1, f.prepareCount); assertTrue(f.ingested.isEmpty())
    }

    @Test fun overSelectionRefusesBeforeReservationOrIngestWithoutTruncation() = runTest {
        val f = Fixture(); assertEquals(OperationErrorCode.RESOURCE_LIMIT, error(f, List(101) { f.refs[0] }).code)
        assertEquals(0, f.prepareCount); assertTrue(f.ingested.isEmpty())
    }

    @Test fun preflightFailureCannotStartAnySourceCopy() = runTest {
        val f = Fixture(); val failure = OperationError(OperationErrorCode.REVISION_CONFLICT, false, "synthetic_stale_batch", f.parent.value)
        f.onPrepare = { throw RepositoryFailure(failure) }
        assertEquals(failure, error(f)); assertTrue(f.ingested.isEmpty())
    }

    @Test fun wrongBatchChildCountAndAliasedChildrenRefuseBeforeSourceIo() = runTest {
        for (mode in 0..2) {
            val f = Fixture(); f.prepared = when (mode) {
                0 -> ImportBatch(ImportSessionId(UUID.randomUUID().toString()), f.children)
                1 -> ImportBatch(f.parent, f.children.dropLast(1))
                else -> ImportBatch(f.parent, List(3) { f.children[0] })
            }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(f).code); assertTrue(f.ingested.isEmpty())
        }
    }

    @Test fun resultForADifferentChildCannotMasqueradeAsThisItem() = runTest {
        val f = Fixture(); f.overrideResult = IngestResult.Pending(f.children[1], FileTransactionState.INTENT)
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(f).code); assertEquals(listOf(f.refs[0] to f.children[0]), f.ingested)
    }

    @Test fun cancellationStopsLaterItemsAndNeverReturnsPartialSuccess() = runTest {
        val f = Fixture(); f.onIngest = { currentCoroutineContext().cancel() }; var canceled = false
        launch { try { f.call(); throw AssertionError("Canceled callback returned partial success") } catch (_: CancellationException) { canceled = true } }.join()
        assertTrue(canceled); assertEquals(listOf(f.refs[0] to f.children[0]), f.ingested)
    }

    @Test fun callbackOwnedListCannotChangeReferencesWhilePreparationSuspends() = runTest {
        val f = Fixture(); val callback = f.refs.toMutableList(); f.onPrepare = { callback[0] = "content://fixture.documents/replaced" }
        assertEquals(f.results, f.call(callback)); assertEquals(f.refs.zip(f.children), f.ingested)
    }

    @Test fun publishedChildSignalsEvenIfItsBatchIsInterrupted() = runTest {
        val f = Fixture()
        val committed = f.results.first() as IngestResult.Committed
        val hints = mutableListOf<Pair<DocumentId, List<PageId>>>()
        val controller = PhotoImportController(ImportSelections { id, _ -> ImportBatch(id, f.children) }, object : BoundSourceIngest {
            override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult {
                if (session != committed.sessionId) {
                    assertEquals(listOf(f.doc to listOf(committed.pageId)), hints)
                    currentCoroutineContext().cancel()
                    throw CancellationException()
                }
                return committed
            }
        })
        launch { controller.importProviderReferences(f.refs, f.parent) { doc, pages -> hints += doc to pages } }.join()
        assertEquals(listOf(f.doc to listOf(committed.pageId)), hints)
    }

    @Test fun rejectedPendingAndMismatchedChildrenDoNotSignalAndHintFailureDoesNotUndoCommit() = runTest {
        val f = Fixture()
        val committed = f.results.first() as IngestResult.Committed
        var mismatched = false
        val controller = PhotoImportController(ImportSelections { id, _ -> ImportBatch(id, f.children) }, object : BoundSourceIngest {
            override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId) =
                if (mismatched) committed.copy(sessionId = f.children.last()) else f.results[f.children.indexOf(session)]
        })
        var notifications = 0
        assertEquals(f.results, controller.importProviderReferences(f.refs, f.parent) { doc, pages ->
            assertEquals(f.doc, doc)
            assertEquals(listOf(committed.pageId), pages)
            notifications++
            error("Synthetic wake failure")
        })
        assertEquals(1, notifications)
        mismatched = true
        val refusal = runCatching {
            controller.importProviderReferences(f.refs, f.parent) { _, _ -> throw AssertionError("Mismatched child signaled") }
        }.exceptionOrNull() as RepositoryFailure
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, refusal.error.code)
    }
}
