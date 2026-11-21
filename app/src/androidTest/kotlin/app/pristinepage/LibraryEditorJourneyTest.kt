package app.pristinepage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.data.model.*
import app.pristinepage.editor.EditorViewModel
import app.pristinepage.library.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real default Activity content and Application graph. Only the synthetic private byte input is debug-only; this is not a system picker/temporary-grant journey. All fixture/original data remains retained. */
@RunWith(AndroidJUnit4::class)
class LibraryEditorJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nonfirstPageAppliesAndRecreatesThroughDefaultNavigationThenReturnsToThatSamePage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nonce = UUID.randomUUID().toString()
        val authority = "${context.packageName}.editor-fixtures"
        assertNotNull("Private default editor journey input is absent",context.packageManager.resolveContentProvider(authority,0))
        val root = File(context.cacheDir,"editor-journey-fixtures"); check(root.isDirectory || root.mkdirs())
        val file = File(root,"$nonce.png"); check(file.createNewFile())
        val png = OriginalPreviewIntegrationTest.asymmetricPng(); file.writeBytes(png)
        val graph = (context.applicationContext as PristinePageApplication).services.imports()
        val doc = graph.store.documents.createDocument("Default editor journey $nonce")
        val batch = graph.store.imports.beginImport(doc,1,null)
        val pages = graph.photoImports.importProviderReferences(listOf("content://$authority/$nonce","content://$authority/$nonce"),batch.parentId).map { it as IngestResult.Committed }
        val second = pages[1]
        val library = compose.runOnUiThread { ViewModelProvider(compose.activity)[ImportLibraryViewModel::class.java].session }
        compose.waitUntil(10_000) { !library.state.value.loading }
        // Fixture seeding addresses a document by opaque ID. From its real default overview onward all navigation/actions below use the actual MainActivity UI, never setContent or an injected graph.
        compose.runOnUiThread { library.accept(LibraryEvent.Open(doc)) }
        compose.waitUntil(10_000) { library.state.value.document?.pages?.size == 2 && !library.state.value.loading }
        compose.onNodeWithTag("original-page-${second.pageId.value}").performClick()
        val host = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        compose.waitUntil(10_000) { host.session.value?.preview?.value?.display != null }
        assertEquals(second.pageId,host.session.value!!.state.value.page!!.pageId)
        compose.onNodeWithTag("editor-tool-rotate").performClick()
        compose.onNodeWithTag("editor-rotate").performScrollTo().performClick()
        val session = checkNotNull(host.session.value)
        compose.onNodeWithTag("editor-apply").performClick()
        compose.waitUntil(10_000) { session.state.value.page?.recipeRevision == 2L && !session.state.value.dirty }
        assertSame(session, host.session.value)
        compose.onNodeWithTag("editor-rotate").assertDoesNotExist()
        compose.onNodeWithTag("editor-tool-rotate").assertIsNotSelected()
        compose.onNodeWithTag("editor-tool-rotate").performClick()
        compose.onNodeWithTag("editor-apply").assertIsEnabled().performClick()
        compose.onNodeWithTag("editor-rotate").assertDoesNotExist()
        assertEquals(2L, graph.store.editablePages.load(doc, second.pageId).recipeRevision)
        val accepted = checkNotNull(session.state.value.draft)
        val paper = accepted.copy(appearance = AppearanceRecipe.PaperGainV1())
        compose.onNodeWithTag("editor-tool-looks").performClick()
        compose.onNodeWithTag("editor-looks-row").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("editor-look-binary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("editor-look-paper").performScrollTo().performClick().assertIsSelected()
        compose.waitUntil(10_000) {
            val preview = session.preview.value
            !preview.processing && !preview.previous && preview.display?.request == preview.request && preview.request?.recipe == paper
        }
        val preview = checkNotNull(session.preview.value.preview)
        assertEquals(second.sourceId.value, preview.request.sourceId)
        assertEquals(listOf("source_decode_v1", "geometry_none_v1", "paper_gain_v1"), preview.stages.map { it.stageId })
        assertEquals(accepted, graph.store.editablePages.load(doc, second.pageId).recipe)
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        val retained = compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        assertSame(host,retained); assertSame(session,retained.session.value); assertTrue(session.state.value.dirty)
        compose.onNodeWithTag("editor-back").performClick()
        compose.onNodeWithTag("editor-discard-exit").performClick()
        compose.waitUntil(10_000) { retained.session.value == null && !library.state.value.loading }
        try { compose.onNodeWithTag("original-page-${second.pageId.value}").assertIsSelected() }
        catch (error: AssertionError) { throw AssertionError("Library editor selection context is absent",error) }
        compose.onNodeWithTag("original-page-${second.pageId.value}").performClick()
        compose.waitUntil(10_000) { retained.session.value?.state?.value?.page != null }
        val reopened = checkNotNull(retained.session.value)
        assertEquals(second.pageId,reopened.state.value.page!!.pageId); assertFalse(reopened.state.value.dirty)
        assertEquals(1,reopened.state.value.draft!!.geometry.outputQuarterTurns)
        assertEquals(1L,graph.store.editablePages.load(doc,pages[0].pageId).recipeRevision)
        assertEquals(2L,graph.store.editablePages.load(doc,second.pageId).recipeRevision)
        assertArrayEquals(png,graph.store.committedSources.load(second.sourceId).file.readBytes())
        compose.onNodeWithTag("editor-back").performClick()
        compose.waitUntil(10_000) { retained.session.value == null }
        assertArrayEquals(png,file.readBytes())
    }
}
