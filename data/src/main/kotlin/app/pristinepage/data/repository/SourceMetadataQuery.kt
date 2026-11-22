package app.pristinepage.data.repository

import java.util.Locale

/** A small explicit vocabulary over metadata already validated by import. This never parses EXIF or opens the original. */
sealed interface SourceMetadataQuery {
    data class Format internal constructor(val mime: String) : SourceMetadataQuery
    data class Orientation internal constructor(val value: Int) : SourceMetadataQuery

    companion object {
        fun parse(raw: String): SourceMetadataQuery? {
            if (raw.length > AlbumSearchQuery.MAX_LENGTH) return null
            val query = raw.trim().lowercase(Locale.ROOT)
            return when (query) {
                "format:jpeg" -> Format("image/jpeg")
                "format:png" -> Format("image/png")
                "format:webp" -> Format("image/webp")
                else -> if (query.length == 13 && query.startsWith("orientation:") && query.last() in '1'..'8') {
                    Orientation(query.last() - '0')
                } else null
            }
        }
    }
}
