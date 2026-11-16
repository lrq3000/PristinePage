package app.pristinepage.data

import app.pristinepage.data.model.*
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Actual reservation/binding metadata commands and transaction rollback; only unavailable Room is substituted. Simulated terminal rows exercise routing, not byte acceptance or a real commit receipt. */
class ImportSelectionTest {
    private val refs = listOf("content://fixture.documents/a", "content://fixture.documents/b", "content://fixture.documents/a")
    private suspend fun prepare(f: ImportSessionTest.Fixture, batch: ImportSessionId, references: List<String>): ImportBatch {
        val method = f.commands.javaClass.methods.singleOrNull { it.name.startsWith("prepareSelection-") }
            ?: throw AssertionError("Atomic durable selection handoff is absent")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try { method.invoke(f.commands, batch.value, references, continuation) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
    private suspend fun rejected(f: ImportSessionTest.Fixture, batch: ImportSessionId, references: List<String>): OperationError = try {
        prepare(f, batch, references); throw AssertionError("Invalid selection was routed")
    } catch (failure: RepositoryFailure) { failure.error }

    @Test fun fullSelectionBindsEveryOrdinalAndDuplicatesWithoutPublishingPages() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, f.pages.pageIds[0])
        val result = prepare(f, batch.parentId, refs); val children = f.children(batch.parentId.value)
        assertEquals(batch.parentId, result.parentId); assertEquals(3, result.childIds.toSet().size)
        assertEquals(result.childIds.map { it.value }, children.map { it.sessionId }); assertEquals(listOf(0, 1, 2), children.map { it.selectionOrdinal })
        assertEquals(refs, children.map { it.uriText }); assertTrue(children.all { it.state == "BOUND" && it.documentId == f.pages.document.value })
        assertEquals(2, f.pages.metadata.pages.size); assertEquals(1L, f.pages.metadata.documents[f.pages.document.value]!!.documentRevision)
        val token = f.rows[batch.parentId.value]!!.uriText!!
        assertTrue(token.matches(Regex("photo-selection-v1:[0-9a-f]{64}"))); assertFalse(token.contains("content:"))
        val before = f.rows.toMap(); assertEquals(result, prepare(f, batch.parentId, refs)); assertEquals(before, f.rows)
    }

