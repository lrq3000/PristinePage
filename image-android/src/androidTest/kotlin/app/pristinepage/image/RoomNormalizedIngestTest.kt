package app.pristinepage.image

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.files.InspectedSourceForIngest
import app.pristinepage.data.journal.IngestJournalCodec
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.EncodedImageFixtures.exif
import app.pristinepage.image.EncodedImageFixtures.jpeg
import app.pristinepage.image.EncodedImageFixtures.rgbaPng
import app.pristinepage.image.EncodedImageFixtures.sha
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room/file/journal/codec composition. Only provider delivery/faults are injected; native grants/picker, process death, API26 and physical performance remain separate gates. Unique DBs/files are closed and retained, never deleted. */
@RunWith(AndroidJUnit4::class)
class RoomNormalizedIngestTest {
    @Test fun pngCommitBindsExactNormalizedPixelsAndReplaysAfterReopenWithoutProviderOrDecode() = runBlocking {
        Fixture(rgbaPng()).use { f ->
            val child = f.reserve().single()
            val first = f.store.ingest(f.reference, child) as IngestResult.Committed
            val expectedPixels = byteArrayOf(-1, -1, -1, -97, -113, 127, -1, 0, 0, -1, -1, -1)
            f.assertBinding(first, "image/png", PixelSize(4, 1), PixelSize(4, 1), 1, expectedPixels)
            assertEquals(1, f.opened); assertEquals(1, f.closed); assertEquals(1, f.inspected)
            val stable = f.inventory()
            f.reopen(); f.forbidInputs = true
            assertEquals(first, f.store.ingest(f.reference, child))
            assertEquals(stable, f.inventory()); assertEquals(1, f.opened); assertEquals(1, f.inspected)
            assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    @Test fun jpegExifSixStoresOrientedBindingAndUnchangedEncodedOriginalAcrossReopen() = runBlocking {
        val jpeg = jpeg()
        val bytes = exif(jpeg, 6)
        Fixture(bytes).use { f ->
            // The platform JPEG is lossy: use its actual un-oriented decode, then an independent literal permutation, not a second production normalizer.
            val baseline = File(f.root, "baseline.bin").apply { parentFile!!.mkdirs(); writeBytes(jpeg) }
            val pixels = f.decoder.decodeFile(baseline, sha(jpeg)).use { it.image.toPackedByteArray() }
            val expected = intArrayOf(3, 0, 4, 1, 5, 2).flatMap { listOf(pixels[it * 3], pixels[it * 3 + 1], pixels[it * 3 + 2]) }.toByteArray()
            val child = f.reserve().single(); val first = f.store.ingest(f.reference, child) as IngestResult.Committed
            f.assertBinding(first, "image/jpeg", PixelSize(3, 2), PixelSize(2, 3), 6, expected)
            f.reopen(); f.forbidInputs = true
            assertEquals(first, f.store.ingest(f.reference, child)); f.assertBinding(first, "image/jpeg", PixelSize(3, 2), PixelSize(2, 3), 6, expected)
            assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    @Test fun admittedCaptureDateIsSealedWithoutChangingNormalizedSourceIdentity() = runBlocking {
        val tiff = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).putShort(0x4949).putShort(42).putInt(8)
            .putShort(1).putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(26).putInt(0)
            .putShort(1).putShort(0x9003.toShort()).putShort(2).putInt(20).putInt(44).putInt(0)
            .put("2024:02:29 09:20:01\u0000".encodeToByteArray()).array()
        val pixels = byteArrayOf(0, -1, 0, 0, 0, -1, 0, 0, 0, -1)
        val dated = EncodedImageFixtures.png(3, 1, 8, 2, pixels, EncodedImageFixtures.chunk("eXIf", tiff))
        val undated = EncodedImageFixtures.png(3, 1, 8, 2, pixels)
        Fixture(dated, projectCaptureDate = true).use { f ->
            val child = f.reserve().single()
            val committed = f.store.ingest(f.reference, child) as IngestResult.Committed
            val intent = RoomIngestLookup(f.database).find(child)!!
            val sql = f.database.openHelper.readableDatabase
            val receipt = sql.query("SELECT payload FROM FileTransaction WHERE transactionId=?", arrayOf(intent.transactionId.value))
                .use { row -> assertTrue(row.moveToFirst()); IngestJournalCodec.decodeCommit(row.getBlob(0), intent) }
            assertEquals(SourceCaptureDate(19782), receipt.seal.captureDate)
            assertEquals(sha(dated), committed.sourceByteHash)
            val plain = File(f.root, "undated.bin").apply { writeBytes(undated) }
            assertEquals(receipt.seal.inspection, f.decoder.inspect(plain, sha(undated)))
            assertEquals(SourceCaptureDate(null), f.decoder.inspectForIngest(plain, sha(undated)).captureDate)
            val wrongHash = try {
                f.decoder.inspectForIngest(plain, sha(dated))
                fail("Wrong original digest must not yield a projection")
                null
            } catch (failure: RepositoryFailure) { failure.error.code }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, wrongHash)
            f.reopen(); f.forbidInputs = true
            assertEquals(committed, f.store.ingest(f.reference, child))
            assertEquals(1, f.inspected)
            assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun corruptAndMalformedActualSourcesNeverCommitVisibilityOrReadyOwnership() = runBlocking {
        for (bytes in listOf(rgbaPng().dropLast(1).toByteArray(), exif(jpeg(), 9), byteArrayOf(1, 2, 3, 4))) Fixture(bytes).use { f ->
            val child = f.reserve().single()
            val result = f.store.ingest(f.reference, child) as IngestResult.Rejected
            assertTrue(result.error.code in setOf(OperationErrorCode.INVALID_INPUT, OperationErrorCode.UNSUPPORTED_FORMAT))
            f.reopen(); f.assertNoVisibleSource()
            val row = f.database.importSessions().session(child.value)!!
            assertEquals("FAILED", row.state); assertNull(row.uriText); assertEquals(result.error.code.name, row.errorCode)
            assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    @Test fun resourceRefusalKeepsOriginalStagingAndNoSavedPage() = runBlocking {
        Fixture(rgbaPng(), ImageAllocationBudget(200_000)).use { f ->
            val child = f.reserve().single(); val result = f.store.ingest(f.reference, child) as IngestResult.Rejected
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, result.error.code)
            val intent = RoomIngestLookup(f.database).find(child)!!
            assertArrayEquals(f.bytes, File(f.root, "staging/${intent.transactionId.value}/original.part").readBytes())
            assertFalse(f.files.finalFile(intent.sourceBlobId.value).exists())
            f.reopen(); f.assertNoVisibleSource(); assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    @Test fun revokedMidCopyDoesNotCommitPageOrReachActualDecoder() = runBlocking {
        Fixture(rgbaPng()).use { f ->
            f.revokeAfterFirstRead = true
            val child = f.reserve().single(); val result = f.store.ingest(f.reference, child) as IngestResult.Rejected
            assertEquals(OperationErrorCode.PERMISSION_LOST, result.error.code)
            assertEquals(0, f.inspected); assertEquals(1, f.closed)
            f.reopen(); f.assertNoVisibleSource(); assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    internal class Fixture(val bytes: ByteArray, val budget: ImageAllocationBudget = ImageAllocationBudget(32L * 1024 * 1024),
        private val projectCaptureDate: Boolean = false,
    ) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val name = "t04-normalized-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, "t04-normalized-${UUID.randomUUID()}")
        val files = SourceFiles(root)
        val reference = Uri.parse("content://fixture.documents/actual-encoded-synthetic")
        val decoder = AndroidSourceInspector(budget)
        private val owners = LocalOperationOwners()
        var opened = 0; var closed = 0; var inspected = 0
        var forbidInputs = false
        var revokeAfterFirstRead = false
        var afterInspection: suspend () -> Unit = {}
        var database = open()
            private set
        val document = runBlocking { RoomDocumentRepository(RoomMetadataAccess(database), RecipeAdmission { _, _ -> null }).createDocument("Normalized source integration") }
        var store = store()
            private set
        private fun open() = Room.databaseBuilder(context, PristineDatabase::class.java, name).build()
        private fun store(): SourceStore {
            val provider = SourceInput { bound ->
                assertFalse(database.inTransaction()); assertFalse("Replay reopened the provider", forbidInputs); assertEquals(reference.toString(), bound); opened++
                object : ByteArrayInputStream(bytes) {
                    private var reads = 0
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (revokeAfterFirstRead && reads++ > 0) throw SecurityException("synthetic read revocation")
                        return super.read(buffer, offset, if (revokeAfterFirstRead) minOf(length, 4) else length)
                    }
                    override fun close() { closed++; super.close() }
                }
            }
            val inspector = object : SourceInspector {
                private suspend fun checked(file: File, hash: String, inspect: suspend () -> InspectedSourceForIngest): InspectedSourceForIngest {
                    assertFalse(database.inTransaction()); assertFalse("Replay decoded the source again", forbidInputs); assertEquals(opened, closed); inspected++
                    assertArrayEquals(bytes, file.readBytes()); assertEquals(sha(bytes), hash)
                    val inspection = inspect()
                    afterInspection()
                    return inspection
                }
                override suspend fun inspect(file: File, expectedByteSha256: String): SourceInspection =
                    checked(file, expectedByteSha256) {
                        InspectedSourceForIngest(decoder.inspect(file, expectedByteSha256), null)
                    }.inspection

                override suspend fun inspectForIngest(file: File, expectedByteSha256: String): InspectedSourceForIngest =
                    if (!projectCaptureDate) super.inspectForIngest(file, expectedByteSha256)
                    else checked(file, expectedByteSha256) { decoder.inspectForIngest(file, expectedByteSha256) }
            }
            val completion = RoomIngestCompletion(database, files)
            return RoomSourceStore(files, provider, inspector, RoomIngestPreparation(database), RoomIngestLookup(database), completion, completion, RoomIngestPhases(database), RoomJobLeases(database, owners), owners)
        }
        suspend fun reserve(): List<ImportSessionId> {
            val sessions = RoomImportSessions(database)
            val parent = sessions.beginImport(document, database.catalog().document(document.value)!!.documentRevision, null).parentId
            val batch = sessions.reserveImportItems(parent, 1)
            sessions.bindImportItem(parent, 0, reference)
            return batch.childIds
        }
        fun reopen() { database.close(); database = open(); store = store() }
        fun assertBinding(result: IngestResult.Committed, mime: String, encoded: PixelSize, normalized: PixelSize, orientation: Int, pixels: ByteArray) {
            val q = database.catalog(); val source = q.source(result.sourceId.value)!!; val blob = q.sourceBlob(source.sourceBlobId)!!
            assertEquals("READY", source.state); assertEquals("READY", blob.state)
            assertEquals(sha(bytes), result.sourceByteHash); assertEquals(result.sourceByteHash, blob.byteSha256); assertEquals(bytes.size.toLong(), blob.byteCount)
            assertEquals(mime, blob.mime); assertEquals(encoded, PixelSize(blob.encodedWidth, blob.encodedHeight)); assertEquals(normalized, PixelSize(source.width, source.height)); assertEquals(orientation, source.exifOrientation)
            val stream = ByteArrayOutputStream()
            DataOutputStream(stream).use { out ->
                out.write("pristinepage-source-pixels-v1\u0000".toByteArray()); val fingerprint = source.conversionFingerprint.toByteArray()
                out.writeInt(fingerprint.size); out.write(fingerprint); out.writeInt(normalized.width); out.writeInt(normalized.height); out.writeLong(pixels.size.toLong()); out.write(pixels)
            }
            assertEquals(sha(stream.toByteArray()), result.sourcePixelHash); assertEquals(result.sourcePixelHash, source.pixelSha256)
            val recipeRow = q.recipe(result.pageId.value, 1)!!; val geometry = RecipeCodec.decode(recipeRow.canonicalRecipe).geometry as GeometryRecipe.None
            assertEquals(result.sourcePixelHash, geometry.source.sourcePixelHash); assertEquals(normalized, geometry.source.sourceSize); assertEquals(source.conversionFingerprint, geometry.source.orientationColorConversionVersion); assertEquals(0, geometry.outputQuarterTurns)
            assertEquals(2L, source.refCount); assertEquals(1L, blob.sourceCount); assertArrayEquals(bytes, files.finalFile(blob.sourceBlobId).readBytes())
        }
        fun inventory(): List<List<String>> = listOf("Document", "Page", "Source", "SourceBlob", "RecipeRevision", "SourceReference", "ImportSession", "FileTransaction", "Job").flatMap { table ->
            database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1,2").use { c ->
                val rows = mutableListOf<List<String>>()
                while (c.moveToNext()) rows += listOf(table) + (0 until c.columnCount).map { i -> if (c.isNull(i)) "NULL" else if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) sha(c.getBlob(i)) else c.getString(i) }
                rows
            }
        }
        fun assertNoVisibleSource() {
            for (table in listOf("Page", "Source", "SourceBlob", "RecipeRevision", "SourceReference")) database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c -> assertTrue(c.moveToFirst()); assertEquals(table, 0L, c.getLong(0)) }
            assertEquals(1L, database.catalog().document(document.value)!!.documentRevision)
        }
        fun assertForeignKeys() { database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) } }
        override fun close() = database.close()
    }
}
