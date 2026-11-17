package app.pristinepage

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.data.model.*
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual consuming editor-session/Application/Room/render/display/save/reopen path. This is not a visible Compose/picker/grant journey; no source or fixture is deleted. */
@RunWith(AndroidJUnit4::class)
class EditorSessionIntegrationTest {
    @Test fun editorApplyPersistsItsDraftThenReopenDisplaysTheExactRotatedOriginal() = runBlocking {
        val png = Bitmap.createBitmap(intArrayOf(5,15,25,35,45,55).map { gray -> (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray }.toIntArray(),3,2,Bitmap.Config.ARGB_8888).let { image -> try { ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray() } finally { image.recycle() } }
        DocumentCatalogIntegrationTest.Fixture(png).use { f ->
            var graph = f.open(); val doc = graph.store.documents.createDocument("Consuming editor fixture"); val batch = graph.store.imports.beginImport(doc,1,null)
            val imported = graph.photoImports.importProviderReferences(listOf(f.good),batch.parentId).single() as IngestResult.Committed; f.forbid = true
            val original = graph.store.committedSources.load(imported.sourceId).file
            suspend fun visit(edit: Boolean) {
                val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Main.immediate)
                val session = withContext(Dispatchers.Main) { openSession(graph,doc,imported.pageId,scope) }
                try {
                    waitState(session) { field<EditablePage?>(it,"Page") != null }
                    if (edit) {
                        withContext(Dispatchers.Main) { call(session,"rotateClockwise") }
                        waitState(session) { field<Recipe?>(it,"Draft")?.geometry?.outputQuarterTurns == 1 }
                    }
                    val preview = waitPreview(session) { state -> field<Any?>(state,"Display") != null && field<app.pristinepage.core.pipeline.RenderRequest?>(state,"Request")?.recipe?.geometry?.outputQuarterTurns == 1 }
                    val display = field<Any>(preview,"Display"); val pixels = type("app.pristinepage.image.preview.RenderedPreviewBitmap").getMethod("getBitmap").invoke(display) as Bitmap
                    assertEquals(2,pixels.width); assertEquals(3,pixels.height)
                    val actual = IntArray(6); pixels.getPixels(actual,0,2,0,0,2,3)
                    assertArrayEquals(intArrayOf(35,5,45,15,55,25).map { gray -> (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray }.toIntArray(),actual)
                    if (edit) {
                        val before = graph.store.editablePages.load(doc,imported.pageId); assertEquals(0,before.recipe.geometry.outputQuarterTurns)
                        withContext(Dispatchers.Main) { call(session,"apply") }
                        val saved = waitState(session) { !field<Boolean>(it,"Saving") && !field<Boolean>(it,"Dirty") }
                        assertNull(field<OperationError?>(saved,"Error")); assertEquals(2L,field<EditablePage>(saved,"Page").recipeRevision)
                    } else assertFalse(field<Boolean>(state(session),"Dirty"))
                    assertEquals(1,f.providerOpens); assertArrayEquals(png,original.readBytes())
                } finally { withContext(Dispatchers.Main) { (session as AutoCloseable).close() }; job.cancelAndJoin() }
                assertEquals(0L,graph.budget.usedBytes)
            }
            visit(true); graph = f.reopen(); visit(false)
            val persisted = graph.store.editablePages.load(doc,imported.pageId)
            assertEquals(1,(persisted.recipe.geometry as GeometryRecipe.None).outputQuarterTurns); assertEquals(2L,persisted.recipeRevision); assertEquals(1,f.providerOpens)
        }
    }

    private fun openSession(graph: ApplicationImports, doc: DocumentId, page: PageId, scope: CoroutineScope): Any {
        val open: suspend () -> ApplicationImports = { graph }
        val actions = type("app.pristinepage.editor.ApplicationEditorActions").getConstructor(kotlin.jvm.functions.Function1::class.java).newInstance(open)
        val converterType = type("app.pristinepage.image.preview.RenderedPreviewBitmaps")
        val converter = type("app.pristinepage.image.preview.RenderedPreviewBitmapLoader").getConstructor(ImageAllocationBudget::class.java).newInstance(graph.budget)
        return type("app.pristinepage.editor.EditorSession").getConstructor(String::class.java,String::class.java,type("app.pristinepage.editor.EditorActions"),RenderCoordinator::class.java,CoroutineScope::class.java,converterType).newInstance(doc.value,page.value,actions,graph.renderer,scope,converter)
    }
    @Suppress("UNCHECKED_CAST") private fun flow(session: Any, property: String) = session.javaClass.getMethod("get$property").invoke(session) as StateFlow<Any>
    private fun state(session: Any) = flow(session,"State").value
    private suspend fun waitState(session: Any, predicate: (Any) -> Boolean): Any = withTimeout(10_000) { flow(session,"State").first(predicate) }
    private suspend fun waitPreview(session: Any, predicate: (Any) -> Boolean): Any = withTimeout(10_000) { flow(session,"Preview").first(predicate) }
    private fun call(session: Any, name: String) { try { session.javaClass.getMethod(name).invoke(session) } catch (error: InvocationTargetException) { throw error.targetException } }
    private fun type(name: String): Class<*> = try { Class.forName(name) } catch (_: ClassNotFoundException) { throw AssertionError("Consuming editor Application integration is absent") }
    @Suppress("UNCHECKED_CAST") private fun <T> field(value: Any, name: String) = value.javaClass.getMethod("get$name").invoke(value) as T
}
