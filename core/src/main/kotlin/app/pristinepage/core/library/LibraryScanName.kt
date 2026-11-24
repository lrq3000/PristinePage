package app.pristinepage.core.library

import app.pristinepage.core.pipeline.requireWellFormedUnicode
import java.text.Normalizer
import java.util.UUID

/** A stable display filename for a scan slot. Neither provider hints nor allocated labels are filesystem paths. */
object LibraryScanName {
    private const val MAX_LENGTH = 160

    fun forIngest(displayName: String?, pageId: String, mime: String, occupied: Collection<String>): String {
        require(UUID.fromString(pageId).toString() == pageId)
        val preferredExtension = when (mime) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> throw IllegalArgumentException("Unsupported scan media type")
        }
        val label = displayName?.trim()?.takeIf(::isValid) ?: "Scan $pageId$preferredExtension"
        val used = HashSet<String>()
        for (name in occupied) {
            require(isValid(name))
            used += libraryComparisonKey(name)
        }
        if (libraryComparisonKey(label) !in used) return label
        val dot = label.lastIndexOf('.')
        val extension = if (dot > 0 && label.length - dot in 2..16) label.substring(dot) else ""
        val basename = label.dropLast(extension.length)
        for (index in 2..(used.size + 2)) {
            val suffix = " ($index)$extension"
            var stem = basename.take(MAX_LENGTH - suffix.length)
            if (stem.lastOrNull()?.isHighSurrogate() == true) stem = stem.dropLast(1)
            val candidate = stem.trimEnd() + suffix
            if (libraryComparisonKey(candidate) !in used) return candidate
        }
        error("Could not allocate a distinct scan label")
    }

    internal fun isValid(filename: String): Boolean {
        if (filename.length !in 1..MAX_LENGTH || filename.isBlank() || filename != filename.trim() ||
            filename == "." || filename == ".." || filename.any { it == '/' || it == '\\' || it.isISOControl() }) return false
        return try { requireWellFormedUnicode(filename); true } catch (_: IllegalArgumentException) { false }
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