    @Test fun sameSelectionReplaysTerminalChildrenAfterTheirProviderUrisWereCleared() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null); val first = prepare(f, batch.parentId, refs)
        for ((index, child) in f.children(batch.parentId.value).withIndex()) f.rows[child.sessionId] = child.copy(state = if (index == 1) "FAILED" else "COMMITTED", uriText = null)
        f.rows[batch.parentId.value] = f.rows[batch.parentId.value]!!.copy(state = "COMMITTED")
        val before = f.rows.toMap(); f.onUpdate = { throw AssertionError("Terminal replay attempted a metadata write") }
        assertEquals(first, prepare(f, batch.parentId, refs)); assertEquals(before, f.rows)
        for (changed in listOf(refs.reversed(), refs.dropLast(1), listOf(refs[0], refs[2], refs[1]))) {
            // The palindrome's reversal is intentionally identical; changing duplicate positions is not.
            if (changed == refs) continue
            assertEquals(OperationErrorCode.INVALID_INPUT, rejected(f, batch.parentId, changed).code); assertEquals(before, f.rows)
        }
    }

    @Test fun partialCompletionKeepsAllChildrenAndNeverRebindsAClearedSuccessfulUri() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null); val first = prepare(f, batch.parentId, refs)
        val child = f.rows[first.childIds[0].value]!!; f.rows[child.sessionId] = child.copy(state = "COMMITTED", uriText = null)
        val before = f.rows.toMap(); assertEquals(first, prepare(f, batch.parentId, refs)); assertEquals(before, f.rows)
        assertEquals(OperationErrorCode.INVALID_INPUT, rejected(f, batch.parentId, refs.toMutableList().apply { this[2] = "content://fixture.documents/other" }).code)
        assertEquals(before, f.rows)
    }

    @Test fun invalidLastReferenceCannotLeaveAPartlyBoundOrReservedSelection() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null); val before = f.rows.toMap()
        for (bad in listOf("file:///private/a", "content://fixture.documents/\u0000", "content:relative")) {
            assertEquals(OperationErrorCode.INVALID_INPUT, rejected(f, batch.parentId, listOf(refs[0], bad)).code); assertEquals(before, f.rows)
        }
    }

    @Test fun fallbackOverSelectionRejectsWholeBatchWithoutTruncatingOrAllocatingChildren() = runTest {
        val f = ImportSessionTest.Fixture(99); val batch = f.commands.beginImport(f.pages.document, 1, null); val before = f.rows.toMap()
        for (references in listOf(refs, List(101) { refs[0] })) {
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, rejected(f, batch.parentId, references).code); assertEquals(before, f.rows)
        }
        assertEquals(99, f.pages.metadata.pages.size)
    }

    @Test fun lateBindingWriteFailureRollsBackAllReservationsReferencesAndSelectionToken() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null); val before = f.rows.toMap()
        f.onUpdate = { if (it.kind == "ITEM" && it.selectionOrdinal == 1) throw java.io.IOException("synthetic private diagnostic") }
        val error = rejected(f, batch.parentId, refs); assertEquals(OperationErrorCode.STORAGE_FAILED, error.code); assertTrue(error.retryable)
        assertFalse(error.safeMessageKey.contains("private")); assertEquals(before, f.rows)
    }

    @Test fun canceledDuringReservationCannotCommitAnyPartOfTheSelection() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null); val before = f.rows.toMap(); var canceled = false
        launch {
            val context = coroutineContext; f.onUpdate = { if (it.kind == "BATCH") context.cancel() }
            try { prepare(f, batch.parentId, refs); throw AssertionError("Canceled reservation was delivered") } catch (_: CancellationException) { canceled = true }
        }.join()
        assertTrue(canceled); assertEquals(before, f.rows)
    }

    @Test fun emptySelectionDurablyCancelsOnlyAnUnselectedBatchAndCanReplay() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null)
        assertEquals(batch, prepare(f, batch.parentId, emptyList())); val before = f.rows.toMap()
        assertEquals("CANCELED", f.rows[batch.parentId.value]!!.state); assertTrue(f.children(batch.parentId.value).isEmpty())
        assertEquals(batch, prepare(f, batch.parentId, emptyList())); assertEquals(before, f.rows)
        assertEquals(OperationErrorCode.INVALID_INPUT, rejected(f, batch.parentId, refs).code)
        val another = f.commands.beginImport(f.pages.document, 1, null); prepare(f, another.parentId, refs)
        assertEquals(OperationErrorCode.INVALID_INPUT, rejected(f, another.parentId, emptyList()).code)
    }

    @Test fun expiredCanceledWrongKindOrChangedMembershipCannotAcceptANewSelection() = runTest {
        for (mode in 0..3) {
            val f = ImportSessionTest.Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null)
            when (mode) {
                0 -> f.now = f.rows[batch.parentId.value]!!.expiresAt
                1 -> f.rows[batch.parentId.value] = f.rows[batch.parentId.value]!!.copy(state = "CANCELED")
                2 -> f.rows[batch.parentId.value] = f.rows[batch.parentId.value]!!.copy(kind = "ITEM")
                else -> f.pages.metadata.pages[f.pages.pageIds[0].value] = f.pages.metadata.pages[f.pages.pageIds[0].value]!!.copy(ordinal = 2)
            }
            val before = f.rows.toMap(); val code = rejected(f, batch.parentId, refs).code
            assertEquals(if (mode == 3) OperationErrorCode.INTEGRITY_FAILED else OperationErrorCode.SESSION_EXPIRED, code); assertEquals(before, f.rows)
        }
    }

    @Test fun legacyPreboundSelectionCanBeAdoptedButCannotInventTerminalSelectionIdentity() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null); val reserved = f.commands.reserveImportItems(batch.parentId, 3)
        val bindings = f.commands as app.pristinepage.data.repository.ImportItemBindings
        bindings.bindProviderItem(batch.parentId, 0, refs[0]); val before = f.rows.toMap()
        assertEquals(OperationErrorCode.INVALID_INPUT, rejected(f, batch.parentId, listOf(refs[1], refs[1], refs[2])).code); assertEquals(before, f.rows)
        assertEquals(reserved, prepare(f, batch.parentId, refs))
        val parent = f.rows[batch.parentId.value]!!; f.rows[parent.sessionId] = parent.copy(uriText = null, state = "COMMITTED")
        assertEquals(OperationErrorCode.SESSION_EXPIRED, rejected(f, batch.parentId, refs).code)
    }
}
