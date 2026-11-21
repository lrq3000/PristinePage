package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireWellFormedUnicode
import java.text.Normalizer

internal fun canonicalOcrSearchText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

/** Only quoted literal tokens cross into the bound FTS MATCH expression; the query is never persisted or logged. */
internal class OcrSearchTerms private constructor(val expression: String) {
    companion object {
        private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*")

        fun create(query: String): OcrSearchTerms? {
            if (query.length > AlbumSearchQuery.MAX_LENGTH || '\u0000' in query) return null
            try { requireWellFormedUnicode(query) } catch (_: IllegalArgumentException) { return null }
            val normalized = canonicalOcrSearchText(query)
            val literals = TOKEN.findAll(normalized).map { "\"${it.value}\"" }.toList()
            return if (literals.isEmpty()) null else OcrSearchTerms(literals.joinToString(" "))
        }
    }
}
