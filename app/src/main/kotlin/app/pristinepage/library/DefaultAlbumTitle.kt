package app.pristinepage.library

import app.pristinepage.core.pipeline.requireWellFormedUnicode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Parsed preferences cannot generate a title outside the repository's text and length bounds. */
internal class AlbumTitleTemplate private constructor(val pattern: String) {
    fun render(now: Instant, zone: ZoneId): String {
        val local = now.atZone(zone)
        return expand(pattern, date.format(local), time.format(local))
    }

    companion object {
        val DEFAULT = AlbumTitleTemplate("pristinepage_%date_%time")
        private val token = Regex("%%|%[A-Za-z]+|%")
        private val date = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
        private val time = DateTimeFormatter.ofPattern("HH-mm-ss", Locale.ROOT)

        fun parse(input: String): AlbumTitleTemplate? {
            if (input.length > 160 || input.any(Char::isISOControl)) return null
            val pattern = input.trim()
            if (pattern.isEmpty() || token.findAll(pattern).any { it.value !in setOf("%%", "%date", "%time") }) return null
            try { requireWellFormedUnicode(pattern) } catch (_: IllegalArgumentException) { return null }
            if (expand(pattern, "2000-01-01", "00-00-00").length > 160) return null
            return AlbumTitleTemplate(pattern)
        }

        private fun expand(pattern: String, date: String, time: String): String = token.replace(pattern) {
            when (it.value) { "%%" -> "%"; "%date" -> date; "%time" -> time; else -> error("Unvalidated title template") }
        }
    }
}

internal fun defaultAlbumTitle(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
    template: AlbumTitleTemplate = AlbumTitleTemplate.DEFAULT): String = template.render(now, zone)
