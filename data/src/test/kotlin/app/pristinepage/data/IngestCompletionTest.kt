package app.pristinepage.data

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Real private staging/seal/move/readback plus real metadata commands; only Android rows/transactions and normalized inspection are substituted. Synthetic bytes are not claimed to be a decodable PNG, and preparation of MOVED/RUNNING fixtures is not worker/admission/recovery evidence. */
class IngestCompletionTest {
    internal class Fixture {
        val db = IngestIntentTest.Fixture()
        private val parent = File(System.getProperty("user.dir"), "build/test-source-files").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "ingest-completion-").toFile()
        val files = SourceFiles(root)
        val commands: IngestCompletion
        val bytes = byteArrayOf(1, 2, 3, 4)
        init {
            val type = try { Class.forName("app.pristinepage.data.repository.RoomIngestCompletion") }
            catch (error: ClassNotFoundException) { throw AssertionError("Verified original-file and joint page commit is absent", error) }
            commands = try { type.constructors.single { it.parameterCount == 9 }.newInstance(db.metadata, db, db, db, db, files, { db.now }, { db.now }, Dispatchers.Unconfined) as IngestCompletion }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        fun ready(child: ImportSessionId = db.child): Pair<IngestJournalIntent, JobLease> {
            val intent = db.commands.prepare(child, db.sessions[child.value]!!.uriText!!)
            val staged = files.stage(intent.transactionId.value, intent.sourceBlobId.value, ByteArrayInputStream(bytes), 100)
            val inspection = SourceInspection("image/png", PixelSize(3, 2), PixelSize(3, 2), 1, null, "synthetic-v1", "a".repeat(64))
            val seal = files.seal(staged, IngestJournalCodec.encodeSeal(IngestJournalSeal(intent, inspection, staged.byteSha256, staged.byteCount)))
            files.move(seal)
            val journal = db.files[intent.transactionId.value]!!
            db.files[journal.transactionId] = journal.copy(state = "MOVED", sealSha256 = seal.sealSha256, expectedByteSha256 = staged.byteSha256, expectedByteCount = staged.byteCount)
            val lease = JobLease(intent.jobId, UUID.randomUUID().toString(), 1)
            db.jobs[lease.jobId.value] = db.jobs[lease.jobId.value]!!.copy(state = "RUNNING", attempt = 1, leaseOwner = lease.owner, leaseUntil = 1100, leaseGeneration = 1)
            return intent to lease
        }
    }

    @Test fun verifiedFinalBytesCommitOneIndependentPageAndAllReferencesTogether() = runTest {
        val f = Fixture(); val (intent, lease) = f.ready()
        val result = f.commands.commit(intent, lease) as IngestResult.Committed
        assertEquals(f.db.child, result.sessionId); assertEquals(f.db.document, result.documentId); assertEquals(f.db.page, result.pageId)
        assertEquals(2L, result.documentRevision); assertEquals(1, f.db.catalog.pages.size)
        assertEquals(1, f.db.catalog.sources.size); assertEquals(1, f.db.blobs.size); assertEquals(2, f.db.catalog.references.size)
        assertEquals(2L, f.db.catalog.sources[intent.sourceId.value]!!.refCount)
        assertEquals("COMMITTED", f.db.files[intent.transactionId.value]!!.state)
        assertEquals("SUCCEEDED", f.db.jobs[intent.jobId.value]!!.state)
        assertEquals("COMMITTED", f.db.sessions[f.db.child.value]!!.state); assertNull(f.db.sessions[f.db.child.value]!!.uriText)
        assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
    }

