package app.pristinepage.data

import app.pristinepage.data.model.*
import app.pristinepage.data.repository.MetadataAccess
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Assert.*
import org.junit.Test

/** Real audit logic with only Android SQL projected from the complete existing metadata fixture. Reflection makes an absent audit an intended assertion rather than a compiler error. Actual SQL counts remain a separate native obligation. */
class ReferenceCountAuditTest {
    private class Harness(val f: RepositoryFixture = RepositoryFixture()) {
        var failure = false
        var repeat = false
        var invalidId = false
        var onQuery: (() -> Unit)? = null

        suspend fun audit(requiredPins: Boolean = false): RecoveryReport {
            val implementation = try { Class.forName("app.pristinepage.data.repository.RoomReferenceAudit") }
            catch (_: ClassNotFoundException) { throw AssertionError("Required bounded reference-counter audit is absent") }
            val entry = implementation.methods.singleOrNull { it.name == if (requiredPins) "auditRequiredPins" else "audit" }
                ?: throw AssertionError("Required source-pin audit route is absent")
            val port = Class.forName("app.pristinepage.data.repository.ReferenceAuditQueries")
            val row = Class.forName("app.pristinepage.data.repository.ReferenceCountRow").getConstructor(String::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            val queries = Proxy.newProxyInstance(port.classLoader, arrayOf(port)) { _, method, args ->
                check(f.metadata.inTransaction) { "Counter and ownership projection must share the metadata transaction" }
                if (failure) throw IOException("content://private-provider/title-and-token")
                onQuery?.invoke()
                val after = args[0] as String?; val limit = args[1] as Int; check(limit in 1..64)
                val values = with(f.metadata) {
                    when (method.name) {
                        "sourcesAfter" -> sources.values.map { Triple(it.sourceId, it.refCount, countSourceReferences(it.sourceId)) }
                        "sourceBlobsAfter" -> blobs.values.map { blob -> Triple(blob.sourceBlobId, blob.sourceCount, sources.values.count { it.sourceBlobId == blob.sourceBlobId }.toLong()) }
                        "masksAfter" -> masks.values.map { Triple(it.maskId, it.refCount, countMaskReferences(it.maskId)) }
                        "pageSourcePinsAfter" -> pages.values.map { page -> Triple(page.pageId, 1L, if (references.containsKey(Triple("PAGE", page.pageId, page.sourceId))) 1L else 0L) }
                        "recipeSourcePinsAfter" -> pages.values.map { page ->
                            val history = recipes.values.filter { it.pageId == page.pageId }
                            Triple(page.pageId, history.size.toLong(), history.count { references.containsKey(Triple("RECIPE_HISTORY", "recipe-v1/${page.pageId}/${it.recipeRevision}", it.sourceId)) }.toLong())
                        }
                        "maskSourcePinsAfter" -> masks.values.map { mask -> Triple(mask.maskId, 1L, if (references.containsKey(Triple("MASK", mask.maskId, mask.sourceId))) 1L else 0L) }
                        else -> error("Unexpected reference-audit query")
                    }
                }
                values.filter { repeat || after == null || it.first > after }.sortedBy { it.first }.take(limit).map { row.newInstance(if (invalidId) "private/non-opaque" else it.first, it.second, it.third) }
            }
            val audit = implementation.getConstructor(MetadataAccess::class.java, port, CoroutineDispatcher::class.java).newInstance(f.metadata, queries, Dispatchers.Unconfined)
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try { entry.invoke(audit, continuation) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
    }

    private fun metadataState(f: RepositoryFixture): List<Any> = with(f.metadata) {
        listOf(documents.toMap(), pages.toMap(), sources.toMap(), blobs.toMap(), recipes.toMap(), references.toMap(), undos.toMap(), masks.toMap(), associations.toMap(), maskReferences.toMap())
    }

    @Test fun repeatedHealthyAuditReportsOnlyInspectedCountersAndMutatesNothing() = runTest {
        val h = Harness(); val before = metadataState(h.f)
        val expected = RecoveryReport(2, 0, 0, 0, 0, emptyList())
        assertEquals(expected, h.audit()); assertEquals(expected, h.audit()); assertEquals(before, metadataState(h.f))
    }

    @Test fun sourceCounterMismatchBlocksWithoutRepairingOrReleasingOwnership() = runTest {
        for (count in listOf(-1L, 0L, Long.MAX_VALUE)) {
            val h = Harness(); val source = h.f.metadata.sources.getValue(h.f.source.value)
            h.f.metadata.sources[source.sourceId] = source.copy(refCount = count); val before = metadataState(h.f)
            val report = h.audit()
            assertEquals(2L, report.inspected); assertEquals(1L, report.blocked); assertEquals(0L, report.completed)
            val issue = report.safeIssues.single(); assertEquals(source.sourceId, issue.operationId); assertEquals(RecoveryAction.REVIEW_INTEGRITY, issue.action)
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, issue.error!!.code); assertFalse(issue.error.retryable)
            assertEquals(before, metadataState(h.f))
        }
    }

    @Test fun blobCounterCountsRetainedConversionSourcesNotActivePages() = runTest {
        val h = Harness(); val original = h.f.metadata.sources.getValue(h.f.source.value); val id = UUID.randomUUID().toString()
        h.f.metadata.sources[id] = original.copy(sourceId = id, conversionFingerprint = "srgb-v2", state = "QUARANTINED", refCount = 0)
        val failed = h.audit(); assertEquals(3L, failed.inspected); assertEquals(1L, failed.blocked); assertEquals(original.sourceBlobId, failed.safeIssues.single().operationId)
        h.f.metadata.blobs[original.sourceBlobId] = h.f.metadata.blobs.getValue(original.sourceBlobId).copy(sourceCount = 2)
        assertEquals(0L, h.audit().blocked)
    }

    @Test fun maskCounterIncludesRecipeAssociationsAndIndependentUndoPins() = runTest {
        val h = Harness(); val mask = h.f.protectionMask(h.f.pageIds[0], 1)
        assertTrue(h.f.repository.saveRecipe(h.f.pageIds[0], 1, h.f.turned(1)) is SaveRecipeResult.Saved)
        assertEquals(3L, h.f.metadata.masks.getValue(mask).refCount); assertEquals(0L, h.audit().blocked)
        h.f.metadata.masks[mask] = h.f.metadata.masks.getValue(mask).copy(refCount = 2)
        val before = metadataState(h.f); val report = h.audit()
        assertEquals(3L, report.inspected); assertEquals(1L, report.blocked); assertEquals(mask, report.safeIssues.single().operationId); assertEquals(before, metadataState(h.f))
    }

    @Test fun auditVisitsEveryPageOfSourcesWithoutUnboundedMaterialization() = runTest {
        val h = Harness(); val source = h.f.metadata.sources.getValue(h.f.source.value)
        repeat(129) { val id = UUID.randomUUID().toString(); h.f.metadata.sources[id] = source.copy(sourceId = id, conversionFingerprint = "retained-$it", refCount = 0) }
        h.f.metadata.blobs[source.sourceBlobId] = h.f.metadata.blobs.getValue(source.sourceBlobId).copy(sourceCount = 130)
        val before = metadataState(h.f); val report = h.audit()
        assertEquals(131L, report.inspected); assertEquals(0L, report.blocked); assertTrue(report.safeIssues.isEmpty()); assertEquals(before, metadataState(h.f))
    }

    @Test fun boundedIssuesDoNotTruncateActualMismatchCounts() = runTest {
        val h = Harness(); val source = h.f.metadata.sources.getValue(h.f.source.value)
        h.f.metadata.sources[source.sourceId] = source.copy(refCount = 0)
        repeat(200) { val id = UUID.randomUUID().toString(); h.f.metadata.sources[id] = source.copy(sourceId = id, conversionFingerprint = "retained-$it", refCount = 1) }
        h.f.metadata.blobs[source.sourceBlobId] = h.f.metadata.blobs.getValue(source.sourceBlobId).copy(sourceCount = 201)
        val report = h.audit(); assertEquals(202L, report.inspected); assertEquals(201L, report.blocked); assertEquals(128, report.safeIssues.size)
        assertEquals(0L, report.completed); assertEquals(0L, report.resumed); assertEquals(0L, report.quarantined)
    }

    @Test fun listingFailureIsTypedAndCannotMasqueradeAsCleanAuditOrLeakProviderText() = runTest {
        val h = Harness(); h.failure = true; val before = metadataState(h.f)
        try { h.audit(); fail("Failed listing returned a successful reference report") }
        catch (failure: RepositoryFailure) {
            assertEquals(OperationErrorCode.STORAGE_FAILED, failure.error.code); assertTrue(failure.error.retryable)
            assertFalse(failure.message!!.contains("private-provider")); assertNull(failure.error.affectedId)
        }
        assertEquals(before, metadataState(h.f))
    }

    @Test fun corruptOrNonadvancingProjectionFailsSafelyInsteadOfLooping() = runTest {
        for (invalid in listOf(false, true)) {
            val h = Harness(); h.repeat = !invalid; h.invalidId = invalid
            try { h.audit(); fail("Corrupt reference projection was accepted") }
            catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code); assertFalse(failure.error.retryable); assertNull(failure.error.affectedId) }
        }
    }

