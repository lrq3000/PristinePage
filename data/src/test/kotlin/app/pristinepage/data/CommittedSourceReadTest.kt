package app.pristinepage.data

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Real private-file proof and complete production rows. Only unavailable Room is substituted; these four bytes are intentionally NOT codec acceptance. All unique synthetic files are retained. */
class CommittedSourceReadTest {
    private class Fixture {
        val f = RepositoryFixture()
        val parent = File(System.getProperty("user.dir"), "build/test-committed-sources").apply { mkdirs() }
        private val root = Files.createTempDirectory(parent.toPath(), "source-").toFile().canonicalFile
        var privatePathChecks = 0
            private set
        // Observe the entry to real path resolution before any filesystem probe; keep the real filesystem and SourceFiles proof.
        val files = SourceFiles(object : File(root.path) {
            override fun getCanonicalFile(): File = this
            override fun toPath(): Path {
                privatePathChecks++
                assertFalse("Private path resolution ran inside the metadata transaction", f.metadata.inTransaction)
                return super.toPath()
            }
        })
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source: SourceRow get() = f.metadata.sources.getValue(f.source.value)
        val blob: SourceBlobRow get() = f.metadata.blobs.getValue(source.sourceBlobId)
        var bounded: Boolean? = true
        var projections = 0
        val metadataReads = mutableListOf<String>()
        var onGuard: () -> Unit = {}
        init {
            val stage = files.stage(blob.ingestTransactionId, blob.sourceBlobId, ByteArrayInputStream(bytes), 100)
            files.move(files.seal(stage, byteArrayOf(1)))
            f.metadata.blobs[blob.sourceBlobId] = blob.copy(byteSha256 = stage.byteSha256)
        }
        fun binding() = ExportSourceBinding(source, blob)

        private fun reader(): RoomCommittedSources {
            val queries = CommittedSourceQueries { id ->
                assertTrue(f.metadata.inTransaction)
                assertEquals(f.source.value, id)
                metadataReads += "bounds"
                onGuard(); bounded
            }
            val catalog = object : CatalogQueries by f.metadata {
                override fun source(id: String): SourceRow? { assertTrue(f.metadata.inTransaction); assertEquals(true, bounded); metadataReads += "source"; projections++; return f.metadata.source(id) }
                override fun sourceBlob(id: String): SourceBlobRow? { assertTrue(f.metadata.inTransaction); assertEquals(true, bounded); metadataReads += "blob"; projections++; return f.metadata.sourceBlob(id) }
                override fun document(id: String) = throw AssertionError("Source lookup consulted current document")
                override fun activePages(documentId: String) = throw AssertionError("Source lookup consulted live membership")
                override fun page(id: String) = throw AssertionError("Source lookup consulted current page")
                override fun recipe(pageId: String, revision: Long) = throw AssertionError("Source lookup consulted current recipe")
            }
            val metadata = object : MetadataAccess {
                override val queries = catalog
                override fun <T> transaction(action: () -> T): T = f.metadata.transaction {
                    metadataReads += "transaction"
                    action()
                }
            }
            return RoomCommittedSources(metadata, queries, files, Dispatchers.Unconfined)
        }

        suspend fun load(): CommittedSource = reader().load(f.source)

        suspend fun loadForProducer(expected: ExportSourceBinding): CommittedSource = reader().loadForProducer(f.source, expected)
        fun inventory(): Any = listOf(f.metadata.sources.toMap(), f.metadata.blobs.toMap(), f.metadata.documents.toMap(), f.metadata.pages.toMap(), f.metadata.references.toMap())
    }
    private fun get(value: Any, property: String): Any = value.javaClass.getMethod("get$property").invoke(value)!!
    private suspend fun error(f: Fixture): OperationError = try { f.load(); throw AssertionError("Unverified committed source was accepted") } catch (failure: RepositoryFailure) { failure.error }

    @Test fun verifiedOriginalAndRecordedIdentityLoadWithoutConsultingCurrentPagesOrWriting() = runTest {
        val f = Fixture(); val before = f.inventory(); val result = f.load()
        assertEquals(f.blob.byteSha256, get(result, "SourceByteHash")); assertEquals(4L, get(result, "ByteCount"))
        val info = get(result, "Inspection") as SourceInspection
        assertEquals(PixelSize(3, 2), info.encodedSize); assertEquals(PixelSize(3, 2), info.normalizedSize); assertEquals("a".repeat(64), info.sourcePixelHash); assertEquals("srgb-v1", info.conversionFingerprint)
        assertEquals(f.files.finalFile(f.blob.sourceBlobId), get(result, "File")); assertArrayEquals(f.bytes, (get(result, "File") as File).readBytes())
        assertEquals(2, f.projections); assertEquals(before, f.inventory()); assertFalse(f.f.metadata.inTransaction)
    }

