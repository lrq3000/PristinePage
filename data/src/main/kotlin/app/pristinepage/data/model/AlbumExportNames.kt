package app.pristinepage.data.model

import java.util.Locale

/** Names are derived from frozen review metadata, never from the current album or a private path. */
class AlbumExportNames(scope: AlbumExportScope) {
    private val stem = basename(scope.title)
    val jpegs: List<String> = scope.positions.map { "${stem}_$it.jpg" }
    val pdf = "$stem.pdf"
    val zip = "$stem.zip"

    fun file(format: ExportFormat): String = when (format) {
        ExportFormat.JPEG -> jpegs.single()
        ExportFormat.IMAGE_ZIP -> zip
        ExportFormat.PDF -> pdf
        ExportFormat.PNG -> error("Unnamed export format")
    }

    private companion object {
        val reservedDevice = Regex("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")

        fun basename(title: String): String {
            val clean = title.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
                .joinToString("").trim { it == '.' || it.isWhitespace() }.ifBlank { "pristinepage" }
            // Reserve nine bytes for "_100.jpg" and a possible device-name prefix; never cut a Unicode code point.
            var end = 0
            var bytes = 0
            while (end < clean.length) {
                val point = clean.codePointAt(end)
                val length = when { point < 0x80 -> 1; point < 0x800 -> 2; point < 0x10000 -> 3; else -> 4 }
                if (bytes + length > 246) break
                bytes += length
                end += Character.charCount(point)
            }
            val truncated = clean.substring(0, end).trimEnd { it == '.' || it.isWhitespace() }
            val stem = truncated.substringBefore('.').trimEnd().uppercase(Locale.ROOT)
            return if (reservedDevice.matches(stem)) "_$truncated" else truncated
        }
    }
}
