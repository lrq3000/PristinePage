package app.pristinepage.data.repository

import app.pristinepage.data.model.SourceCaptureDate
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/** A small explicit vocabulary over metadata already validated by import. This never parses EXIF or opens the original. */
sealed interface SourceMetadataQuery {
    data class Format internal constructor(val mime: String) : SourceMetadataQuery
    data class Orientation internal constructor(val value: Int) : SourceMetadataQuery
    companion object {
        private val CAPTURE_DAY = Regex("captured:([0-9]{4}-[0-9]{2}-[0-9]{2})")

        fun parse(raw: String): SourceMetadataQuery? {
            if (raw.length > AlbumSearchQuery.MAX_LENGTH) return null
            val query = raw.trim().lowercase(Locale.ROOT)
            return when (query) {
                "format:jpeg" -> Format("image/jpeg")
                "format:png" -> Format("image/png")
                "format:webp" -> Format("image/webp")
                else -> if (query.length == 13 && query.startsWith("orientation:") && query.last() in '1'..'8') {
                    Orientation(query.last() - '0')
                } else parseCaptureDay(query)
            }
        }

        private fun parseCaptureDay(query: String): SourceMetadataQuery? {
            val date = CAPTURE_DAY.matchEntire(query)?.groupValues?.get(1) ?: return null
            val day = try { LocalDate.parse(date) } catch (_: DateTimeParseException) { return null }
            return if (day.year in 1..9999) CaptureDayMetadataQuery(day.toEpochDay()) else null
        }
    }
}

internal data class CaptureDayMetadataQuery(val epochDay: Long) : SourceMetadataQuery {
    init { SourceCaptureDate(epochDay) }
}
