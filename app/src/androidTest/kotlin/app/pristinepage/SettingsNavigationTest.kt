package app.pristinepage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual Home gear and Activity routes without changing user preferences. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
