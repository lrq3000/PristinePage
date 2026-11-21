package app.pristinepage.image.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageEvidenceTest {
    @Test fun smallChannelRoundingDoesNotRejectACompletePage() {
        val expected = IntArray(100) { if (it % 2 == 0) 0xff293497.toInt() else 0xffeadeb1.toInt() }
        val actual = expected.map { it xor 0x010101 }.toIntArray()
        assertTrue(PdfPageEvidence.matches(expected, actual))
    }

    @Test fun equalGeometryCannotHideALargeSubstitution() {
        val expected = IntArray(100) { if (it < 50) 0xffff0000.toInt() else 0xff0000ff.toInt() }
        assertFalse(PdfPageEvidence.matches(expected, expected.reversedArray()))
        assertFalse(PdfPageEvidence.matches(expected, IntArray(100) { -1 }))
    }

    @Test fun localizedMissingInkIsNotSilencedByMeanErrorOverThePage() {
        val expected = IntArray(1000) { if (it < 40) 0xff000000.toInt() else -1 }
        assertFalse(PdfPageEvidence.matches(expected, IntArray(1000) { -1 }))
    }

    @Test fun missingSamplesOrTransparencyCannotCountAsEvidence() {
        assertFalse(PdfPageEvidence.matches(IntArray(0), IntArray(0)))
        assertFalse(PdfPageEvidence.matches(intArrayOf(-1), intArrayOf(-1, -1)))
        assertFalse(PdfPageEvidence.matches(intArrayOf(-1), intArrayOf(0x00ffffff)))
    }
}