    @Test fun missingSourceFailsBeforeTypedProjectionOrFileWork() = runTest {
        val f = Fixture(); f.bounded = null
        assertEquals(OperationErrorCode.SOURCE_UNAVAILABLE, error(f).code); assertEquals(0, f.projections)
    }

    @Test fun failedRawBoundsCannotReachLossyRoomIntProjection() = runTest {
        val f = Fixture(); f.bounded = false
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(f).code); assertEquals(0, f.projections)
    }

    @Test fun invalidReadyChainPathDimensionsAndOrientationAreNotTrustedEvenByADefectiveGuard() = runTest {
        for (mutation in 0..6) {
            val f = Fixture(); val source = f.source; val blob = f.blob
            when (mutation) {
                0 -> f.f.metadata.sources[source.sourceId] = source.copy(state = "QUARANTINED")
                1 -> f.f.metadata.blobs[blob.sourceBlobId] = blob.copy(state = "DELETING")
                2 -> f.f.metadata.blobs[blob.sourceBlobId] = blob.copy(relativePath = "staging/${UUID.randomUUID()}/original.part")
                3 -> f.f.metadata.blobs[blob.sourceBlobId] = blob.copy(encodedWidth = Int.MAX_VALUE)
                4 -> f.f.metadata.sources[source.sourceId] = source.copy(exifOrientation = 6)
                5 -> f.f.metadata.sources[source.sourceId] = source.copy(pixelSha256 = "not_a_hash")
                else -> f.f.metadata.blobs.remove(blob.sourceBlobId)
            }
            val before = f.inventory(); assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(f).code); assertEquals(before, f.inventory())
        }
    }

    @Test fun changedClosedBytesRefuseWithoutRewritingOriginalOrRecordedIdentity() = runTest {
        val f = Fixture(); val file = f.files.finalFile(f.blob.sourceBlobId); val changed = byteArrayOf(4, 3, 2, 1); file.writeBytes(changed); val before = f.inventory()
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(f).code); assertEquals(before, f.inventory()); assertArrayEquals(changed, file.readBytes())
    }

    @Test fun missingCommittedPayloadIsIntegrityFailureNotASyntheticRecoveredSource() = runTest {
        val f = Fixture(); val old = f.blob; val id = UUID.randomUUID().toString()
        f.f.metadata.blobs.remove(old.sourceBlobId); f.f.metadata.blobs[id] = old.copy(sourceBlobId = id, relativePath = "sources/$id/original.bin"); f.f.metadata.sources[f.source.sourceId] = f.source.copy(sourceBlobId = id)
        val before = f.inventory(); assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(f).code); assertEquals(before, f.inventory()); assertFalse(f.files.finalFile(id).exists())
    }

    @Test fun cancellationFromMetadataIsNotFlattenedIntoStorageOrIntegrityError() = runTest {
        val f = Fixture(); f.onGuard = { throw CancellationException("synthetic cancellation") }; val before = f.inventory()
        try { f.load(); throw AssertionError("Cancellation was swallowed") } catch (_: CancellationException) { /* Required propagation, no newly owned resources to deliver. */ }
        assertEquals(0, f.projections); assertEquals(before, f.inventory())
    }

    @Test fun actualReadIoErrorRemainsRetryableStorageFailureWithSafePayload() = runTest {
        val f = Fixture(); f.onGuard = { throw IOException("private-path-containing diagnostic") }
        val error = error(f); assertEquals(OperationErrorCode.STORAGE_FAILED, error.code); assertTrue(error.retryable); assertFalse(error.safeMessageKey.contains("private")); assertEquals(f.f.source.value, error.affectedId)
    }

    @Test fun boundLoadRejectsRowOnlyDriftBeforePrivatePathResolution() = runTest {
        val mutations = listOf<Pair<String, (ExportSourceBinding) -> ExportSourceBinding>>(
            "ingest transaction ID" to { it.copy(blob = it.blob.copy(ingestTransactionId = UUID.randomUUID().toString())) },
            "blob creation time" to { it.copy(blob = it.blob.copy(createdAt = it.blob.createdAt + 1L)) },
            "source creation time" to { it.copy(source = it.source.copy(createdAt = it.source.createdAt + 1L)) },
        )
        for ((field, mutate) in mutations) {
            val f = Fixture()
            val admitted = f.binding()
            val original = f.load()
            val drifted = mutate(admitted)
            assertNotEquals(field, admitted, drifted)
            f.f.metadata.sources[admitted.source.sourceId] = drifted.source
            f.f.metadata.blobs[admitted.blob.sourceBlobId] = drifted.blob
            assertSameProjection(original, f.load())
            f.metadataReads.clear()

            val before = f.inventory()
            val pathChecks = f.privatePathChecks
            assertIntegrityRefusal(f.f.source) { f.loadForProducer(admitted) }
            assertEquals(field, pathChecks, f.privatePathChecks)
            assertEquals(before, f.inventory())
            assertOneBoundedRead(f)
        }
    }

    @Test fun boundLoadAllowsBothPositiveCounterChangesAndReturnsTheVerifiedOriginal() = runTest {
        val f = Fixture()
        val admitted = f.binding()
        val original = f.load()
        f.metadataReads.clear()
        f.f.metadata.sources[admitted.source.sourceId] = admitted.source.copy(refCount = admitted.source.refCount + 1L)
        f.f.metadata.blobs[admitted.blob.sourceBlobId] = admitted.blob.copy(sourceCount = admitted.blob.sourceCount + 1L)
        val before = f.inventory()
        val pathChecks = f.privatePathChecks

        val result = f.loadForProducer(admitted)
        assertOneBoundedRead(f)
        assertTrue("Bound load did not reach the real private path", f.privatePathChecks > pathChecks)
        assertSameProjection(original, result)
        assertArrayEquals(f.bytes, result.file.readBytes())
        assertEquals(before, f.inventory())
    }

    @Test fun boundLoadRejectsEachNonpositiveLiveCounterBeforePrivatePathResolution() = runTest {
        for (badCount in listOf(0L, -1L)) for (sourceCounter in listOf(true, false)) {
            val f = Fixture()
            val admitted = f.binding()
            if (sourceCounter) {
                f.f.metadata.sources[admitted.source.sourceId] = admitted.source.copy(refCount = badCount)
            } else {
                f.f.metadata.blobs[admitted.blob.sourceBlobId] = admitted.blob.copy(sourceCount = badCount)
            }
            val before = f.inventory()
            val pathChecks = f.privatePathChecks

            // Even a permissive scalar response cannot waive positive typed liveness; the other counter is unchanged.
            assertIntegrityRefusal(f.f.source) { f.loadForProducer(admitted) }
            assertEquals("count=$badCount, sourceCounter=$sourceCounter", pathChecks, f.privatePathChecks)
            assertEquals(before, f.inventory())
            assertOneBoundedRead(f)
        }
    }

    @Test fun boundLoadStillHashesClosedBytesWhenTheRowsMatch() = runTest {
        val f = Fixture()
        val original = f.blob
        // Exact admitted/live rows agree on a wrong hash; the same-length original remains untouched.
        f.f.metadata.blobs[original.sourceBlobId] = original.copy(byteSha256 = "0".repeat(64))
        val admitted = f.binding()
        val before = f.inventory()
        val pathChecks = f.privatePathChecks

        assertIntegrityRefusal(f.f.source) { f.loadForProducer(admitted) }
        assertOneBoundedRead(f)
        assertTrue("Matching rows must reach the real byte proof", f.privatePathChecks > pathChecks)
        assertArrayEquals(f.bytes, f.files.finalFile(original.sourceBlobId).readBytes())
        assertEquals(before, f.inventory())
    }

    private fun assertSameProjection(expected: CommittedSource, actual: CommittedSource) {
        assertEquals(expected.sourceId, actual.sourceId)
        assertEquals(expected.sourceBlobId, actual.sourceBlobId)
        assertEquals(expected.sourceByteHash, actual.sourceByteHash)
        assertEquals(expected.byteCount, actual.byteCount)
        assertEquals(expected.inspection, actual.inspection)
        assertEquals(expected.file, actual.file)
    }

    private fun assertOneBoundedRead(f: Fixture) {
        assertEquals(listOf("transaction", "bounds", "source", "blob"), f.metadataReads)
        assertFalse(f.f.metadata.inTransaction)
    }

    private suspend fun assertIntegrityRefusal(id: SourceId, load: suspend () -> CommittedSource) {
        val failure = try {
            load()
            throw AssertionError("Unverified producer source was accepted")
        } catch (failure: RepositoryFailure) { failure.error }
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.code)
        assertFalse(failure.retryable)
        assertEquals(id.value, failure.affectedId)
    }
}
