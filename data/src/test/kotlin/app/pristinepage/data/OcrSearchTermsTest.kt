package app.pristinepage.data

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.db.OcrResultRow
import app.pristinepage.data.repository.OcrSearchTerms
import org.junit.Assert.*
import org.junit.Test

class OcrSearchTermsTest {
    @Test fun searchOperatorsCannotBecomeFtsGrammar() {
        val terms = OcrSearchTerms.create("""total" OR amount NEAR* paid""")!!
        assertEquals(""""total" "OR" "amount" "NEAR" "paid"""", terms.expression)
        assertFalse(terms.toString().contains("total"))
    }

    @Test fun canonicalEquivalentTextAndScriptsSurviveTokenization() {
        val terms = OcrSearchTerms.create("E\u0301cole 48.25 東京")!!
        assertEquals(""""École" "48" "25" "東京"""", terms.expression)
    }

    @Test fun noMatchExpressionForEmptyOrMalformedInput() {
        assertNull(OcrSearchTerms.create(" — … + + "))
        assertNull(OcrSearchTerms.create("x".repeat(161)))
        assertNull(OcrSearchTerms.create("valid\uD800"))
        assertNull(OcrSearchTerms.create("valid\u0000page"))
    }

    @Test fun indexedTextUsesTheSameNormalizationWithoutLosingVerbatimOutput() {
        val input = OcrInput("aaaaaaaa-aaaa-4aaa-8aaa-000000000004", 1, "a".repeat(64))
        val engine = OcrConfiguration("fixture", 1, OcrLayout.AUTOMATIC)
        val original = "o\u0323\u0302"
        val decomposed = OcrResultRow.fromRecognition(input, engine, OcrText(original))
        val composed = OcrResultRow.fromRecognition(input, engine, OcrText("ộ"))
        assertEquals("ộ", decomposed.indexedText)
        assertEquals(original, decomposed.recognizedText())
        assertEquals("ộ", composed.recognizedText())
        assertNull(composed.verbatimText)
        assertEquals("\"${decomposed.indexedText}\"", OcrSearchTerms.create(original)?.expression)
    }
}
