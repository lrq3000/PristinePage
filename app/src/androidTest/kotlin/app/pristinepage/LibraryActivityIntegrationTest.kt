package app.pristinepage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.library.LibrarySession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual launcher Activity/Compose/ViewModel recreation. Only explicit creation of one synthetic empty document is exercised; no picker/provider/grant, file deletion or OS process-death claim. */
@RunWith(AndroidJUnit4::class)
class LibraryActivityIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realLibraryActivityCreatesAnEmptyDocumentAndRetainsItsSessionAcrossRecreation() {
        try { compose.onNodeWithTag("create-document").assertIsDisplayed() }
        catch (failure: AssertionError) { throw AssertionError("Visible lifecycle library is absent", failure) }
        val beforeActivity = compose.activity
        val beforeModel = compose.runOnUiThread { model(beforeActivity) }
        val before = session(beforeModel)
        compose.waitUntil(10_000) { !before.state.value.loading }
        compose.onNodeWithTag("create-document").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { before.state.value.document != null && !before.state.value.loading }
        val document = before.state.value.document!!
        assertEquals(0, document.document.pageCount); assertTrue(document.pages.isEmpty())
        assertNull(before.state.value.request); assertNull(before.state.value.phase)
        compose.onNodeWithTag("document-overview").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        val afterModel = compose.runOnUiThread { model(compose.activity) }
        assertNotSame(beforeActivity, compose.activity); assertSame(beforeModel, afterModel); assertSame(before, session(afterModel))
        assertEquals(document.document.documentId, session(afterModel).identity().selected)
        compose.onNodeWithTag("document-overview").assertIsDisplayed()
    }

    private companion object {
        @Suppress("UNCHECKED_CAST") fun model(activity: MainActivity): ViewModel {
            val type = Class.forName("app.pristinepage.library.ImportLibraryViewModel") as Class<ViewModel>
            return ViewModelProvider(activity)[type]
        }
        fun session(model: ViewModel): LibrarySession = model.javaClass.getMethod("getSession").invoke(model) as LibrarySession
    }
}
