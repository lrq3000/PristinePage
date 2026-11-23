package app.pristinepage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.model.AlbumOrder
import app.pristinepage.design.AtelierPreferences
import app.pristinepage.design.AtelierSettings
import app.pristinepage.design.HomeThumbnailLayout
import app.pristinepage.design.OcrLanguage
import app.pristinepage.library.AlbumTitleTemplate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtelierPreferencesTest {
    @Test fun diagnosticsPersistWithoutChangingTheStoredRecognitionOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-ocr-diagnostics-${UUID.randomUUID()}"
        val preferences = AtelierPreferences(context, name)
        assertEquals(false, preferences.settings.value.ocrDiagnostics)
        preferences.setOcrLanguage(OcrLanguage.JAPANESE, true)
        preferences.setOcrDiagnostics(true)
        val reopened = AtelierPreferences(context, name)
        assertEquals(true, reopened.settings.value.ocrDiagnostics)
        assertEquals(listOf(OcrLanguage.ENGLISH, OcrLanguage.FRENCH, OcrLanguage.JAPANESE), reopened.settings.value.ocrLanguages)
        reopened.setOcrDiagnostics(false)
        assertEquals(false, AtelierPreferences(context, name).settings.value.ocrDiagnostics)
    }

    @Test fun savedOcrLanguagesAreIndependentOfTheCameraAndRetainSelectionOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-ocr-prefs-${UUID.randomUUID()}"
        try {
            val preferences = AtelierPreferences(context, name)
            preferences.setShowCameraGrid(false)
            preferences.setOcrLanguage(OcrLanguage.JAPANESE, true)
            preferences.setOcrLanguage(OcrLanguage.ARABIC, true)
            preferences.setOcrLanguage(OcrLanguage.FRENCH, false)
            val reopened = AtelierPreferences(context, name).settings.value
            assertEquals(listOf(OcrLanguage.ENGLISH, OcrLanguage.JAPANESE, OcrLanguage.ARABIC), reopened.ocrLanguages)
            assertEquals(false, reopened.showCameraGrid)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun aNewPreferenceOwnerReadsTheLastSavedNameTemplate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-atelier-${UUID.randomUUID()}"
        try {
            val pattern = "Receipts_%date_%time"
            AtelierPreferences(context, name).setAlbumTitleTemplate(checkNotNull(AlbumTitleTemplate.parse(pattern)))
            assertEquals(pattern, AtelierPreferences(context, name).settings.value.albumTitleTemplate.pattern)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun ANewOwnerReadsTheSameSettingsAndIgnoresAnInvalidTypeIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-atelier-${UUID.randomUUID()}"
        try {
            val original = AtelierPreferences(context, name)
            original.setOrder(AlbumOrder.TITLE_ZA)
            original.setShowCreationInNameSort(true)
            original.setShowCameraGrid(false)
            original.setHomeThumbnailLayout(HomeThumbnailLayout.ROW)
            val reopened = AtelierPreferences(context, name)
            assertEquals(AtelierSettings(AlbumOrder.TITLE_ZA, true, false, HomeThumbnailLayout.ROW), reopened.settings.value)

            // One malformed setting must not erase the user's other choices.
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putInt("order", -1).commit()
            assertEquals(AtelierSettings(AlbumOrder.MODIFIED, true, false, HomeThumbnailLayout.ROW),
                AtelierPreferences(context, name).settings.value)

            reopened.setHomeThumbnailLayout(HomeThumbnailLayout.STACK)
            assertEquals(HomeThumbnailLayout.STACK, AtelierPreferences(context, name).settings.value.homeThumbnailLayout)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
