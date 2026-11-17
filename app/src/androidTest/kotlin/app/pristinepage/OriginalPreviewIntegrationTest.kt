package app.pristinepage

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.model.*
import app.pristinepage.image.preview.OriginalPreviewLoader
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room/source byte proof/ImageDecoder/preview sampling/accounting. Provider delivery is synthetic, not a platform grant or a displayed bitmap; all fixture files remain retained. */
@RunWith(AndroidJUnit4::class)
class OriginalPreviewIntegrationTest {
    @Test fun importedOriginalProducesVerifiedAspectPreservingPreviewWithoutProviderReopenOrFileWrites() = runBlocking {
        DocumentCatalogIntegrationTest.Fixture(asymmetricPng()).use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Native original preview")
            val batch = graph.store.imports.beginImport(doc, 1, null)
            val saved = graph.photoImports.importProviderReferences(listOf(f.good), batch.parentId).single() as IngestResult.Committed
            f.forbid = true; val before = f.files()
            val preview = OriginalPreviewLoader(graph.store.committedSources, graph.decoder, graph.budget).load(saved.sourceId.value, 2)
            preview.use {
                assertEquals(saved.sourceId.value, it.sourceId); assertEquals(saved.sourceByteHash, it.sourceByteHash)
                assertEquals(saved.sourcePixelHash, it.inspection.sourcePixelHash); assertEquals(PixelSize(4, 2), it.inspection.normalizedSize)
                assertEquals(PixelSize(2, 1), it.output.size); assertEquals(RenderPurpose.PREVIEW, it.output.purpose)
                assertArrayEquals(byteArrayOf(60,60,100,229.toByte(),100,100), it.image.toPackedByteArray())
                assertTrue(graph.budget.usedBytes > 0)
            }
            preview.close(); assertEquals(0L, graph.budget.usedBytes)
            assertEquals(before, f.files()); assertEquals(1, f.providerOpens)
            assertArrayEquals(f.png, graph.store.committedSources.load(saved.sourceId).file.readBytes())
        }
    }

    @Test fun changedPrivateOriginalCannotBecomeAPreviewOrBeRepairedFromTheProvider() = runBlocking {
        DocumentCatalogIntegrationTest.Fixture(asymmetricPng()).use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Preview integrity refusal")
            val batch = graph.store.imports.beginImport(doc, 1, null)
            val saved = graph.photoImports.importProviderReferences(listOf(f.good), batch.parentId).single() as IngestResult.Committed
            val original = graph.store.committedSources.load(saved.sourceId).file
            val changed = original.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            original.writeBytes(changed); f.forbid = true; val before = f.files()
            try { OriginalPreviewLoader(graph.store.committedSources, graph.decoder, graph.budget).load(saved.sourceId.value, 2).close(); fail("Changed original produced a preview") }
            catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code) }
            assertEquals(0L, graph.budget.usedBytes); assertEquals(before, f.files()); assertEquals(1, f.providerOpens)
            assertArrayEquals(changed, original.readBytes())
        }
    }

    internal companion object {
        fun asymmetricPng(): ByteArray {
            val pixels = intArrayOf(0xff000000.toInt(),0xff641428.toInt(),0xffc82850.toInt(),0xfff03c78.toInt(),0xff1464c8.toInt(),0xff7878a0.toInt(),0xffdc8c78.toInt(),0xffffa050.toInt())
            val bitmap = Bitmap.createBitmap(pixels, 4, 2, Bitmap.Config.ARGB_8888)
            return try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() }
            finally { bitmap.recycle() }
        }
    }
}
