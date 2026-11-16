package app.pristinepage.data

import app.pristinepage.data.model.*
import app.pristinepage.data.repository.ImportSelections
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Actual metadata state transitions/rollback, only unavailable Room substituted. Simulated child terminal/ingesting states do not prove codec, IO interruption, native lease or late-publication behavior. */
class ImportCancellationTest {
    private suspend fun cancel(f: ImportSessionTest.Fixture, batch: ImportSessionId): ImportBatch {
        val method = f.commands.javaClass.methods.singleOrNull { it.name.startsWith("cancelBatch-") } ?: throw AssertionError("Pending import batch cancellation is absent")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try { method.invoke(f.commands, batch.value, continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
    private suspend fun prepared(f: ImportSessionTest.Fixture): ImportBatch {
        val batch = f.commands.beginImport(f.pages.document, 1, null)
        return (f.commands as ImportSelections).prepareSelection(batch.parentId, List(3) { "content://fixture.documents/item$it" })
    }

    @Test fun unselectedParentCancelsWithoutCreatingChildrenOrPagesAndAllowsNewBatch() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = f.commands.beginImport(f.pages.document, 1, null)
        assertEquals(batch, cancel(f, batch.parentId)); assertEquals("CANCELED", f.rows[batch.parentId.value]!!.state)
        assertTrue(f.children(batch.parentId.value).isEmpty()); assertEquals(2, f.pages.metadata.pages.size)
        assertNotEquals(batch.parentId, f.commands.beginImport(f.pages.document, 1, null).parentId)
    }

    @Test fun unfinishedItemsLoseOnlyPrivateReferencesAndCannotReceiveLateBindings() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = prepared(f); val pageRows = f.pages.metadata.pages.toMap(); val sourceRows = f.pages.metadata.sources.toMap(); val refs = f.pages.metadata.references.toMap()
        val token = f.rows[batch.parentId.value]!!.uriText
        assertEquals(batch, cancel(f, batch.parentId))
        assertTrue(f.children(batch.parentId.value).all { it.state == "CANCELED" && it.errorCode == "CANCELED" && it.uriText == null })
        assertEquals(token, f.rows[batch.parentId.value]!!.uriText); assertEquals(pageRows, f.pages.metadata.pages); assertEquals(sourceRows, f.pages.metadata.sources); assertEquals(refs, f.pages.metadata.references)
        try { (f.commands as ImportSelections).prepareSelection(batch.parentId, listOf("content://fixture.documents/late")); fail("Canceled batch accepted a new selection") }
        catch (error: RepositoryFailure) { assertEquals(OperationErrorCode.INVALID_INPUT, error.error.code) }
    }

    @Test fun explicitAbandonmentPreservesEveryKnownSuccessFailureAndPlannedPageIdentity() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = prepared(f); val children = f.children(batch.parentId.value)
        f.rows[children[0].sessionId] = children[0].copy(state = "COMMITTED", uriText = null)
        f.rows[children[1].sessionId] = children[1].copy(state = "FAILED", errorCode = "PERMISSION_LOST", uriText = null)
        f.rows[children[2].sessionId] = children[2].copy(state = "INGESTING")
        val committed = f.rows[children[0].sessionId]; val failed = f.rows[children[1].sessionId]
        assertEquals(batch, cancel(f, batch.parentId)); assertEquals("CANCELED", f.rows[batch.parentId.value]!!.state)
        assertEquals(committed, f.rows[children[0].sessionId]); assertEquals(failed, f.rows[children[1].sessionId]); assertEquals(children[2].plannedPageId, f.rows[children[2].sessionId]!!.plannedPageId)
    }

    @Test fun completedOrRepeatedCancellationIsReadOnlyNotARetroactiveOutcomeChange() = runTest {
        for (terminal in listOf("COMMITTED", "FAILED", "CANCELED", "QUARANTINED")) {
            val f = ImportSessionTest.Fixture(); val batch = prepared(f)
            for (child in f.children(batch.parentId.value)) f.rows[child.sessionId] = child.copy(state = terminal, uriText = null)
            f.rows[batch.parentId.value] = f.rows[batch.parentId.value]!!.copy(state = terminal)
            val before = f.rows.toMap(); f.onUpdate = { throw AssertionError("Terminal cancellation attempted a write") }
            assertEquals(batch, cancel(f, batch.parentId)); assertEquals(before, f.rows)
        }
    }

    @Test fun expiredOrChangedMembershipDoesNotTrapAnUnresolvedInsertionForever() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = prepared(f); f.now = f.rows[batch.parentId.value]!!.expiresAt
        f.pages.metadata.pages[f.pages.pageIds[0].value] = f.pages.metadata.pages[f.pages.pageIds[0].value]!!.copy(ordinal = 9)
        assertEquals(batch, cancel(f, batch.parentId)); assertEquals("CANCELED", f.rows[batch.parentId.value]!!.state)
    }

    @Test fun lateMetadataFailureRollsBackAllItemAndParentCancellationWrites() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = prepared(f); val before = f.rows.toMap()
        f.onUpdate = { if (it.kind == "ITEM" && it.selectionOrdinal == 1) throw java.io.IOException("synthetic diagnostic") }
        try { cancel(f, batch.parentId); fail("Failed cancellation partially committed") }
        catch (error: RepositoryFailure) { assertEquals(OperationErrorCode.STORAGE_FAILED, error.error.code) }
        assertEquals(before, f.rows)
    }

    @Test fun cancellationOfTheCommandBeforePublicationCannotMutateAnyRow() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = prepared(f); val before = f.rows.toMap(); var canceled = false
        launch {
            val context = coroutineContext; f.onUpdate = { context.cancel() }
            try { cancel(f, batch.parentId); throw AssertionError("Canceled command delivered") } catch (_: CancellationException) { canceled = true }
        }.join()
        assertTrue(canceled); assertEquals(before, f.rows)
    }

    @Test fun malformedChildDestinationCannotCancelAnotherDocumentsItem() = runTest {
        val f = ImportSessionTest.Fixture(); val batch = prepared(f); val child = f.rows[batch.childIds[1].value]!!
        f.rows[child.sessionId] = child.copy(documentId = java.util.UUID.randomUUID().toString()); val before = f.rows.toMap()
        try { cancel(f, batch.parentId); fail("Cross-document child was mutated") }
        catch (error: RepositoryFailure) { assertEquals(OperationErrorCode.INTEGRITY_FAILED, error.error.code) }
        assertEquals(before, f.rows)
    }
}
