package app.pristinepage

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.model.*
import app.pristinepage.editor.*
import app.pristinepage.image.preview.RenderedPreviewBitmapLoader
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Compose editor controls and real private Application/Room/renderer/display/CAS. The fixture replaces Activity content, not system picker permissions or the default-library navigation path. */
@RunWith(AndroidJUnit4::class)
class EditorScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun visibleRotateAndApplySaveARecipeThatReopensWithoutChangingTheOriginal() = runBlocking {
        withFixture { f, graph, doc, page, scope ->
            val screen = screen()
            val session = withContext(Dispatchers.Main) { EditorSession(doc.value,page.pageId.value,ApplicationEditorActions { graph },graph.renderer,scope,RenderedPreviewBitmapLoader(graph.budget)) }
            try {
                compose.runOnUiThread { compose.activity.setContent { MaterialTheme { screen.Content(session) {} } } }
                ready(); compose.onNodeWithTag("editor-tool-rotate").performClick()
                compose.onNodeWithTag("editor-rotate").performScrollTo().performClick()
                compose.waitUntil(10_000) { session.state.value.draft?.geometry?.outputQuarterTurns == 1 && session.preview.value.request?.recipe?.geometry?.outputQuarterTurns == 1 && session.preview.value.display != null && !session.preview.value.processing }
                compose.onNodeWithTag("editor-preview").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("editor-unsaved").assertExists()
                assertEquals(0,graph.store.editablePages.load(doc,page.pageId).recipe.geometry.outputQuarterTurns)
                compose.onNodeWithTag("editor-apply").performClick()
                compose.waitUntil(10_000) { session.state.value.page?.recipeRevision == 2L && !session.state.value.dirty && !session.state.value.saving }
                compose.onNodeWithTag("editor-unsaved").assertDoesNotExist(); assertEquals(1,graph.store.editablePages.load(doc,page.pageId).recipe.geometry.outputQuarterTurns)
                assertEquals(1,f.providerOpens); assertArrayEquals(f.png,graph.store.committedSources.load(page.sourceId).file.readBytes())
            } finally { compose.runOnUiThread { compose.activity.setContent {}; session.close() }; compose.waitForIdle() }
            compose.waitUntil(10_000) { graph.budget.usedBytes == 0L }
            val reopened = f.reopen(); assertEquals(1,reopened.store.editablePages.load(doc,page.pageId).recipe.geometry.outputQuarterTurns); assertEquals(1,f.providerOpens)
        }
    }

    @Test fun backFromAnUnsavedLookOffersKeepEditingOrDiscardInsteadOfSilentlySaving() = runBlocking {
        withFixture { _, graph, doc, page, scope ->
            val screen = screen(); var exited = false
            val session = withContext(Dispatchers.Main) { EditorSession(doc.value,page.pageId.value,ApplicationEditorActions { graph },graph.renderer,scope,RenderedPreviewBitmapLoader(graph.budget)) }
            try {
                compose.runOnUiThread { compose.activity.setContent { MaterialTheme { screen.Content(session) { exited = true } } } }
                ready(); compose.onNodeWithTag("editor-tool-looks").performClick()
                compose.onNodeWithTag("editor-look-paper").performScrollTo().performClick()
                compose.onNodeWithTag("editor-back").performScrollTo().performClick(); compose.onNodeWithTag("editor-exit-choice").assertExists()
                compose.onNodeWithTag("editor-keep-editing").performClick(); compose.onNodeWithTag("editor-exit-choice").assertDoesNotExist()
                assertTrue(session.state.value.dirty); assertEquals(1L,graph.store.editablePages.load(doc,page.pageId).recipeRevision)
                compose.onNodeWithTag("editor-back").performScrollTo().performClick(); compose.onNodeWithTag("editor-discard-exit").performClick()
                compose.waitUntil(10_000) { exited && graph.budget.usedBytes == 0L }; assertEquals(1L,graph.store.editablePages.load(doc,page.pageId).recipeRevision)
            } finally { compose.runOnUiThread { compose.activity.setContent {}; session.close() }; compose.waitForIdle() }
        }
    }

    private fun ready() { compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor-preview").fetchSemanticsNodes().isNotEmpty() } }
    private fun screen(): EditorContent = try { Class.forName("app.pristinepage.editor.PageEditorScreen").getConstructor().newInstance() as EditorContent }
    catch (_: ClassNotFoundException) { throw AssertionError("Visible recipe editor is absent") }
    catch (error: InvocationTargetException) { throw error.targetException }
    private suspend fun withFixture(body: suspend (DocumentCatalogIntegrationTest.Fixture,ApplicationImports,DocumentId,IngestResult.Committed,CoroutineScope) -> Unit) {
        DocumentCatalogIntegrationTest.Fixture(OriginalPreviewIntegrationTest.asymmetricPng()).use { f ->
            val graph = f.open(); val doc = graph.store.documents.createDocument("Visible edited fixture"); val batch = graph.store.imports.beginImport(doc,1,null)
            val page = graph.photoImports.importProviderReferences(listOf(f.good),batch.parentId).single() as IngestResult.Committed; f.forbid = true
            val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Main.immediate)
            try { body(f,graph,doc,page,scope) } finally { job.cancelAndJoin() }
        }
    }
}