    @Test fun repeatedCommittedReplayKeepsItsOriginalResultWithoutNewPagesOrRevisions() = runTest {
        val f = Fixture(); val (intent, lease) = f.ready()
        val first = f.commands.commit(intent, lease) as IngestResult.Committed
        f.db.catalog.documents[f.db.document.value] = f.db.catalog.documents[f.db.document.value]!!.copy(documentRevision = 9)
        repeat(2) { assertEquals(first, f.commands.commit(intent, lease)) }
        assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.db.catalog.references.size)
        assertEquals(9L, f.db.catalog.documents[f.db.document.value]!!.documentRevision)
    }

    @Test fun canceledDestinationCannotBeResurrectedByAValidMovedFile() = runTest {
        val f = Fixture(); val (intent, lease) = f.ready()
        f.db.catalog.documents[f.db.document.value] = f.db.catalog.documents[f.db.document.value]!!.copy(lifecycle = "TOMBSTONED")
        assertTrue(f.commands.commit(intent, lease) is IngestResult.Rejected)
        assertTrue(f.db.catalog.pages.isEmpty()); assertTrue(f.db.catalog.sources.isEmpty())
        assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
        assertEquals("MOVED", f.db.files[intent.transactionId.value]!!.state)
    }

    @Test fun recipeWriteFailureRollsBackSourcePagePinsSessionAndJournalButRetainsBytes() = runTest {
        val f = Fixture(); val (intent, lease) = f.ready()
        f.db.catalog.failRecipeInsertAt = 1
        assertTrue(f.commands.commit(intent, lease) is IngestResult.Pending)
        assertTrue(f.db.catalog.pages.isEmpty()); assertTrue(f.db.catalog.sources.isEmpty()); assertTrue(f.db.blobs.isEmpty()); assertTrue(f.db.catalog.references.isEmpty())
        assertEquals("INGESTING", f.db.sessions[f.db.child.value]!!.state); assertEquals("MOVED", f.db.files[intent.transactionId.value]!!.state)
        assertEquals("RUNNING", f.db.jobs[intent.jobId.value]!!.state)
        assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
        f.db.catalog.failRecipeInsertAt = null
        assertTrue(f.commands.commit(intent, lease) is IngestResult.Committed)
    }

    @Test fun missingOrChangedFinalOriginalCannotPublishAPage() = runTest {
        val f = Fixture(); val (intent, lease) = f.ready()
        // Modify only this operation-owned synthetic test file; originals elsewhere are not touched or deleted.
        f.files.finalFile(intent.sourceBlobId.value).writeBytes(byteArrayOf(9, 9, 9, 9))
        val result = f.commands.commit(intent, lease) as IngestResult.Rejected
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, result.error.code)
        assertTrue(f.db.catalog.pages.isEmpty()); assertTrue(f.db.catalog.sources.isEmpty())
    }

    @Test fun completionOrderCannotReorderTheReservedSelection() = runTest {
        val f = Fixture(); val second = ImportSessionId(UUID.randomUUID().toString()); val secondPage = PageId(UUID.randomUUID().toString())
        f.db.sessions[second.value] = f.db.sessions[f.db.child.value]!!.copy(sessionId = second.value, selectionOrdinal = 1, plannedPageId = secondPage.value, uriText = "content://fixture.documents/selected/two")
        val first = f.ready(); val last = f.ready(second)
        assertTrue(f.commands.commit(last.first, last.second) is IngestResult.Committed)
        assertTrue(f.commands.commit(first.first, first.second) is IngestResult.Committed)
        assertEquals(listOf(f.db.page.value, secondPage.value), f.db.catalog.activePages(f.db.document.value).map { it.pageId })
        assertEquals(listOf(0, 1), f.db.catalog.activePages(f.db.document.value).map { it.ordinal })
        assertEquals(3L, f.db.catalog.documents[f.db.document.value]!!.documentRevision)
    }

    @Test fun staleLeaseCannotPublishEvenThoughItsPrivateFileIsValid() = runTest {
        val f = Fixture(); val (intent, lease) = f.ready()
        f.db.now = 1100
        assertTrue(f.commands.commit(intent, lease) is IngestResult.Pending)
        assertTrue(f.db.catalog.pages.isEmpty()); assertEquals("MOVED", f.db.files[intent.transactionId.value]!!.state)
    }

    @Test fun committedReplayRejectsChangedNormalizedSourceBinding() = runTest {
        for (field in listOf("width", "height", "conversion", "profile", "orientation")) {
            val f = Fixture(); val (intent, lease) = f.ready()
            assertTrue(f.commands.commit(intent, lease) is IngestResult.Committed)
            val source = f.db.catalog.sources[intent.sourceId.value]!!
            f.db.catalog.sources[source.sourceId] = when (field) {
                "width" -> source.copy(width = 4)
                "height" -> source.copy(height = 4)
                "conversion" -> source.copy(conversionFingerprint = "different-v1")
                "profile" -> source.copy(profileSha256 = "b".repeat(64))
                else -> source.copy(exifOrientation = 2)
            }
            val result = f.commands.commit(intent, lease) as? IngestResult.Rejected
            assertNotNull("Contradictory source $field was certified", result)
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, result!!.error.code)
            assertEquals(1, f.db.catalog.pages.size); assertEquals(2L, f.db.catalog.documents[f.db.document.value]!!.documentRevision)
        }
    }

    @Test fun committedReplayRejectsChangedEncodedIdentityLocationOrTransaction() = runTest {
        for (field in listOf("path", "transaction", "mime", "width", "height")) {
            val f = Fixture(); val (intent, lease) = f.ready()
            assertTrue(f.commands.commit(intent, lease) is IngestResult.Committed)
            val blob = f.db.blobs[intent.sourceBlobId.value]!!
            f.db.blobs[blob.sourceBlobId] = when (field) {
                "path" -> blob.copy(relativePath = "sources/${UUID.randomUUID()}/original.bin")
                "transaction" -> blob.copy(ingestTransactionId = UUID.randomUUID().toString())
                "mime" -> blob.copy(mime = "image/jpeg")
                "width" -> blob.copy(encodedWidth = 4)
                else -> blob.copy(encodedHeight = 4)
            }
            val result = f.commands.commit(intent, lease) as? IngestResult.Rejected
            assertNotNull("Contradictory blob $field was certified", result)
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, result!!.error.code)
            assertArrayEquals(f.bytes, f.files.finalFile(intent.sourceBlobId.value).readBytes())
        }
    }

    @Test fun committedReplayRejectsMissingOrChangedCanonicalJobProvenance() = runTest {
        for (field in listOf("missing", "input", "hash", "key", "kind")) {
            val f = Fixture(); val (intent, lease) = f.ready()
            assertTrue(f.commands.commit(intent, lease) is IngestResult.Committed)
            val job = f.db.jobs[intent.jobId.value]!!
            if (field == "missing") f.db.jobs.remove(job.jobId)
            else f.db.jobs[job.jobId] = when (field) {
                "input" -> job.copy(input = byteArrayOf(9))
                "hash" -> job.copy(canonicalInputSha256 = "b".repeat(64))
                "key" -> job.copy(idempotenceKey = "other-v1")
                else -> job.copy(kind = "PAGE_RENDER")
            }
            val result = f.commands.commit(intent, lease) as? IngestResult.Rejected
            assertNotNull("Contradictory job $field was certified", result)
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, result!!.error.code)
            assertEquals(1, f.db.catalog.pages.size)
        }
    }

    @Test fun committedReplayRejectsReboundChildOrParentOwnership() = runTest {
        for (field in listOf("transaction", "page", "parent-document")) {
            val f = Fixture(); val (intent, lease) = f.ready()
            assertTrue(f.commands.commit(intent, lease) is IngestResult.Committed)
            val child = f.db.sessions[f.db.child.value]!!
            when (field) {
                "transaction" -> f.db.sessions[child.sessionId] = child.copy(transactionId = UUID.randomUUID().toString())
                "page" -> f.db.sessions[child.sessionId] = child.copy(plannedPageId = UUID.randomUUID().toString())
                else -> f.db.sessions[f.db.parent.value] = f.db.sessions[f.db.parent.value]!!.copy(documentId = UUID.randomUUID().toString())
            }
            val result = f.commands.commit(intent, lease) as? IngestResult.Rejected
            assertNotNull("Rebound $field ownership was certified", result)
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, result!!.error.code)
        }
    }

    @Test fun committedReplayAllowsLegitimateLaterRecipeAndReferenceChanges() = runTest {
        val f = Fixture(); val (intent, lease) = f.ready()
        val first = f.commands.commit(intent, lease) as IngestResult.Committed
        val repository = RoomDocumentRepository(f.db.metadata, RecipeAdmission { _, _ -> null }, { f.db.now }, Dispatchers.Unconfined)
        val recipe = RecipeCodec.decode(f.db.catalog.recipes[intent.pageId.value to 1L]!!.canonicalRecipe)
        assertTrue(repository.saveRecipe(intent.pageId, 1, recipe.copy(geometry = recipe.geometry.withOutputQuarterTurns(1))) is SaveRecipeResult.Saved)
        assertTrue(f.db.catalog.sources[intent.sourceId.value]!!.refCount > 2)
        assertEquals(first, f.commands.commit(intent, lease))
        assertEquals(3L, f.db.catalog.documents[f.db.document.value]!!.documentRevision)
        assertEquals(2L, f.db.catalog.pages[intent.pageId.value]!!.currentRecipeRevision)
    }

    @Test fun changedRunningJobHashCannotPublishOrCompleteTheOperation() = runTest {
        val f = Fixture(); val (intent, lease) = f.ready()
        f.db.jobs[intent.jobId.value] = f.db.jobs[intent.jobId.value]!!.copy(canonicalInputSha256 = "b".repeat(64))
        val result = f.commands.commit(intent, lease) as? IngestResult.Rejected
        assertNotNull("Changed canonical input hash was accepted", result)
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, result!!.error.code)
        assertTrue(f.db.catalog.pages.isEmpty()); assertTrue(f.db.catalog.sources.isEmpty())
        assertEquals("MOVED", f.db.files[intent.transactionId.value]!!.state); assertEquals("RUNNING", f.db.jobs[intent.jobId.value]!!.state)
    }

    @Test fun terminalFileOutcomeDoesNotBecomePendingAndPreservesItsSafeError() = runTest {
        for ((state, code) in listOf("CANCELED" to OperationErrorCode.CANCELED, "FAILED" to OperationErrorCode.STORAGE_FAILED, "QUARANTINED" to OperationErrorCode.INTEGRITY_FAILED)) {
            val f = Fixture(); val (intent, lease) = f.ready()
            val row = f.db.files[intent.transactionId.value]!!
            f.db.files[row.transactionId] = row.copy(state = state, errorCode = code.name)
            val result = f.commands.commit(intent, lease) as? IngestResult.Rejected
            assertNotNull("Terminal $state was called recoverable pending", result)
            assertEquals(code, result!!.error.code); assertFalse(result.error.retryable)
            assertTrue(f.db.catalog.pages.isEmpty()); assertEquals(state, f.db.files[row.transactionId]!!.state)
        }
    }

    @Test fun canceledOrFailedJobCannotBecomeAnUnexplainedPendingResult() = runTest {
        for ((state, code) in listOf("CANCELED" to OperationErrorCode.CANCELED, "FAILED" to OperationErrorCode.PERMISSION_LOST)) {
            val f = Fixture(); val (intent, lease) = f.ready()
            f.db.jobs[intent.jobId.value] = f.db.jobs[intent.jobId.value]!!.copy(state = state, errorCode = code.name)
            val result = f.commands.commit(intent, lease) as? IngestResult.Rejected
            assertNotNull("Terminal job $state became unexplained pending", result)
            assertEquals(code, result!!.error.code); assertTrue(f.db.catalog.pages.isEmpty())
        }
    }

    @Test fun canceledItemOrBatchIsDistinctFromAnExpiredSession() = runTest {
        for (batch in listOf(false, true)) {
            val f = Fixture(); val (intent, lease) = f.ready()
            val id = if (batch) f.db.parent.value else f.db.child.value
            f.db.sessions[id] = f.db.sessions[id]!!.copy(state = "CANCELED", errorCode = "CANCELED")
            val result = f.commands.commit(intent, lease) as IngestResult.Rejected
            assertEquals(OperationErrorCode.CANCELED, result.error.code)
            assertTrue(f.db.catalog.pages.isEmpty()); assertEquals("MOVED", f.db.files[intent.transactionId.value]!!.state)
        }
    }
}
