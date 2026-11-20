package app.pristinepage

import app.pristinepage.data.model.AlbumOrder
import app.pristinepage.design.AtelierSettings
import app.pristinepage.design.HomeThumbnailLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class CarnetThumbnailsTest {
    @Test fun anOldOrMalformedPreferenceUsesStackWithoutResettingOtherSettings() {
        for (stored in listOf(emptyMap(), mapOf("homeThumbnailLayout" to 7), mapOf("homeThumbnailLayout" to "unknown"))) {
            val settings = AtelierSettings.fromStored(stored + mapOf("order" to "TITLE_ZA", "created" to true, "grid" to false))
            assertEquals(AtelierSettings(AlbumOrder.TITLE_ZA, true, false, HomeThumbnailLayout.STACK), settings)
        }
    }

    @Test fun rowSurvivesDecodingAndIncludesTheLastPageInItsSavedOrder() {
        val row = AtelierSettings.fromStored(mapOf("homeThumbnailLayout" to "ROW")).homeThumbnailLayout
        val pages = listOf("e", "c", "a", "b", "d")
        assertEquals(HomeThumbnailLayout.ROW, row)
        assertEquals(listOf("e", "c", "a", "b", "d"), row.pages(pages))
    }

    @Test fun stackHasNoMoreThanTheFirstThreePagesAndNeverInventsPages() {
        val stack = HomeThumbnailLayout.STACK
        assertEquals(listOf(8, 3, 9), stack.pages(listOf(8, 3, 9, 2, 1)))
        assertEquals(listOf(8, 3, 9), stack.pages(listOf(8, 3, 9)))
        assertEquals(listOf(8), stack.pages(listOf(8)))
        assertEquals(emptyList<Int>(), stack.pages(emptyList<Int>()))
    }
}
