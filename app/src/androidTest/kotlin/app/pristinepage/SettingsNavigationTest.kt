package app.pristinepage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

/** Exercises the actual Home gear and Activity routes without changing user preferences. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun libraryBackupRequiresAnExplicitConfirmationAndCanBeCanceledInSettings() {
        val files = compose.activity.filesDir.canonicalFile
        fun attempts() = checkNotNull(files.list()).filter { it.startsWith("library-out-") }.toSet()
        val before = attempts()
        compose.onNodeWithTag("atelier-settings").performClick()
        compose.onNodeWithTag("settings-open-library_storage").performClick()
        compose.onNodeWithTag("settings-screen-library_storage").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("library-backup-export") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("library-backup-choose-folder").assertDoesNotExist()
        compose.onNodeWithTag("library-backup-export").performClick()
        compose.onNodeWithTag("library-backup-choose-folder").assertIsDisplayed()
        compose.onNodeWithTag("library-backup-cancel").performClick()
        compose.onNodeWithTag("library-backup-choose-folder").assertDoesNotExist()
        compose.onNodeWithTag("library-backup-completed").assertDoesNotExist()
        assertEquals(before, attempts())
    }

    @Test fun gearOpensStandaloneNestedSettingsAndBackReturnsToHome() {
        compose.onNodeWithTag("atelier-settings").performClick()
        compose.onNodeWithTag("settings-screen-root").assertIsDisplayed()
        compose.onNodeWithTag("atelier-album-list").assertDoesNotExist()
        compose.onNodeWithTag("settings-open-home").performClick()
        compose.onNodeWithTag("carnet-title-template").performClick()
        compose.onNodeWithTag("carnet-title-template-field").assertIsDisplayed()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("settings-open-ocr").performClick()
        compose.onNodeWithTag("settings-open-ocr_languages").performClick()
        compose.onNodeWithTag("settings-screen-ocr_languages").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings-screen-ocr_languages").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings-screen-ocr").assertIsDisplayed()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("atelier-album-list").assertIsDisplayed()
    }
}
