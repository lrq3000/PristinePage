package app.pristinepage.data

import app.pristinepage.data.repository.decodeStoredOcrText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OcrVerbatimStorageTest {
    @Test fun preservesWellFormedBytesAndBlankWithoutAnotherProjection() {
        val original = "  E\u0301cole 東京都\n"
        val text = checkNotNull(decodeStoredOcrText(original.encodeToByteArray()))
        assertEquals(original, text.value)
        assertFalse(text.toString().contains("cole"))
        assertEquals("", decodeStoredOcrText(byteArrayOf())?.value)
    }

    @Test fun rejectsInvalidUtf8NulAndOversizedTextInsteadOfReplacingOrTruncating() {
        assertNull(decodeStoredOcrText(byteArrayOf(97, 0xff.toByte(), 98)))
        assertNull(decodeStoredOcrText(byteArrayOf(97, 0, 98)))
        assertNull(decodeStoredOcrText("a".repeat(131_073).encodeToByteArray()))
    }
}
