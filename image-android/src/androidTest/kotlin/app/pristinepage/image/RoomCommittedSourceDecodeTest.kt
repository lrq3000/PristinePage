package app.pristinepage.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.RoomCommittedSources
import app.pristinepage.data.repository.RoomMetadataAccess
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.image.decode.SourceFileDecoder
import app.pristinepage.image.EncodedImageFixtures.exif
import app.pristinepage.image.EncodedImageFixtures.jpeg
import app.pristinepage.image.EncodedImageFixtures.rgbaPng
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual committed-source ID→Room→private bytes→platform decoder, not an old URI or synthetic inspection. Adversaries alter only operation-owned synthetic rows/files and remain retained. No process-death, picker, API26, profile or physical acceptance follows. */
@RunWith(AndroidJUnit4::class)
class RoomCommittedSourceDecodeTest {
    private val output = OutputSpec(PixelSize(1, 1), RenderPurpose.PREVIEW)
    private fun decoder(f: RoomNormalizedIngestTest.Fixture, codec: SourceFileDecoder = f.decoder) = SourceDecoder(RoomCommittedSources(RoomMetadataAccess(f.database), f.database.catalog(), f.files), codec)
    private suspend fun ingest(f: RoomNormalizedIngestTest.Fixture): IngestResult.Committed = f.store.ingest(f.reference, f.reserve().single()) as IngestResult.Committed
    private suspend fun error(decoder: SourceDecoder, id: String): OperationError = try { decoder.decode(id, output).close(); throw AssertionError("Invalid committed source was decoded") } catch (failure: RepositoryFailure) { failure.error }

