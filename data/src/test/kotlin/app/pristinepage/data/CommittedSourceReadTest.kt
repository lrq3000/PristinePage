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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
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
        val root = Files.createTempDirectory(parent.toPath(), "source-").toFile()
        val files = SourceFiles(root)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source: SourceRow get() = f.metadata.sources.getValue(f.source.value)
        val blob: SourceBlobRow get() = f.metadata.blobs.getValue(source.sourceBlobId)
        var bounded: Boolean? = true
        var projections = 0
        var onGuard: () -> Unit = {}
        init {
            val stage = files.stage(UUID.randomUUID().toString(), blob.sourceBlobId, ByteArrayInputStream(bytes), 100)
            files.move(files.seal(stage, byteArrayOf(1)))
            f.metadata.blobs[blob.sourceBlobId] = blob.copy(byteSha256 = stage.byteSha256)
        }
        suspend fun load(): Any {
            val reader = try { Class.forName("app.pristinepage.data.repository.RoomCommittedSources") }
            catch (_: ClassNotFoundException) { throw AssertionError("Verified committed-source reader is absent") }
            val port = Class.forName("app.pristinepage.data.repository.CommittedSourceQueries")
            val queries = Proxy.newProxyInstance(port.classLoader, arrayOf(port)) { _, method, args ->
                check(method.name == "sourceReadIsBounded" && f.metadata.inTransaction && args[0] == f.source.value)
                onGuard(); bounded
            }
            val catalog = object : CatalogQueries by f.metadata {
                override fun source(id: String): SourceRow? { assertTrue(f.metadata.inTransaction); assertEquals(true, bounded); projections++; return f.metadata.source(id) }
                override fun sourceBlob(id: String): SourceBlobRow? { assertTrue(f.metadata.inTransaction); assertEquals(true, bounded); projections++; return f.metadata.sourceBlob(id) }
                override fun document(id: String) = throw AssertionError("Source lookup consulted current document")
                override fun activePages(documentId: String) = throw AssertionError("Source lookup consulted live membership")
                override fun page(id: String) = throw AssertionError("Source lookup consulted current page")
                override fun recipe(pageId: String, revision: Long) = throw AssertionError("Source lookup consulted current recipe")
            }
            val metadata = object : MetadataAccess {
                override val queries = catalog
                override fun <T> transaction(action: () -> T): T = f.metadata.transaction(action)
            }
            val instance = try { reader.constructors.single { it.parameterCount == 4 }.newInstance(metadata, queries, files, Dispatchers.Unconfined) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try { reader.methods.single { it.name.startsWith("load-") }.invoke(instance, f.source.value, continuation) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
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
}
