package app.pristinepage.core.library

import app.pristinepage.core.pipeline.requireWellFormedUnicode
import java.text.Normalizer
import java.util.UUID

/** A stable display filename for a scan slot. Neither provider hints nor allocated labels are filesystem paths. */
object LibraryScanName {
    private const val MAX_LENGTH = 160
    private const val MAX_PREFERRED_ATTEMPTS = 100

    fun forIngest(displayName: String?, pageId: String, mime: String, occupied: Collection<String>): String =
        forIngest(displayName, pageId, mime, occupied.mapTo(HashSet(), ::comparisonKey)::contains)

    /** The caller tests comparison keys in its album index, without loading removed or live page names. */
    fun forIngest(displayName: String?, pageId: String, mime: String, containsKey: (String) -> Boolean): String {
        val fallback = forUnnamedPage(pageId, mime)
        val label = displayName?.takeIf { it.length <= MAX_LENGTH }?.trim()?.takeIf(::isValid)
        return label?.let { availableVariant(it, containsKey) }
            ?: availableVariant(fallback, containsKey)
            ?: throw IllegalArgumentException("No distinct logical scan name is available")
    }

    private fun forUnnamedPage(pageId: String, mime: String): String {
        require(UUID.fromString(pageId).toString() == pageId)
        val preferredExtension = when (mime) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> throw IllegalArgumentException("Unsupported scan media type")
        }
        return "Scan $pageId$preferredExtension"
    }

    private fun availableVariant(label: String, containsKey: (String) -> Boolean): String? {
        if (!containsKey(comparisonKey(label))) return label
        val dot = label.lastIndexOf('.')
        val extension = if (dot > 0 && label.length - dot in 2..16) label.substring(dot) else ""
        val basename = label.dropLast(extension.length)
        for (index in 2..MAX_PREFERRED_ATTEMPTS) {
            val suffix = " ($index)$extension"
            var stem = basename.take(MAX_LENGTH - suffix.length)
            if (stem.lastOrNull()?.isHighSurrogate() == true) stem = stem.dropLast(1)
            val candidate = stem.trimEnd() + suffix
            if (!containsKey(comparisonKey(candidate))) return candidate
        }
        return null
    }

    internal fun isValid(filename: String): Boolean {
        if (filename.length !in 1..MAX_LENGTH || filename.isBlank() || filename != filename.trim() ||
            filename == "." || filename == ".." || filename.any { it == '/' || it == '\\' || it.isISOControl() }) return false
        return try { requireWellFormedUnicode(filename); true } catch (_: IllegalArgumentException) { false }
    }

    /** Derive the persisted comparison key from a validated logical filename, never from caller-supplied SQL collation data. */
    fun comparisonKey(filename: String): String {
        require(isValid(filename))
        return libraryComparisonKey(filename)
    }
}

// Compare NFC plus locale-independent one-code-point case equivalence (including final sigma). Keep dotted and dotless I distinct and do not expand characters into multiple letters.
internal fun libraryComparisonKey(label: String): String {
    val normalized = Normalizer.normalize(label, Normalizer.Form.NFC)
    val key = StringBuilder(normalized.length)
    var index = 0
    while (index < normalized.length) {
        val codePoint = normalized.codePointAt(index)
        key.appendCodePoint(when (codePoint) {
            0x0130, 0x0131 -> codePoint
            else -> Character.toLowerCase(Character.toUpperCase(codePoint))
        })
        index += Character.charCount(codePoint)
    }
    return Normalizer.normalize(key, Normalizer.Form.NFC)
}
