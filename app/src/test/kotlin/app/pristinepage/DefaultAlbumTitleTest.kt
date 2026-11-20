package app.pristinepage

import app.pristinepage.library.defaultAlbumTitle
import app.pristinepage.library.AlbumTitleTemplate
import app.pristinepage.design.AtelierSettings
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultAlbumTitleTest {
    @Test fun timestampUsesLocalDateAndTimeEvenAcrossMidnight() {
        assertEquals("pristinepage_2026-11-21_00-14-08",
            defaultAlbumTitle(Instant.parse("2026-11-20T23:14:08Z"), ZoneId.of("Europe/Paris")))
    }

    @Test fun storedTemplateExpandsRepeatedTokensAndEscapedPercentWithoutChangingLiteralText() {
        val settings = AtelierSettings.fromStored(mapOf("albumTitleTemplate" to "Factures_%time_%date_%%_%date"))
        assertEquals("Factures_08-09-10_2026-11-20_%_2026-11-20",
            settings.albumTitleTemplate.render(Instant.parse("2026-11-20T08:09:10Z"), ZoneId.of("UTC")))
    }

    @Test fun malformedTemplatesCannotReplaceTheDefault() {
        for (input in listOf(" ", "%mystery", "%" , "a".repeat(160) + "%date", "one\ntwo")) {
            assertNull(AlbumTitleTemplate.parse(input))
            assertEquals("pristinepage_%date_%time",
                AtelierSettings.fromStored(mapOf("albumTitleTemplate" to input)).albumTitleTemplate.pattern)
        }
    }
}