    @Test fun cancellationDuringListingPropagatesWithoutPublishingOrChangingMetadata() = runTest {
        val h = Harness(); val job = Job(); h.onQuery = { job.cancel() }; val before = metadataState(h.f)
        var canceled = false
        try { withContext(job) { h.audit() } } catch (_: CancellationException) { canceled = true }
        assertTrue(canceled); assertEquals(before, metadataState(h.f))
    }

    private suspend fun assertEmptyIdRejected(kind: String) {
            val h = Harness(); val source = h.f.metadata.sources.getValue(h.f.source.value)
            when (kind) {
                "SOURCE" -> {
                    h.f.metadata.sources[""] = source.copy(sourceId = "", conversionFingerprint = "invalid-id-retained", refCount = 1)
                    h.f.metadata.blobs[source.sourceBlobId] = h.f.metadata.blobs.getValue(source.sourceBlobId).copy(sourceCount = 2)
                }
                "BLOB" -> {
                    val blob = h.f.metadata.blobs.remove(source.sourceBlobId)!!
                    h.f.metadata.blobs[""] = blob.copy(sourceBlobId = "", sourceCount = 0)
                    h.f.metadata.sources[source.sourceId] = source.copy(sourceBlobId = "")
                }
                "MASK" -> {
                    val id = h.f.protectionMask(h.f.pageIds[0], 1, attach = false)
                    val mask = h.f.metadata.masks.remove(id)!!
                    h.f.metadata.masks[""] = mask.copy(maskId = "", refCount = 1)
                }
            }
            // Every nonempty-ID counter remains consistent. The invalid object's mismatch must not vanish behind an initial exclusive empty-string cursor.
            val before = metadataState(h.f)
            try { h.audit(); fail("Empty $kind ID was omitted and a false-clean audit returned") }
            catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code); assertFalse(failure.error.retryable); assertNull(failure.error.affectedId) }
            assertEquals(before, metadataState(h.f))
    }

    @Test fun emptySourceIdCannotBeOmittedFromAnOtherwiseCleanAudit() = runTest { assertEmptyIdRejected("SOURCE") }
    @Test fun emptyBlobIdCannotBeOmittedFromAnOtherwiseCleanAudit() = runTest { assertEmptyIdRejected("BLOB") }
    @Test fun emptyMaskIdCannotBeOmittedFromAnOtherwiseCleanAudit() = runTest { assertEmptyIdRejected("MASK") }

    @Test fun retainedDuplicateHistoryAndMaskPinsRemainValidAfterMembershipUndo() = runTest {
        val h = Harness(); h.f.protectionMask(h.f.pageIds[0], 1)
        val duplicated = h.f.repository.duplicatePage(h.f.pageIds[0], 1) as DocumentMutationResult.Applied
        assertTrue(h.f.repository.saveRecipe(duplicated.affectedPageIds.single(), 1, h.f.turned(2)) is SaveRecipeResult.Saved)
        assertTrue(h.f.repository.undoDocumentEdit(checkNotNull(duplicated.undoId), 3) is DocumentMutationResult.Applied)
        val before = metadataState(h.f); val report = h.audit(true)
        assertEquals(RecoveryReport(7, 0, 0, 0, 0, emptyList()), report)
        assertEquals(report, h.audit(true)); assertEquals(before, metadataState(h.f))
    }

    @Test fun missingPagePinCannotHideBehindARebalancedSourceCounter() = runTest {
        val h = Harness(); val page = h.f.pageIds[0].value
        h.f.metadata.references.remove(Triple("PAGE", page, h.f.source.value))
        h.f.metadata.sources[h.f.source.value] = h.f.metadata.sources.getValue(h.f.source.value).copy(refCount = 3)
        assertEquals(0L, h.audit().blocked); val before = metadataState(h.f); val report = h.audit(true)
        assertEquals(1L, report.blocked); assertEquals(page, report.safeIssues.single().operationId)
        assertEquals("PAGE_SOURCE_PIN", report.safeIssues.single().state); assertEquals(before, metadataState(h.f))
    }

    @Test fun missingOldHistoryPinIsReportedEvenWhenTheCurrentRevisionRemainsPinned() = runTest {
        val h = Harness(); val page = h.f.pageIds[0].value
        assertTrue(h.f.repository.saveRecipe(h.f.pageIds[0], 1, h.f.turned(1)) is SaveRecipeResult.Saved)
        h.f.metadata.references.remove(Triple("RECIPE_HISTORY", "recipe-v1/$page/1", h.f.source.value))
        h.f.metadata.sources[h.f.source.value] = h.f.metadata.sources.getValue(h.f.source.value).copy(refCount = 5)
        assertEquals(0L, h.audit().blocked); val before = metadataState(h.f); val report = h.audit(true)
        assertEquals(1L, report.blocked); assertEquals(page, report.safeIssues.single().operationId)
        assertEquals("RECIPE_SOURCE_PINS", report.safeIssues.single().state); assertEquals(2L, h.f.metadata.pages.getValue(page).currentRecipeRevision)
        assertEquals(before, metadataState(h.f))
    }

    @Test fun aPinToAnotherSourceDoesNotSatisfyThePagesRequiredBinding() = runTest {
        val h = Harness(); val page = h.f.pageIds[0].value; val old = h.f.metadata.sources.getValue(h.f.source.value); val id = UUID.randomUUID().toString()
        h.f.metadata.sources[id] = old.copy(sourceId = id, conversionFingerprint = "independent-v2", refCount = 1)
        h.f.metadata.sources[old.sourceId] = old.copy(refCount = 3)
        h.f.metadata.blobs[old.sourceBlobId] = h.f.metadata.blobs.getValue(old.sourceBlobId).copy(sourceCount = 2)
        val ref = h.f.metadata.references.remove(Triple("PAGE", page, old.sourceId))!!
        h.f.metadata.references[Triple("PAGE", page, id)] = ref.copy(sourceId = id)
        assertEquals(0L, h.audit().blocked); val before = metadataState(h.f); val report = h.audit(true)
        assertEquals(1L, report.blocked); assertEquals(page, report.safeIssues.single().operationId); assertEquals(before, metadataState(h.f))
    }

    @Test fun missingMaskSourcePinIsIndependentOfItsCorrectRecipeMaskRefcount() = runTest {
        val h = Harness(); val mask = h.f.protectionMask(h.f.pageIds[0], 1)
        h.f.metadata.references.remove(Triple("MASK", mask, h.f.source.value))
        h.f.metadata.sources[h.f.source.value] = h.f.metadata.sources.getValue(h.f.source.value).copy(refCount = 4)
        assertEquals(0L, h.audit().blocked); val before = metadataState(h.f); val report = h.audit(true)
        assertEquals(1L, report.blocked); assertEquals(mask, report.safeIssues.single().operationId)
        assertEquals("MASK_SOURCE_PIN", report.safeIssues.single().state); assertEquals(before, metadataState(h.f))
    }

    @Test fun removedPagesStillRequireTheirOwnSourcePinUntilExplicitFinalization() = runTest {
        val h = Harness(); val page = h.f.pageIds[0].value
        assertTrue(h.f.repository.removePage(h.f.pageIds[0], 1) is DocumentMutationResult.Applied)
        h.f.metadata.references.remove(Triple("PAGE", page, h.f.source.value))
        h.f.metadata.sources[h.f.source.value] = h.f.metadata.sources.getValue(h.f.source.value).copy(refCount = 4)
        assertEquals(0L, h.audit().blocked); val before = metadataState(h.f); val report = h.audit(true)
        assertEquals(1L, report.blocked); assertEquals(page, report.safeIssues.single().operationId)
        assertEquals("REMOVED", h.f.metadata.pages.getValue(page).state); assertEquals(before, metadataState(h.f))
    }
}
