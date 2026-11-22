package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireWellFormedUnicode
import java.lang.Character.UnicodeScript
import java.text.Normalizer

private val OCR_WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*")
private val HALF_WIDTH_KANA = Regex("[\uFF66-\uFF9F]+")

private fun normalizeForSearch(text: String): String {
    // Compose kana + voice marks before classifying scripts. Compatibility-fold only kana, not arbitrary characters in the page.
    val folded = HALF_WIDTH_KANA.replace(text) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }
    return Normalizer.normalize(folded, Normalizer.Form.NFC)
}

/** Segment unspaced East Asian runs for indexing only; the exact recognition is retained separately when changed. */
internal fun canonicalOcrSearchText(text: String): String =
    OCR_WORD.replace(normalizeForSearch(text)) { match -> searchSegments(match.value).joinToString(" ") }

/** One FTS phrase per CJK run requires ordered adjacent tokens. FTS ignores punctuation; Latin, RTL and Indic words keep ordinary word matching. */
private fun searchSegments(word: String): List<String> {
    val parts = ArrayList<String>(1)
    var current = StringBuilder(word.length)
    var eastAsian = false
    var offset = 0
    while (offset < word.length) {
        val point = word.codePointAt(offset)
        val mark = when (Character.getType(point)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
        if (!mark) {
            val east = isUnspacedEastAsian(point)
            if (current.isNotEmpty() && east != eastAsian) {
                parts.add(current.toString())
                current = StringBuilder()
            }
            if (current.isNotEmpty() && east) current.append(' ')
            eastAsian = east
        }
        current.appendCodePoint(point)
        offset += Character.charCount(point)
    }
    if (current.isNotEmpty()) parts.add(current.toString())
    return parts
}

private fun isUnspacedEastAsian(point: Int): Boolean =
    point == 0x30FC || when (UnicodeScript.of(point)) {
        UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HANGUL -> true
        else -> false
    }

/** Only quoted literal tokens cross into the bound FTS MATCH expression; the query is never persisted or logged. */
internal class OcrSearchTerms private constructor(val expression: String) {
    companion object {
        fun create(query: String): OcrSearchTerms? {
            if (query.length > AlbumSearchQuery.MAX_LENGTH || '\u0000' in query) return null
            try { requireWellFormedUnicode(query) } catch (_: IllegalArgumentException) { return null }
            val literals = OCR_WORD.findAll(normalizeForSearch(query)).flatMap { searchSegments(it.value).asSequence() }.map { "\"$it\"" }.toList()
            return if (literals.isEmpty()) null else OcrSearchTerms(literals.joinToString(" "))
        }
    }
}
