package app.pristinepage

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

    @Test fun addingAppendsAndRemovingTheLastSelectionIsRefused() {
        val initial = AtelierSettings(ocrLanguages = listOf(OcrLanguage.FRENCH))
        assertEquals(initial, initial.withOcrLanguage(OcrLanguage.FRENCH, enabled = false))
        val added = initial.withOcrLanguage(OcrLanguage.JAPANESE, enabled = true)
        assertEquals(listOf(OcrLanguage.FRENCH, OcrLanguage.JAPANESE), added.ocrLanguages)
        assertEquals(listOf(OcrLanguage.JAPANESE), added.withOcrLanguage(OcrLanguage.FRENCH, enabled = false).ocrLanguages)
    }
}
