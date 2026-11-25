package app.pristinepage

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import app.pristinepage.data.model.*
import app.pristinepage.editor.EditorContent
import app.pristinepage.editor.EditorIdentity
import app.pristinepage.editor.EditorViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity/ViewModelStore recreation with an explicitly private synthetic graph. This checks the retained editor owner, not picker grants or default-library entry. No source/fixture data is deleted. */
@RunWith(AndroidJUnit4::class)
class EditorHostTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun retainedEditorSurvivesRecreationAndBackgroundWithoutSavingOrRetainingDisplayAllocations() = runBlocking {
        DocumentCatalogIntegrationTest.Fixture(OriginalPreviewIntegrationTest.asymmetricPng()).use { f ->
            val services = f.services()
            val graph = f.open(services); val doc = graph.store.documents.createDocument("Retained editor fixture"); val batch = graph.store.imports.beginImport(doc,1,null)
            val page = graph.photoImports.importProviderReferences(listOf(f.good),batch.parentId).single() as IngestResult.Committed; f.forbid = true
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EditorViewModel(services) { _, _, _ -> } as T
            }
            val type = EditorViewModel::class.java
            val model = compose.runOnUiThread { ViewModelProvider(compose.activity,factory)["fixture-editor",type].also { it.open(EditorIdentity(doc, page.pageId, graph.activation)) } }
            val sessions = model.session
            compose.waitUntil(10_000) { sessions.value?.state?.value?.page != null }
            val session = sessions.value!!
            val currentIdentity = checkNotNull(model.state.value.identity)
            var refusedTargetSelected = false
            compose.runOnUiThread {
                model.open(currentIdentity.copy(pageId = PageId(java.util.UUID.randomUUID().toString()))) { refusedTargetSelected = true }
            }
            compose.waitUntil(10_000) { !model.state.value.loading && model.state.value.error != null }
            assertSame(session, sessions.value)
            assertEquals(currentIdentity, model.state.value.identity)
            assertFalse(refusedTargetSelected)
            fun show() = compose.runOnUiThread { compose.activity.setContent { MaterialTheme { (Class.forName("app.pristinepage.editor.PageEditorScreen").getConstructor().newInstance() as EditorContent).Content(session) {} } } }
            try {
                show(); compose.waitUntil(10_000) { session.preview.value.display != null }
                compose.onNodeWithTag("editor-tool-rotate").performClick()
                compose.onNodeWithTag("editor-rotate").performScrollTo().performClick()
                compose.waitUntil(10_000) { session.state.value.dirty && session.preview.value.display != null && !session.preview.value.processing }
                // ActivityScenario's helper-Activity stop failed to cover this Activity on the retained API28 run. Move the actual task back instead, and prove real STOP before testing release; never synthesize Lifecycle events to make the owner assertions pass.
                val activity = compose.activity
                assertTrue(compose.runOnUiThread { activity.moveTaskToBack(true) })
                withTimeout(10_000) { while (withContext(Dispatchers.Main) { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) } || graph.budget.usedBytes != 0L) delay(20) }
                assertEquals(Lifecycle.State.CREATED,compose.runOnUiThread { activity.lifecycle.currentState })
                assertNull(session.preview.value.display); assertTrue(session.state.value.dirty)
                // The helper-based resume also failed while the real target remained STOPPED. Use the approved package-scoped shell foreground stimulus and require this same Activity to resume, rather than modifying product lifecycle code or accepting a synthesized transition.
                val command = "am start --activity-reorder-to-front --activity-single-top -n ${activity.componentName.flattenToString()}"
                val launch = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
                assertTrue(launch,launch.contains("Starting: Intent")); assertFalse(launch,launch.contains("Error:"))
                withTimeout(10_000) { while (withContext(Dispatchers.Main) { activity.lifecycle.currentState != Lifecycle.State.RESUMED }) delay(20) }
                assertSame(activity,compose.activity)
                compose.waitUntil(10_000) { session.preview.value.display != null }
                compose.activityRule.scenario.recreate(); compose.waitForIdle()
                val retained = compose.runOnUiThread { ViewModelProvider(compose.activity,factory)["fixture-editor",type] }
                assertSame(model,retained); assertSame(session,sessions.value); show()
                compose.waitUntil(10_000) { session.preview.value.display != null }
                assertEquals(1,session.state.value.draft!!.geometry.outputQuarterTurns); assertTrue(session.state.value.dirty)
                assertEquals(1L,graph.store.editablePages.load(doc,page.pageId).recipeRevision)
            } finally {
                compose.runOnUiThread { compose.activity.setContent {}; model.closeEditor() }
                compose.waitUntil(10_000) { graph.budget.usedBytes == 0L }
            }
            assertNull(sessions.value); assertEquals(1,f.providerOpens)
        }
    }
}
