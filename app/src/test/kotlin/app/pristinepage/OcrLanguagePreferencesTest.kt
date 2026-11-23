package app.pristinepage

import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.design.AtelierSettings
import app.pristinepage.design.OcrLanguage
import org.junit.Assert.*
import org.junit.Test

class OcrLanguagePreferencesTest {
    @Test fun defaultsIncludeEnglishAndFrenchAndOfferTwentyDistinctChoices() {
        assertEquals(listOf(OcrLanguage.ENGLISH, OcrLanguage.FRENCH), AtelierSettings.fromStored(emptyMap<String, String>()).ocrLanguages)
        assertTrue(OcrLanguage.entries.map { it.packCode }.distinct().size >= 20)
    }

    @Test fun existingPreferencesAndOrderedLanguagesSurviveReadingOldOrMixedValues() {
        val stored = mapOf("ocrLanguages" to "jpn,custom-model,eng,jpn,ara", "grid" to false)
        val settings = AtelierSettings.fromStored(stored)
        assertEquals(listOf(OcrLanguage.JAPANESE, OcrLanguage.ENGLISH, OcrLanguage.ARABIC), settings.ocrLanguages)
        assertFalse(settings.showCameraGrid)
    }

    @Test fun layoutDefaultsToAutomaticWithoutResettingExistingLanguageChoices() {
        val old = mapOf("ocrLanguages" to "jpn,eng", "grid" to false)
        for (values in listOf(old, old + ("ocrLayout" to "UNKNOWN"), old + ("ocrLayout" to 2))) {
            val settings = AtelierSettings.fromStored(values)
            assertEquals(OcrLayout.AUTOMATIC, settings.ocrLayout)
            assertEquals(listOf(OcrLanguage.JAPANESE, OcrLanguage.ENGLISH), settings.ocrLanguages)
            assertFalse(settings.showCameraGrid)
        }
        val selected = AtelierSettings.fromStored(old + ("ocrLayout" to "SPARSE_TEXT"))
        assertEquals(OcrLayout.SPARSE_TEXT, selected.ocrLayout)
        assertEquals(OcrLayout.SPARSE_TEXT, selected.withOcrLanguage(OcrLanguage.FRENCH, true).ocrLayout)
    }

    @Test fun addingAppendsAndRemovingTheLastSelectionIsRefused() {
        val initial = AtelierSettings(ocrLanguages = listOf(OcrLanguage.FRENCH))
        assertEquals(initial, initial.withOcrLanguage(OcrLanguage.FRENCH, enabled = false))
        val added = initial.withOcrLanguage(OcrLanguage.JAPANESE, enabled = true)
        assertEquals(listOf(OcrLanguage.FRENCH, OcrLanguage.JAPANESE), added.ocrLanguages)
        assertEquals(listOf(OcrLanguage.JAPANESE), added.withOcrLanguage(OcrLanguage.FRENCH, enabled = false).ocrLanguages)
    }
}
