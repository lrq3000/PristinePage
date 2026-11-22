package app.pristinepage.data.repository

import java.text.Normalizer
import java.time.Instant
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Bounded transient query for the current title/date projection. No SQL expression or query history is stored. */
class AlbumSearchQuery(val text: String, private val zone: ZoneId) {
    private val groups = groupTerms(tokens(fold(text.take(MAX_LENGTH))))

    internal fun matches(title: String, creation: Long, modification: Long): Boolean {
        if (groups.isEmpty()) return true
        val titleKey = fold(title)
        val titleTerms by lazy { tokens(titleKey) }
        val unmatched = groups.filterNot { group ->
            if (group.size == 1) titleKey.contains(group.single()) else containsPhrase(titleTerms, group)
        }
        if (unmatched.isEmpty()) return true
        val timestamps = dateTerms(creation) + if (modification != creation) dateTerms(modification) else emptyList()
        return unmatched.all { group -> timestamps.any { containsPhrase(it, group) } }
    }

    private fun dateTerms(epoch: Long): List<List<String>> {
        val zoned = Instant.ofEpochMilli(epoch).atZone(zone)
        val displayed = tokens(DISPLAY.format(zoned).lowercase(Locale.ROOT))
        val clock = displayed.takeLast(2)
        val clock24 = listOf(CLOCK_24.format(zoned))
        val date = displayed.dropLast(2)
        val longDate = tokens(LONG_DATE.format(zoned).lowercase(Locale.ROOT))
        val isoDate = listOf(DateTimeFormatter.ISO_LOCAL_DATE.format(zoned))
        return listOf(date, date.dropLast(1), longDate, longDate.dropLast(1), isoDate).flatMap { prefix ->
            listOf(prefix + clock, prefix + clock24)
        }
    }

    private fun fold(value: String) =
        MARKS.replace(Normalizer.normalize(value, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    private fun containsPhrase(field: List<String>, phrase: List<String>): Boolean =
        (0..(field.size - phrase.size)).any { from -> phrase.indices.all { field[from + it] == phrase[it] } }

    /** An explicit date and adjacent time must match one timestamp, not a mixture of creation and modification. */
    private fun groupTerms(terms: List<String>): List<List<String>> = buildList {
        var index = 0
        while (index < terms.size) {
            var end = index + 1
            val day = terms.getOrNull(end)?.takeIf { it.matches(DAY) }?.toIntOrNull()
            val namedDate = terms[index] in MONTHS && day != null && day in 1..31
            if (namedDate) {
                end++
                if (terms.getOrNull(end)?.matches(YEAR) == true) end++
            }
            if (namedDate || terms[index].matches(ISO_DATE)) {
                if (terms.getOrNull(end)?.matches(CLOCK_TIME) == true) {
                    end++
                    if (terms.getOrNull(end) in MERIDIEMS) end++
                }
            }
            add(terms.subList(index, end))
            index = end
        }
    }

    companion object {
        const val MAX_LENGTH = 160
        private val SEPARATORS = Regex("[,\\s\\p{Z}]+")
        private val MARKS = Regex("\\p{M}+")
        private val DAY = Regex("[0-9]{1,2}")
        private val YEAR = Regex("[0-9]{4}")
        private val ISO_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val CLOCK_TIME = Regex("[0-9]{1,2}:[0-9]{2}")
        private val MERIDIEMS = setOf("am", "pm")
        private val MONTHS = Month.entries.flatMap { month ->
            listOf(TextStyle.SHORT, TextStyle.FULL).map { style ->
                month.getDisplayName(style, Locale.ENGLISH).lowercase(Locale.ROOT)
            }
        }.toSet()
        private val DISPLAY = DateTimeFormatter.ofPattern("MMM d, uuuu h:mm a", Locale.ENGLISH)
        private val LONG_DATE = DateTimeFormatter.ofPattern("MMMM d uuuu", Locale.ENGLISH)
        private val CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        /** The album UI and search use the same English date and local time. */
        fun displayDate(epoch: Long, zone: ZoneId): String = DISPLAY.format(Instant.ofEpochMilli(epoch).atZone(zone))

        private fun tokens(value: String) = SEPARATORS.split(value).filter(String::isNotEmpty)
    }
}
