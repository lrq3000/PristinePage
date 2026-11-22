package app.pristinepage.image

import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.TiffOrientation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TiffCaptureDateTest {
    @Test fun capturesOnlyTheCalendarDayInBothTiffByteOrders() {
        val day = LocalDate.of(2024, 2, 29)
        assertEquals(day, TiffOrientation.readCaptureDate(tiff()) {})
        val app1 = "Exif\u0000\u0000".encodeToByteArray() + tiff(order = ByteOrder.BIG_ENDIAN)
        assertEquals(day, TiffOrientation.readCaptureDate(app1) {})
    }

    @Test fun dateFromGpsOrThumbnailChainIsNotThePrimaryCaptureDate() {
        assertNull(TiffOrientation.readCaptureDate(tiff(primaryTag = 0x8825)) {})
        assertNull(TiffOrientation.readCaptureDate(tiff(primaryTag = null)) {})
    }

    @Test fun malformedOptionalDateDoesNotTurnIntoAValidDayOrChangeOrientation() {
        for (text in listOf("2023:02:29 09:20:01\u0000", "2024:02:29 25:20:01\u0000",
            "2024:02:29 09:20:01x", "2024:02:29\u000009:20:01\u0000", "0000:02:29 09:20:01\u0000",
            "2024-02-29 09:20:01\u0000", "20240229\u0000")) {
            val bytes = tiff(text = text)
            assertNull(text, TiffOrientation.readCaptureDate(bytes) {})
            assertEquals(6, TiffOrientation.read(bytes) {})
        }
        assertNull(TiffOrientation.readCaptureDate(tiff(fieldType = 7)) {})
    }

    @Test fun truncatedPayloadRemainsAnEnvelopeFailureAndCancellationPropagates() {
        val cut = tiff().let { it.copyOf(it.size - 1) }
        val failure = assertThrows(RepositoryFailure::class.java) { TiffOrientation.readCaptureDate(cut) {} }
        assertEquals(OperationErrorCode.INVALID_INPUT, failure.error.code)
        val canceled = CancellationException("synthetic")
        assertSame(canceled, assertThrows(CancellationException::class.java) { TiffOrientation.readCaptureDate(tiff()) { throw canceled } })
    }

    private fun tiff(
        order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        text: String = "2024:02:29 09:20:01\u0000",
        primaryTag: Int? = 0x8769,
        fieldType: Int = 2,
    ): ByteArray {
        val payload = text.encodeToByteArray()
        val child = if (primaryTag == null) 26 else 38
        val b = ByteBuffer.allocate(child + 18 + payload.size).order(order)
        val endian = if (order == ByteOrder.LITTLE_ENDIAN) 0x49.toByte() else 0x4d.toByte()
        b.put(endian).put(endian).putShort(42).putInt(8)
        b.putShort(if (primaryTag == null) 1 else 2)
        b.putShort(0x112).putShort(3).putInt(1).putShort(6).putShort(0)
        if (primaryTag != null) b.putShort(primaryTag.toShort()).putShort(4).putInt(1).putInt(child)
        b.putInt(if (primaryTag == null) child else 0)
        b.putShort(1).putShort(0x9003.toShort()).putShort(fieldType.toShort()).putInt(payload.size).putInt(child + 18).putInt(0)
        return b.put(payload).array()
    }
}