    @Test fun reopenedPngDecodesBySourceIdWithoutProviderOrPreviewIdentitySubstitution() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val first = ingest(f); f.reopen(); f.forbidInputs = true; val before = f.inventory()
            decoder(f).decode(first.sourceId.value, output).use { result ->
                assertEquals(first.sourceByteHash, result.sourceByteHash); assertEquals(first.sourcePixelHash, result.inspection.sourcePixelHash)
                assertEquals(PixelSize(4, 1), result.inspection.normalizedSize)
                assertEquals(listOf(255, 255, 255, 159, 143, 127, 255, 0, 0, 255, 255, 255), result.image.toPackedByteArray().map { it.toInt() and 255 })
                assertTrue(f.budget.usedBytes > 0)
            }
            assertEquals(before, f.inventory()); assertEquals(1, f.opened); assertEquals(1, f.inspected); assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    @Test fun exifJpegKeepsRecordedFullSourceBindingForDifferentGeometryDestinationAspect() = runBlocking {
        RoomNormalizedIngestTest.Fixture(exif(jpeg(), 6)).use { f ->
            val first = ingest(f); f.reopen(); f.forbidInputs = true; val before = f.inventory()
            decoder(f).decode(first.sourceId.value, OutputSpec(PixelSize(3, 1), RenderPurpose.EXPORT)).use { result ->
                assertEquals(first.sourceByteHash, result.sourceByteHash); assertEquals(first.sourcePixelHash, result.inspection.sourcePixelHash)
                assertEquals(6, result.inspection.exifOrientation); assertEquals(PixelSize(3, 2), result.inspection.encodedSize); assertEquals(PixelSize(2, 3), result.inspection.normalizedSize)
                assertEquals(2, result.image.width); assertEquals(3, result.image.height)
            }
            assertEquals(before, f.inventory()); assertEquals(1, f.opened); assertEquals(1, f.inspected); assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    @Test fun rawSqliteLongAliasesCannotPassThroughRoomIntNarrowingIntoDecode() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val first = ingest(f); val blob = f.database.catalog().source(first.sourceId.value)!!.sourceBlobId
            for ((table, field, original) in listOf(Triple("Source", "width", 4), Triple("Source", "height", 1), Triple("Source", "exifOrientation", 1), Triple("SourceBlob", "encodedWidth", 4), Triple("SourceBlob", "encodedHeight", 1))) {
                val key = if (table == "Source") "sourceId" else "sourceBlobId"; val id = if (table == "Source") first.sourceId.value else blob; val raw = original.toLong() + 4294967296L
                f.database.openHelper.writableDatabase.execSQL("UPDATE $table SET $field=? WHERE $key=?", arrayOf<Any>(raw, id))
                f.database.openHelper.readableDatabase.query("SELECT $field FROM $table WHERE $key=?", arrayOf(id)).use { assertTrue(it.moveToFirst()); assertEquals(raw, it.getLong(0)) }
                val before = f.inventory(); assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(decoder(f), first.sourceId.value).code); assertEquals(before, f.inventory()); assertEquals(0L, f.budget.usedBytes)
                f.database.openHelper.writableDatabase.execSQL("UPDATE $table SET $field=? WHERE $key=?", arrayOf<Any>(original, id))
            }
            decoder(f).decode(first.sourceId.value, output).close(); assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    @Test fun oversizedWrongTypeSourceMetadataRefusesWithoutChangingRowsOrAllocatingRaster() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val first = ingest(f)
            f.database.openHelper.writableDatabase.execSQL("UPDATE Source SET conversionFingerprint=zeroblob(524288) WHERE sourceId=?", arrayOf(first.sourceId.value))
            f.database.openHelper.readableDatabase.query("SELECT typeof(conversionFingerprint),length(conversionFingerprint) FROM Source WHERE sourceId=?", arrayOf(first.sourceId.value)).use { assertTrue(it.moveToFirst()); assertEquals("blob", it.getString(0)); assertEquals(524288L, it.getLong(1)) }
            val before = f.inventory(); assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(decoder(f), first.sourceId.value).code)
            assertEquals(before, f.inventory()); assertEquals(0L, f.budget.usedBytes); f.assertForeignKeys()
        }
    }

    @Test fun changedPrivateOriginalCannotBeRepairedFromProviderOrAcceptedAsRecorded() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val first = ingest(f); val source = f.database.catalog().source(first.sourceId.value)!!; val file = f.files.finalFile(source.sourceBlobId)
            val changed = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }; file.writeBytes(changed); f.forbidInputs = true; val before = f.inventory()
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(decoder(f), first.sourceId.value).code)
            assertEquals(before, f.inventory()); assertArrayEquals(changed, file.readBytes()); assertEquals(1, f.opened); assertEquals(1, f.inspected); assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun retainedUnsupportedConversionDoesNotRewriteItsIdentityAfterDecode() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val first = ingest(f)
            f.database.openHelper.writableDatabase.execSQL("UPDATE Source SET conversionFingerprint='future-unavailable-conversion' WHERE sourceId=?", arrayOf(first.sourceId.value))
            val before = f.inventory(); assertEquals(OperationErrorCode.UNSUPPORTED_VERSION, error(decoder(f), first.sourceId.value).code)
            assertEquals(before, f.inventory()); assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun tinyPreviewCannotBypassFullSourceRasterAdmissionAfterParserFits() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val first = ingest(f); val low = ImageAllocationBudget(1_000_000); val service = decoder(f, AndroidSourceInspector(low)); val before = f.inventory()
            assertEquals(OperationErrorCode.RESOURCE_LIMIT, error(service, first.sourceId.value).code)
            assertEquals(0L, low.usedBytes); assertEquals(before, f.inventory()); assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun absentSourceIdIsUnavailableAndCannotSelectAnotherStoredSource() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            ingest(f); val before = f.inventory()
            assertEquals(OperationErrorCode.SOURCE_UNAVAILABLE, error(decoder(f), UUID.randomUUID().toString()).code)
            assertEquals(before, f.inventory()); assertEquals(0L, f.budget.usedBytes)
        }
    }

    @Test fun nulPaddedTextIsBoundedInBytesBeforeRoomMaterializesSourceRows() = runBlocking {
        RoomNormalizedIngestTest.Fixture(rgbaPng()).use { f ->
            val first = ingest(f); val value = "srgb-v1\u0000" + "x".repeat(524288)
            // Half a MiB is a safe bounded metadata adversary, not a native OOM experiment. SQLite's character length hides the suffix; raw byte length must still reject BEFORE typed row projection.
            f.database.openHelper.writableDatabase.execSQL("UPDATE Source SET conversionFingerprint=? WHERE sourceId=?", arrayOf<Any>(value, first.sourceId.value))
            f.database.openHelper.readableDatabase.query("SELECT typeof(conversionFingerprint),length(conversionFingerprint),length(CAST(conversionFingerprint AS BLOB)) FROM Source WHERE sourceId=?", arrayOf(first.sourceId.value)).use {
                assertTrue(it.moveToFirst()); assertEquals("text", it.getString(0)); assertEquals(7L, it.getLong(1)); assertEquals(524296L, it.getLong(2))
            }
            assertEquals(false, f.database.catalog().sourceReadIsBounded(first.sourceId.value))
            val before = f.inventory(); assertEquals(OperationErrorCode.INTEGRITY_FAILED, error(decoder(f), first.sourceId.value).code)
            assertEquals(before, f.inventory()); assertEquals(0L, f.budget.usedBytes)
        }
    }
}
