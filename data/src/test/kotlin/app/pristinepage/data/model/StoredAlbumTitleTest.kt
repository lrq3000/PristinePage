package app.pristinepage.data.model

import org.junit.Assert.assertThrows
import org.junit.Test

class StoredAlbumTitleTest {
    @Test fun storedTitleRejectsOuterUnicodeWhitespaceRatherThanRenamingAnAlbum() {
        for (title in listOf(" Trips", "Trips\t", "\u00a0Trips", "Trips\u2003", "\u2003")) {
            assertThrows("An album reopened by Room must have a canonical title", IllegalArgumentException::class.java) {
                requireStoredAlbumTitle(title)
            }
        }
    }

    @Test fun exactAcceptedUnicodeSpellingAndInteriorSpacesStayValid() {
        requireStoredAlbumTitle("Cafe\u0301  \u03a3\u03b1\u03c2")
        requireStoredAlbumTitle("\ud83d\udcc4".repeat(80))
        for (title in listOf("\ud83d\udcc4".repeat(81), "Broken\ud800", "")) {
            assertThrows(IllegalArgumentException::class.java) { requireStoredAlbumTitle(title) }
        }
    }
}
