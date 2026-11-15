package app.pristinepage.data

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Real source-store orchestration/private files/journal/lease/owner/metadata commands. Only provider streams, normalized inspection and Android DB storage are substituted; this is not actual decoder/provider/device/process-death acceptance. */
class SourceStoreTest {
    internal class Fixture(directorySync: DirectorySync? = null, enableRetry: Boolean = false) {
        val db = IngestIntentTest.Fixture()
        val bytes = byteArrayOf(1, 2, 3, 4)
        private val parent = File(System.getProperty("user.dir"), "build/test-source-files").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "source-store-").toFile()
        val files = if (directorySync == null) SourceFiles(root) else SourceFiles(root, directorySync)
        val owners = LocalOperationOwners()
        var opened = 0; var closed = 0; var inspected = 0
        var inspectionFailure: RepositoryFailure? = null
        var duringInspection: () -> Unit = {}
        var beforeReplay: suspend (IngestJournalIntent) -> Unit = {}
        var beforePreparation: () -> Unit = {}
        var input: () -> InputStream = {
            object : ByteArrayInputStream(bytes) { override fun close() { closed++; super.close() } }
        }
        val store: BoundSourceIngest
        init {
            val type = try { Class.forName("app.pristinepage.data.repository.RoomSourceStore") }
            catch (error: ClassNotFoundException) { throw AssertionError("Admitted session-bound SourceStore is absent", error) }
            val phasesType = try { Class.forName("app.pristinepage.data.repository.RoomIngestPhases") }
            catch (error: ClassNotFoundException) { throw AssertionError("Fenced ingest phase transitions are absent", error) }
            val phases = try { phasesType.constructors.single { it.parameterCount == 7 }.newInstance(db.metadata, db, db, db, db, { db.now }, { db.now }) as IngestPhases }
            catch (error: InvocationTargetException) { throw error.targetException }
            val provider = SourceInput { reference ->
                assertFalse(db.catalog.inTransaction); assertEquals(db.uri, reference)
                assertEquals(1, db.files.values.count { it.state == "COPYING" })
                opened++; input()
            }
            val inspector = SourceInspector { file, hash ->
                assertFalse(db.catalog.inTransaction); assertEquals(opened, closed)
                assertEquals(1, db.files.values.count { it.state == "COPYING" })
                assertArrayEquals(bytes, file.readBytes()); assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", hash)
                inspected++; inspectionFailure?.let { throw it }; duringInspection()
                SourceInspection("image/png", PixelSize(3, 2), PixelSize(3, 2), 1, null, "synthetic-v1", "a".repeat(64))
            }
            val completion = RoomIngestCompletion(db.metadata, db, db, db, db, files, { db.now }, { db.now }, Dispatchers.Unconfined)
            val replay = object : IngestReplay { override suspend fun replay(intent: IngestJournalIntent): IngestResult { beforeReplay(intent); return completion.replay(intent) } }
            val preparation = object : IngestPreparation { override fun prepare(sessionId: ImportSessionId, boundUriText: String): IngestJournalIntent { beforePreparation(); return db.commands.prepare(sessionId, boundUriText) } }
            val lookup = RoomIngestLookup(db.metadata, db, db, db)
            val leases = RoomJobLeases(db.metadata, db, owners, { db.now }, { db.now }, Dispatchers.Unconfined)
            val restart = if (enableRetry) {
                val restartType = try { Class.forName("app.pristinepage.data.repository.RoomIngestRestarts") }
                catch (failure: ClassNotFoundException) { throw AssertionError("Admitted unsealed-file retry consumption is absent", failure) }
                try { restartType.constructors.single { it.parameterCount == 7 }.newInstance(db.metadata, db, db, files, owners, RoomIngestAttempts(db.metadata, db, db, db, db, owners, { db.now }), Dispatchers.Unconfined) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            } else null
            store = try { if (enableRetry) type.constructors.single { it.parameterCount == 12 }.newInstance(files, provider, inspector, preparation, lookup, replay, completion, phases, leases, owners, Dispatchers.Unconfined, restart) as BoundSourceIngest else type.constructors.single { it.parameterCount == 11 }.newInstance(files, provider, inspector, preparation, lookup, replay, completion, phases, leases, owners, Dispatchers.Unconfined) as BoundSourceIngest }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
    }

    @Test fun boundProviderBytesAreClosedInspectedSealedMovedAndCommittedOnce() = runTest {
        val f = Fixture()
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Committed
        assertEquals(f.db.page, result.pageId); assertEquals(f.db.document, result.documentId)
        assertEquals(1, f.opened); assertEquals(1, f.closed); assertEquals(1, f.inspected)
        assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.db.catalog.references.size)
        val row = f.db.files.values.single()
        assertEquals("COMMITTED", row.state); assertEquals("SUCCEEDED", f.db.jobs.values.single().state)
        assertArrayEquals(f.bytes, File(f.root, row.finalPath!!).readBytes())
        assertNull(f.db.sessions[f.db.child.value]!!.uriText)
    }

