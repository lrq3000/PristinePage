package app.pristinepage.data

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

/** Real repository/policy behavior with only Android metadata substituted. Expiry changes availability only, never pins/history/files; real SQLite state-only and late-rollback proofs remain native obligations. */
class UndoRetentionTest {
    private fun nonUndoState(f: RepositoryFixture): List<Any> = with(f.metadata) {
        listOf(documents.toMap(), pages.toMap(), sources.toMap(), blobs.toMap(), recipes.toMap(), references.toMap(), masks.toMap(), associations.toMap(), maskReferences.toMap())
    }

    private suspend fun edits(f: RepositoryFixture, count: Int): List<UndoId> = (0 until count).map {
        val revision = f.metadata.pages.getValue(f.pageIds[0].value).currentRecipeRevision
        val result = f.repository.rotatePage(f.pageIds[0], revision, 1) as DocumentMutationResult.Applied
        checkNotNull(result.undoId)
    }

    private suspend fun expire(f: RepositoryFixture, clock: () -> Long): RecoveryReport {
        val type = try { Class.forName("app.pristinepage.data.repository.RoomUndoRetention") }
        catch (_: ClassNotFoundException) { throw AssertionError("Undo availability retention is absent") }
        val service = type.constructors.single { it.parameterCount == 3 }.newInstance(f.metadata, clock, Dispatchers.Unconfined)
        val entry = type.methods.singleOrNull { it.name.startsWith("expireDocument") }
            ?: throw AssertionError("Undo availability expiry route is absent")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try { entry.invoke(service, f.document.value, continuation) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }

    @Test fun twentyFirstRecipeEditExpiresOnlyTheOldestRecordedUndoWithoutReleasingPins() = runTest {
        val f = RepositoryFixture(); val ids = edits(f, 21)
        assertEquals("EXPIRED", f.metadata.undos.getValue(ids.first().value).state)
        assertEquals(List(20) { "AVAILABLE" }, ids.drop(1).map { f.metadata.undos.getValue(it.value).state })
        assertEquals(21, f.metadata.undos.size); assertEquals(23, f.metadata.recipes.size)
        assertEquals(46L, f.metadata.sources.getValue(f.source.value).refCount)
        assertEquals(21, f.metadata.references.values.count { it.ownerType == "UNDO" })
        assertEquals(listOf(f.pageIds[0].value, f.pageIds[1].value), f.metadata.activePages(f.document.value).map { it.pageId })
    }

    @Test fun membershipAndRecipeCommandsShareTheSameTwentyEntryWindow() = runTest {
        val f = RepositoryFixture(); val ids = edits(f, 20)
        val result = f.repository.reorderPages(f.document, 21, f.pageIds.reversed()) as DocumentMutationResult.Applied
        assertEquals("EXPIRED", f.metadata.undos.getValue(ids.first().value).state)
        assertEquals("AVAILABLE", f.metadata.undos.getValue(checkNotNull(result.undoId).value).state)
        assertEquals(20, f.metadata.undos.values.count { it.state == "AVAILABLE" })
        assertEquals(21, f.metadata.references.values.count { it.ownerType == "UNDO" })
    }

    @Test fun periodicExpiryIsInclusiveAtTwentyFourHoursAndPreservesAllPinsAndHistoryBytes() = runTest {
        val f = RepositoryFixture(); f.protectionMask(f.pageIds[0], 1); val id = edits(f, 1).single()
        val entry = f.metadata.undos.getValue(id.value); val before = nonUndoState(f)
        val oldBefore = entry.beforeState.copyOf(); val oldAfter = entry.afterState.copyOf()
        assertEquals(RecoveryReport(1, 0, 0, 0, 0, emptyList()), expire(f) { 86_400_999L })
        assertEquals("AVAILABLE", f.metadata.undos.getValue(id.value).state)
        assertEquals(RecoveryReport(1, 1, 0, 0, 0, emptyList()), expire(f) { 86_401_000L })
        assertEquals("EXPIRED", f.metadata.undos.getValue(id.value).state)
        assertArrayEquals(oldBefore, f.metadata.undos.getValue(id.value).beforeState)
        assertArrayEquals(oldAfter, f.metadata.undos.getValue(id.value).afterState)
        assertEquals(before, nonUndoState(f))
    }

    @Test fun appliedUndoEntriesStillCountAsRecordedEditsAndCanExpireButNeverReactivate() = runTest {
        val f = RepositoryFixture(); val first = edits(f, 1).single()
        assertTrue(f.repository.undoDocumentEdit(first, 2) is DocumentMutationResult.Applied)
        assertEquals("APPLIED", f.metadata.undos.getValue(first.value).state)
        edits(f, 20)
        assertEquals("EXPIRED", f.metadata.undos.getValue(first.value).state)
        assertEquals(RecoveryReport(20, 0, 0, 0, 0, emptyList()), expire(f) { 0L })
        assertEquals("EXPIRED", f.metadata.undos.getValue(first.value).state)
    }

    @Test fun oldestExpiredTokenCannotUndoARepeatedLogicalAfterStateButNewestStillWorks() = runTest {
        val f = RepositoryFixture(); val ids = edits(f, 21); val before = nonUndoState(f)
        //21quarter-turns repeat the first edit's q1 after-state: content equality alone must not let its evicted token execute.
        val rejected = f.repository.undoDocumentEdit(ids.first(), 22) as? DocumentMutationResult.Rejected
            ?: throw AssertionError("Oldest token outside the twenty-edit window still executed")
        assertEquals(OperationErrorCode.INVALID_INPUT, rejected.error.code); assertEquals(before, nonUndoState(f))
        assertTrue(f.repository.undoDocumentEdit(ids.last(), 22) is DocumentMutationResult.Applied)
        assertEquals(0, f.current(f.pageIds[0]).geometry.outputQuarterTurns)
        assertEquals(23L, f.metadata.pages.getValue(f.pageIds[0].value).currentRecipeRevision)
    }

    @Test fun lateExpiryWriteFailureRollsBackTheWholeCreatingEdit() = runTest {
        val f = RepositoryFixture(); edits(f, 20); val before = nonUndoState(f); val undos = f.metadata.undos.toMap()
        val failingQueries = Proxy.newProxyInstance(CatalogQueries::class.java.classLoader, arrayOf(CatalogQueries::class.java, EditablePageQueries::class.java)) { _, method, args ->
            if (method.name == "expireUndoEntries") throw IOException("content://private-provider/retention-fault")
            try { method.invoke(f.metadata, *(args ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        } as CatalogQueries
        val metadata = object : MetadataAccess {
            override val queries = failingQueries
            override fun <T> transaction(action: () -> T): T = f.metadata.transaction(action)
        }
        val repository = RoomDocumentRepository(metadata, RecipeAdmission { _, _ -> null }, { 1000L }, Dispatchers.Unconfined)
        val rejected = repository.rotatePage(f.pageIds[0], 21, 1) as? DocumentMutationResult.Rejected
            ?: throw AssertionError("Creating edit committed without completing its Undo window")
        assertEquals(OperationErrorCode.STORAGE_FAILED, rejected.error.code); assertTrue(rejected.error.retryable)
        assertEquals(before, nonUndoState(f)); assertEquals(undos, f.metadata.undos)
    }

    @Test fun clockCallbackCancellationCannotPerformStandaloneExpiry() = runTest {
        val f = RepositoryFixture(); val id = edits(f, 1).single(); val before = nonUndoState(f); val undos = f.metadata.undos.toMap(); val job = Job()
        var canceled = false
        try { withContext(job) { expire(f) { job.cancel(); 86_401_000L } } }
        catch (_: CancellationException) { canceled = true }
        assertTrue(canceled); assertEquals("AVAILABLE", f.metadata.undos.getValue(id.value).state)
        assertEquals(before, nonUndoState(f)); assertEquals(undos, f.metadata.undos)
    }

    @Test fun periodicExpiryIsIdempotentAndScopedToItsNamedDocument() = runTest {
        val f = RepositoryFixture(); val other = RepositoryFixture(); val id = edits(f, 1).single(); val foreign = edits(other, 1).single()
        with(f.metadata) {
            documents.putAll(other.metadata.documents); pages.putAll(other.metadata.pages); sources.putAll(other.metadata.sources); blobs.putAll(other.metadata.blobs)
            recipes.putAll(other.metadata.recipes); references.putAll(other.metadata.references); undos.putAll(other.metadata.undos)
        }
        val before = nonUndoState(f)
        assertEquals(RecoveryReport(1, 1, 0, 0, 0, emptyList()), expire(f) { 86_401_000L })
        assertEquals(RecoveryReport(0, 0, 0, 0, 0, emptyList()), expire(f) { 86_401_000L })
        assertEquals("EXPIRED", f.metadata.undos.getValue(id.value).state)
        assertEquals("AVAILABLE", f.metadata.undos.getValue(foreign.value).state); assertEquals(before, nonUndoState(f))
    }

    @Test fun expiringRemovalUndoDoesNotFinalizeThePageOrReleaseItsSourceHistoryPins() = runTest {
        val f = RepositoryFixture(); val result = f.repository.removePage(f.pageIds[0], 1) as DocumentMutationResult.Applied
        val before = nonUndoState(f)
        assertEquals(RecoveryReport(1, 1, 0, 0, 0, emptyList()), expire(f) { 86_401_000L })
        assertEquals("EXPIRED", f.metadata.undos.getValue(checkNotNull(result.undoId).value).state)
        assertEquals("REMOVED", f.metadata.pages.getValue(f.pageIds[0].value).state)
        assertEquals(before, nonUndoState(f)); assertEquals(5L, f.metadata.countSourceReferences(f.source.value))
    }

    @Test fun legacyAvailableRecipeTokenOutsideWindowCannotExecuteBeforeStartupExpiry() = runTest {
        val f = RepositoryFixture(); val ids = edits(f, 21); val first = ids.first().value
        // An older build could retain21AVAILABLE rows. Recreate that state without changing its genuine history/pins, and without running the new standalone expiry pass.
        f.metadata.undos[first] = f.metadata.undos.getValue(first).copy(state = "AVAILABLE")
        val before = nonUndoState(f); val undos = f.metadata.undos.toMap()
        val rejected = f.repository.undoDocumentEdit(ids.first(), 22) as? DocumentMutationResult.Rejected
            ?: throw AssertionError("Legacy over-window recipe token executed before startup expiry")
        assertEquals(OperationErrorCode.INVALID_INPUT, rejected.error.code)
        assertEquals(before, nonUndoState(f)); assertEquals(undos, f.metadata.undos)
        assertTrue(f.repository.undoDocumentEdit(ids.last(), 22) is DocumentMutationResult.Applied)
    }

    @Test fun legacyAvailableMembershipTokenOutsideWindowCannotExecuteBeforeStartupExpiry() = runTest {
        val f = RepositoryFixture()
        val ids = (0 until 21).map { index ->
            val order = if (index % 2 == 0) f.pageIds.reversed() else f.pageIds
            val result = f.repository.reorderPages(f.document, index + 1L, order) as DocumentMutationResult.Applied
            checkNotNull(result.undoId)
        }
        val first = ids.first().value
        f.metadata.undos[first] = f.metadata.undos.getValue(first).copy(state = "AVAILABLE")
        val before = nonUndoState(f); val undos = f.metadata.undos.toMap()
        val rejected = f.repository.undoDocumentEdit(ids.first(), 22) as? DocumentMutationResult.Rejected
            ?: throw AssertionError("Legacy over-window membership token executed before startup expiry")
        assertEquals(OperationErrorCode.INVALID_INPUT, rejected.error.code)
        assertEquals(before, nonUndoState(f)); assertEquals(undos, f.metadata.undos)
        assertTrue(f.repository.undoDocumentEdit(ids.last(), 22) is DocumentMutationResult.Applied)
    }
}
