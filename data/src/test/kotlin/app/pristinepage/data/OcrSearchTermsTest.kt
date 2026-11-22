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
        assertEquals(""""École" "48" "25" "東 京"""", terms.expression)
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

    @Test fun contiguousCjkInputIsSearchableAsAnOrderedPhraseWithoutChangingTheOriginal() {
        val input = OcrInput("aaaaaaaa-aaaa-4aaa-8aaa-000000000004", 1, "a".repeat(64))
        val engine = OcrConfiguration("fixture", 1, OcrLayout.AUTOMATIC)
        val original = "東京都の領収書です Invoice한글"
        val stored = OcrResultRow.fromRecognition(input, engine, OcrText(original))
        assertEquals("東 京 都 の 領 収 書 で す Invoice 한 글", stored.indexedText)
        assertEquals(original, stored.recognizedText())
        assertEquals(""""領 収 書" "invoice" "한 글"""", OcrSearchTerms.create("領収書 invoice한글")?.expression)
    }

    @Test fun segmentationKeepsSupplementaryCharactersAndKanaLengthMarksWhole() {
        val text = "\uD840\uDC00コーヒー"
        assertEquals(""""𠀀 コ ー ヒ ー"""", OcrSearchTerms.create(text)?.expression)
    }

    @Test fun halfWidthKanaVoiceMarksStayWithTheBaseInSearchButVerbatimStaysUntouched() {
        val input = OcrInput("aaaaaaaa-aaaa-4aaa-8aaa-000000000004", 1, "a".repeat(64))
        val engine = OcrConfiguration("fixture", 1, OcrLayout.AUTOMATIC)
        val original = "ｶﾞﾊﾟ ①"
        val stored = OcrResultRow.fromRecognition(input, engine, OcrText(original))
        assertEquals("ガ パ ①", stored.indexedText)
        assertEquals(original, stored.recognizedText())
        assertEquals(""""ガ パ"""", OcrSearchTerms.create("ｶﾞﾊﾟ")?.expression)
        assertEquals(""""ガ パ"""", OcrSearchTerms.create("ガパ")?.expression)
    }
}
