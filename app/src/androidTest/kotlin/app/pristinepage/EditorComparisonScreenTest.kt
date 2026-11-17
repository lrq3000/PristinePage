package app.pristinepage

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.*
import app.pristinepage.editor.*
import app.pristinepage.image.preview.RenderedPreviewBitmapLoader
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose/Room/render/bitmap comparison consumer with a private synthetic graph. Literal source/quarter-turn pixels are independent of EditorViews; this is not a default-library/picker or full-resolution-detail test. */
@RunWith(AndroidJUnit4::class)
class EditorComparisonScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun referenceViewsShowDifferentLiteralGeometryAndApplyOnlyTheCompleteDraft() = runBlocking {
        val sourcePixels = intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffffff.toInt(),0xff000000.toInt(),0xff010203.toInt())
        val image = Bitmap.createBitmap(sourcePixels,3,2,Bitmap.Config.ARGB_8888)
        val png = try { ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray() } finally { image.recycle() }
        DocumentCatalogIntegrationTest.Fixture(png).use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Comparison fixture"); val batch = graph.store.imports.beginImport(doc,1,null)
            val imported = graph.photoImports.importProviderReferences(listOf(f.good),batch.parentId).single() as IngestResult.Committed; f.forbid = true
            val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Main.immediate)
            val session = withContext(Dispatchers.Main) { EditorSession(doc.value,imported.pageId.value,ApplicationEditorActions { graph },graph.renderer,scope,RenderedPreviewBitmapLoader(graph.budget)) }
            try {
                compose.runOnUiThread { compose.activity.setContent { MaterialTheme { PageEditorScreen().Content(session) {} } } }
                compose.waitUntil(10_000) { session.state.value.page != null }
                val initial = checkNotNull(session.state.value.draft)
                val draft = initial.copy(geometry = initial.geometry.withOutputQuarterTurns(1),appearance = AppearanceRecipe.PaperGainV1(strength = .75),denoise = DenoiseRecipe.BilateralLumaV1(),detail = DetailRecipe.LumaDetail7V1(),binary = BinaryRecipe.GrayscaleV1)
                compose.runOnUiThread { session.edit(draft) }
                waitFor(session,draft,EditorView.RESULT)
                compose.onNodeWithTag("editor-view-input").performScrollTo().performClick()
                waitFor(session,Recipe(geometry = draft.geometry),EditorView.GEOMETRY_INPUT)
                compose.onNodeWithTag("editor-preview").performScrollTo().assertContentDescriptionEquals("Geometry input preview, before all appearance and finishing")
                assertPixels(session,2,3,intArrayOf(0xffffffff.toInt(),0xffff0000.toInt(),0xff000000.toInt(),0xff00ff00.toInt(),0xff010203.toInt(),0xff0000ff.toInt()))
                assertEquals(draft,session.state.value.draft); assertEquals(1L,graph.store.editablePages.load(doc,imported.pageId).recipeRevision)
                compose.onNodeWithTag("editor-view-original").performScrollTo().performClick()
                waitFor(session,Recipe(geometry = GeometryRecipe.None(initial.geometry.source)),EditorView.ORIGINAL)
                compose.onNodeWithTag("editor-preview").performScrollTo().assertContentDescriptionEquals("Original photograph preview, uncropped and without user rotation")
                assertPixels(session,3,2,sourcePixels)
                assertEquals(draft,session.state.value.draft); assertTrue(session.state.value.dirty)
                compose.onNodeWithTag("editor-apply").performScrollTo().performClick()
                compose.waitUntil(10_000) { session.state.value.page?.recipeRevision == 2L && !session.state.value.saving && !session.state.value.dirty }
                waitFor(session,draft,EditorView.RESULT)
                compose.onNodeWithTag("editor-preview").performScrollTo().assertContentDescriptionEquals("Rendered page preview, resized for display")
                assertEquals(draft,graph.store.editablePages.load(doc,imported.pageId).recipe)
                assertEquals(1,f.providerOpens); assertArrayEquals(png,graph.store.committedSources.load(imported.sourceId).file.readBytes())
            } finally {
                compose.runOnUiThread { compose.activity.setContent {}; session.close() }
                job.cancelAndJoin(); compose.waitUntil(10_000) { graph.budget.usedBytes == 0L }
            }
        }
    }

    private fun waitFor(session: EditorSession, recipe: Recipe, view: EditorView) {
        compose.waitUntil(10_000) { session.state.value.view == view && session.preview.value.request?.recipe == recipe && session.preview.value.display?.request == session.preview.value.request && !session.preview.value.processing && !session.preview.value.previous }
    }
    private fun assertPixels(session: EditorSession, width: Int, height: Int, expected: IntArray) {
        val bitmap = checkNotNull(session.preview.value.display).bitmap
        assertEquals(width,bitmap.width); assertEquals(height,bitmap.height)
        val pixels = IntArray(width*height); bitmap.getPixels(pixels,0,width,0,0,width,height); assertArrayEquals(expected,pixels)
    }
}