    @Test fun repeatedCallbackUsesCommittedPrivateBytesWithoutReopeningTheProvider() = runTest {
        val f = Fixture()
        val first = f.store.ingestBoundProvider(f.db.uri, f.db.child)
        f.input = { throw SecurityException("synthetic provider no longer readable") }
        repeat(2) { assertEquals(first, f.store.ingestBoundProvider(f.db.uri, f.db.child)) }
        assertEquals(1, f.opened); assertEquals(1, f.closed); assertEquals(1, f.db.catalog.pages.size)
        assertEquals(2L, f.db.catalog.documents[f.db.document.value]!!.documentRevision)
    }

    @Test fun revokedMidCopyClosesTheDescriptorAndCannotCommitAPage() = runTest {
        val f = Fixture()
        f.input = {
            object : InputStream() {
                private var emitted = false
                override fun read(): Int { if (emitted) throw SecurityException("synthetic private provider refusal"); emitted = true; return 1 }
                override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = read().toByte(); return 1 }
                override fun close() { f.closed++ }
            }
        }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Rejected
        assertEquals(OperationErrorCode.PERMISSION_LOST, result.error.code)
        assertEquals(1, f.closed); assertEquals(0, f.inspected)
        assertTrue(f.db.catalog.pages.isEmpty()); assertTrue(f.db.catalog.sources.isEmpty())
        assertEquals("PERMISSION_LOST", f.db.files.values.single().errorCode)
        assertFalse(File(f.root, f.db.files.values.single().finalPath!!).exists())
    }

    @Test fun inspectorRefusalPreservesClosedStagingButCannotPublishAnOriginal() = runTest {
        val f = Fixture()
        f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.UNSUPPORTED_FORMAT, false, "synthetic_unsupported_input", f.db.child.value))
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Rejected
        assertEquals(OperationErrorCode.UNSUPPORTED_FORMAT, result.error.code)
        assertEquals(1, f.closed); assertTrue(f.db.catalog.pages.isEmpty())
        assertEquals("FAILED", f.db.files.values.single().state)
        assertTrue(File(f.root, f.db.files.values.single().stagingPath!!).isFile)
    }

    @Test fun copyCancellationClosesResourcesAndPersistsADistinctCanceledOutcome() = runTest {
        val f = Fixture()
        f.input = { object : InputStream() { override fun read(): Int = throw CancellationException("synthetic canceled read"); override fun close() { f.closed++ } } }
        try { f.store.ingestBoundProvider(f.db.uri, f.db.child); fail("Cancellation became success") }
        catch (_: CancellationException) { }
        assertEquals(1, f.closed); assertTrue(f.db.catalog.pages.isEmpty())
        assertEquals("CANCELED", f.db.files.values.single().state); assertEquals("CANCELED", f.db.jobs.values.single().state)
    }

    @Test fun lostSealPhaseWriteDoesNotMoveOrPublishAndNeverOverwritesOnRetry() = runTest {
        val f = Fixture(); f.db.rejectPhaseState = "SEALED"
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        assertTrue(f.db.catalog.pages.isEmpty()); assertEquals("COPYING", f.db.files.values.single().state)
        assertFalse(File(f.root, f.db.files.values.single().finalPath!!).exists())
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        assertEquals(1, f.opened); assertEquals(1, f.closed)
    }

    @Test fun missingProviderBytesAreUnavailableNotAnInternalDiskRetry() = runTest {
        val f = Fixture(); f.input = { throw FileNotFoundException("synthetic private provider location") }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as? IngestResult.Rejected
        assertNotNull("Missing external content became an internal storage retry", result)
        assertEquals(OperationErrorCode.SOURCE_UNAVAILABLE, result!!.error.code)
        assertFalse(result.error.safeMessageKey.contains("private")); assertTrue(f.db.catalog.pages.isEmpty())
        assertEquals(0, f.closed); assertEquals(0, f.inspected)
    }

    @Test fun unexpectedInspectorFailureIsSafeAndLeavesNoPublishedOriginal() = runTest {
        val f = Fixture(); f.duringInspection = { throw IllegalStateException("synthetic private decoder details") }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Rejected
        assertEquals(OperationErrorCode.PROCESSING_FAILED, result.error.code)
        assertFalse(result.error.safeMessageKey.contains("private")); assertEquals(1, f.closed)
        assertTrue(f.db.catalog.pages.isEmpty()); assertEquals("FAILED", f.db.files.values.single().state)
    }

    @Test fun actualJobCancellationDuringReadClosesBeforeOwnerEndsAndNeverPublishes() = runTest {
        val f = Fixture(); lateinit var task: Job; var owner: String? = null
        f.input = {
            object : InputStream() {
                override fun read(): Int { task.cancel(); return 1 }
                override fun close() {
                    f.closed++
                    owner = f.db.jobs.values.single().leaseOwner
                    assertEquals(LeaseOwnerState.ALIVE, f.owners.state(checkNotNull(owner)))
                }
            }
        }
        task = launch { f.store.ingestBoundProvider(f.db.uri, f.db.child); fail("Actual cancellation became success") }
        task.join()
        assertTrue(task.isCancelled); assertEquals(1, f.closed); assertEquals(0, f.inspected)
        assertEquals(LeaseOwnerState.ENDED, f.owners.state(checkNotNull(owner)))
        assertEquals("CANCELED", f.db.jobs.values.single().state); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun expiredLeaseAfterInspectionCannotMoveOrPublishItsPrivatelySealedBytes() = runTest {
        val f = Fixture(); f.duringInspection = { f.db.now = 301001 }
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        val row = f.db.files.values.single()
        assertEquals("COPYING", row.state); assertFalse(File(f.root, row.finalPath!!).exists())
        assertTrue(File(f.root, row.stagingPath!!).isFile); assertTrue(f.db.catalog.pages.isEmpty())
        assertEquals(1, f.closed)
    }

    @Test fun lostCopyingCasDoesNotOpenAnyProviderOrStageFile() = runTest {
        val f = Fixture(); f.db.rejectPhaseState = "COPYING"
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        assertEquals(0, f.opened); assertEquals(0, f.closed); assertEquals(0, f.inspected)
        val row = f.db.files.values.single()
        assertEquals("INTENT", row.state); assertFalse(File(f.root, row.stagingPath!!).exists())
        assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun zeroByteSourceIsRejectedBeforeInspectionWithoutPublishing() = runTest {
        val f = Fixture()
        f.input = { object : ByteArrayInputStream(byteArrayOf()) { override fun close() { f.closed++; super.close() } } }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Rejected
        assertEquals(OperationErrorCode.INVALID_INPUT, result.error.code)
        assertEquals(1, f.closed); assertEquals(0, f.inspected); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun encodedByteLimitIsEnforcedDuringStreamingBeforeDecodeOrFinalPublication() = runTest {
        val f = Fixture()
        f.input = {
            object : InputStream() {
                private var remaining = 104857601L
                override fun read(): Int { if (remaining == 0L) return -1; remaining--; return 1 }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining == 0L) return -1
                    val n = minOf(len.toLong(), remaining).toInt(); b.fill(1, off, off + n); remaining -= n
                    return n
                }
                override fun close() { f.closed++ }
            }
        }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Rejected
        assertEquals(OperationErrorCode.RESOURCE_LIMIT, result.error.code)
        assertEquals(1, f.closed); assertEquals(0, f.inspected)
        val row = f.db.files.values.single()
        assertTrue(File(f.root, row.stagingPath!!).length() <= 104857600L)
        assertFalse(File(f.root, row.finalPath!!).exists()); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun terminalFailureSettlesItsItemAndAllowsANewImportWithoutRecovery() = runTest {
        val f = Fixture()
        f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.UNSUPPORTED_FORMAT, false, "synthetic_unsupported_input", f.db.child.value))
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Rejected)
        assertEquals("FAILED", f.db.sessions[f.db.child.value]!!.state)
        assertNull(f.db.sessions[f.db.child.value]!!.uriText)
        assertEquals("FAILED", f.db.sessions[f.db.parent.value]!!.state)
        val sessions = RoomImportSessions(f.db.metadata, f.db, { f.db.now }, Dispatchers.Unconfined)
        assertNotEquals(f.db.parent, sessions.beginImport(f.db.document, 1, null).parentId)
        assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun mixedFailedAndCommittedChildrenSettleParentWithoutLosingEitherOutcome() = runTest {
        val f = Fixture(); val second = ImportSessionId(UUID.randomUUID().toString())
        f.db.sessions[second.value] = f.db.sessions[f.db.child.value]!!.copy(sessionId = second.value, selectionOrdinal = 1, plannedPageId = UUID.randomUUID().toString())
        f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.UNSUPPORTED_FORMAT, false, "synthetic_unsupported_input", f.db.child.value))
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Rejected)
        f.inspectionFailure = null
        assertTrue(f.store.ingestBoundProvider(f.db.uri, second) is IngestResult.Committed)
        assertEquals("FAILED", f.db.sessions[f.db.child.value]!!.state)
        assertEquals("COMMITTED", f.db.sessions[second.value]!!.state)
        assertEquals("COMMITTED", f.db.sessions[f.db.parent.value]!!.state)
        val sessions = RoomImportSessions(f.db.metadata, f.db, { f.db.now }, Dispatchers.Unconfined)
        assertNotEquals(f.db.parent, sessions.beginImport(f.db.document, 2, null).parentId)
        assertEquals(1, f.db.catalog.pages.size)
    }

    @Test fun providerReadIoIsSourceUnavailableRatherThanPrivateStorageFailure() = runTest {
        val f = Fixture()
        f.input = { object : InputStream() { override fun read(): Int = throw IOException("synthetic private provider read refusal"); override fun close() { f.closed++ } } }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as? IngestResult.Rejected
        assertNotNull("Provider read IO became a disk retry", result)
        assertEquals(OperationErrorCode.SOURCE_UNAVAILABLE, result!!.error.code)
        assertEquals("SOURCE_UNAVAILABLE", f.db.jobs.values.single().errorCode)
        assertEquals(1, f.closed); assertEquals(0, f.inspected); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun providerCloseIoCannotCertifyItsReadBytesOrBecomeADiskFailure() = runTest {
        val f = Fixture()
        f.input = { object : ByteArrayInputStream(f.bytes) { override fun close() { f.closed++; throw IOException("synthetic private provider close failure") } } }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as? IngestResult.Rejected
        assertNotNull("Provider close IO became a disk retry", result)
        assertEquals(OperationErrorCode.SOURCE_UNAVAILABLE, result!!.error.code)
        assertEquals(1, f.closed); assertEquals(0, f.inspected); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun privateDirectorySyncFailureRemainsRetryableStorageAndRetainsItsSession() = runTest {
        val f = Fixture(DirectorySync { throw IOException("synthetic private disk sync failure") })
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        assertEquals("STORAGE_FAILED", f.db.jobs.values.single().errorCode)
        assertEquals("RETRYABLE", f.db.files.values.single().state)
        assertEquals("INGESTING", f.db.sessions[f.db.child.value]!!.state)
        assertEquals(f.db.uri, f.db.sessions[f.db.child.value]!!.uriText)
        assertEquals(1, f.closed); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun unexpectedProviderOpenReadAndCloseFailuresReturnSafeResultsAndCloseOnce() = runTest {
        for (at in listOf("open", "read", "close")) {
            val f = Fixture()
            f.input = {
                if (at == "open") throw IllegalStateException("synthetic private provider failure")
                object : ByteArrayInputStream(f.bytes) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (at == "read") throw IllegalStateException("synthetic private provider failure")
                        return super.read(b, off, len)
                    }
                    override fun close() { f.closed++; if (at == "close") throw IllegalStateException("synthetic private provider failure"); super.close() }
                }
            }
            val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Rejected
            assertEquals(OperationErrorCode.SOURCE_UNAVAILABLE, result.error.code)
            assertFalse(result.error.safeMessageKey.contains("private"))
            assertEquals(if (at == "open") 0 else 1, f.closed)
            assertEquals("SOURCE_UNAVAILABLE", f.db.jobs.values.single().errorCode)
            assertTrue(f.db.catalog.pages.isEmpty())
        }
    }

    @Test fun independentItemCancellationWinsOverALaterProviderRefusal() = runTest {
        val f = Fixture()
        f.input = {
            object : InputStream() {
                override fun read(): Int {
                    val child = f.db.sessions[f.db.child.value]!!
                    f.db.sessions[child.sessionId] = child.copy(state = "CANCELED", errorCode = "CANCELED")
                    throw SecurityException("synthetic provider refusal after cancel")
                }
                override fun close() { f.closed++ }
            }
        }
        val result = f.store.ingestBoundProvider(f.db.uri, f.db.child) as IngestResult.Rejected
        assertEquals(OperationErrorCode.CANCELED, result.error.code)
        assertEquals("CANCELED", f.db.sessions[f.db.child.value]!!.state)
        assertEquals("CANCELED", f.db.jobs.values.single().state)
        assertEquals(1, f.closed); assertTrue(f.db.catalog.pages.isEmpty())
    }
}
