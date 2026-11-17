package app.pristinepage

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.*
import app.pristinepage.image.preview.OriginalPreviewBitmap
import app.pristinepage.image.preview.OriginalPreviewLoader
import app.pristinepage.image.decode.ImageAllocationBudget
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android bitmap bridge/shared admission; no Compose image is displayed, picker opened, provider grant acquired or source changed. Retaining a borrowed Bitmap here explicitly checks that owner close cannot invalidate an already issued draw reference. */
@RunWith(AndroidJUnit4::class)
class OriginalPreviewBitmapIntegrationTest {
    @Test fun bitmapKeepsExactOpaqueSrgbSamplesAndClosingItsOwnerNeverRecyclesAnIssuedDrawReference() = runBlocking {
        DocumentCatalogIntegrationTest.Fixture(OriginalPreviewIntegrationTest.asymmetricPng()).use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Display original bitmap")
            val batch = graph.store.imports.beginImport(doc, 1, null)
            val saved = graph.photoImports.importProviderReferences(listOf(f.good), batch.parentId).single() as IngestResult.Committed
            val before = f.files(); f.forbid = true
            val result = load(graph, saved.sourceId.value)
            val issued = result.bitmap
            try {
                assertEquals(PixelSize(2,1), result.output.size); assertEquals(saved.sourceByteHash, result.sourceByteHash)
                assertEquals(saved.sourcePixelHash, result.inspection.sourcePixelHash)
                assertEquals(Bitmap.Config.ARGB_8888, issued.config); assertTrue(issued.colorSpace!!.isSrgb); assertFalse(issued.hasAlpha())
                assertEquals(0xff3c3c64.toInt(), issued.getPixel(0,0)); assertEquals(0xffe56464.toInt(), issued.getPixel(1,0))
                assertTrue(graph.budget.usedBytes >= 16)
            } finally { result.close(); result.close() }
            assertEquals(0L, graph.budget.usedBytes); assertFalse(issued.isRecycled)
            assertEquals(0xff3c3c64.toInt(), issued.getPixel(0,0))
            assertThrows(IllegalStateException::class.java) { result.bitmap }
            assertEquals(before, f.files()); assertEquals(1, f.providerOpens)
        }
    }

    @Test fun competingBudgetOwnerRefusesDisplayWorkWithoutLosingItsReservationOrOriginal() = runBlocking {
        DocumentCatalogIntegrationTest.Fixture().use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Display admission refusal")
            val batch = graph.store.imports.beginImport(doc,1,null)
            val saved = graph.photoImports.importProviderReferences(listOf(f.good),batch.parentId).single() as IngestResult.Committed
            val before = f.files(); f.forbid = true
            graph.budget.reserve(graph.budget.capacityBytes - 1).use {
                try { load(graph,saved.sourceId.value).close(); fail("Display bypassed shared resource admission") }
                catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.RESOURCE_LIMIT,failure.error.code) }
                assertEquals(graph.budget.capacityBytes - 1, graph.budget.usedBytes)
            }
            assertEquals(0L,graph.budget.usedBytes); assertEquals(before,f.files()); assertEquals(1,f.providerOpens)
        }
    }

    private companion object {
        suspend fun load(graph: ApplicationImports, id: String): OriginalPreviewBitmap {
            val type = try { Class.forName("app.pristinepage.image.preview.OriginalPreviewBitmapLoader") }
            catch (_: ClassNotFoundException) { throw AssertionError("Budgeted original bitmap bridge is absent") }
            val raster = OriginalPreviewLoader(graph.store.committedSources, graph.decoder, graph.budget)
            val loader = type.getConstructor(OriginalPreviewLoader::class.java, ImageAllocationBudget::class.java).newInstance(raster, graph.budget)
            return kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
                try { type.getMethod("load",String::class.java,Int::class.javaPrimitiveType,kotlin.coroutines.Continuation::class.java).invoke(loader,id,2,continuation) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
    }
}
