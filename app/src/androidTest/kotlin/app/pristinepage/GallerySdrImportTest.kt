package app.pristinepage

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.model.IngestResult
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual provider-reference ingest, private commit and Android codec/readback with only generated pixels. */
@RunWith(AndroidJUnit4::class)
class GallerySdrImportTest {
    @Test fun importsAnUltraHdrJpegAndReopensTheSdrPixelsWithoutRewritingTheOriginal() = runBlocking<Unit> {
        val primary = jpeg(16, 12, 0xff5baac8.toInt())
        val gain = jpeg(8, 6, 0xff404040.toInt())
        val source = withGainMap(primary, gain)
        val (plainInspection, expectedRgb) = ingestAndReopen(primary)
        val (hdrInspection, actualRgb) = ingestAndReopen(source)
        assertTrue(hdrInspection.endsWith("/jpeg-ultrahdr-sdr-v1"))
        assertNotEquals(plainInspection, hdrInspection)
        assertArrayEquals(expectedRgb, actualRgb)
    }

    private suspend fun ingestAndReopen(bytes: ByteArray): Pair<String, ByteArray> =
        DocumentCatalogIntegrationTest.Fixture(bytes).use { fixture ->
            val graph = fixture.open()
            val album = graph.store.documents.createDocument("Synthetic imported JPEG")
            val batch = graph.store.imports.beginImport(album, 1, null)
            val result = graph.photoImports.importProviderReferences(listOf(fixture.good), batch.parentId).single()
            assertTrue("Synthetic provider did not commit: ${result.javaClass.simpleName}", result is IngestResult.Committed)
            val saved = result as IngestResult.Committed
            fixture.forbid = true
            val original = graph.store.committedSources.load(saved.sourceId)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(), original.sourceByteHash)
            assertArrayEquals(bytes, original.file.readBytes())
            graph.decoder.decode(saved.sourceId.value, OutputSpec(PixelSize(16, 12), RenderPurpose.PREVIEW)).use {
                assertEquals(PixelSize(16, 12), it.inspection.normalizedSize)
                it.inspection.conversionFingerprint to it.image.toPackedByteArray()
            }
        }

    private fun jpeg(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun withGainMap(primary: ByteArray, secondary: ByteArray): ByteArray {
        fun segment(code: Int, body: ByteArray): ByteArray {
            val length = body.size + 2
            return byteArrayOf(-1, code.toByte(), (length ushr 8).toByte(), length.toByte()) + body
        }
        val xmp = segment(0xe1, ("http://ns.adobe.com/xap/1.0/\u0000" +
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:BaseRenditionIsHDR="False" /></rdf:RDF></x:xmpmeta>""").encodeToByteArray())
        val iso = "urn:iso:std:iso:ts:21496:-1\u0000".encodeToByteArray()
        val isoVersion = segment(0xe2, iso + ByteArray(4))
        val isoGain = segment(0xe2, iso + ByteBuffer.allocate(61)
            .putInt(0).put(0x40).also { b -> repeat(7) { b.putInt(1).putInt(1) } }.array())
        val mpAttribute = segment(0xe2, "MPF\u0000".encodeToByteArray() + ByteBuffer.allocate(26)
            .putInt(0x4d4d002a).putInt(8).putShort(1).putShort(0xb000.toShort()).putShort(7).putInt(4)
            .put("0100".encodeToByteArray()).putInt(0).array())
        val tiffStart = 2 + xmp.size + isoVersion.size + 8
        val primaryEnd = primary.size + xmp.size + isoVersion.size + 90
        val gain = secondary.copyOfRange(0, 2) + xmp + isoGain + mpAttribute + secondary.copyOfRange(2, secondary.size)
        val tiff = ByteBuffer.allocate(82)
            .putInt(0x4d4d002a).putInt(8).putShort(3)
            .putShort(0xb000.toShort()).putShort(7).putInt(4).put("0100".encodeToByteArray())
            .putShort(0xb001.toShort()).putShort(4).putInt(1).putInt(2)
            .putShort(0xb002.toShort()).putShort(7).putInt(32).putInt(50).putInt(0)
            .putInt(0x030000).putInt(primaryEnd).putInt(0).putInt(0)
            .putInt(0).putInt(gain.size).putInt(primaryEnd - tiffStart).putInt(0).array()
        return primary.copyOfRange(0, 2) + xmp + isoVersion + segment(0xe2, "MPF\u0000".encodeToByteArray() + tiff) +
            primary.copyOfRange(2, primary.size) + gain
    }
}
