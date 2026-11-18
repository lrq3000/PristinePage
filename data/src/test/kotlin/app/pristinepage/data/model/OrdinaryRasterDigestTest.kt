package app.pristinepage.data.model

import app.pristinepage.core.image.RgbImage
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class OrdinaryRasterDigestTest {
    @Test fun opaqueArgbRowsMatchIndependentUnsignedRgbVector() {
        assertEquals(golden, reference(3, 2, activeRgb.hexToByteArray()))
        assertEquals(golden, hashRows(colors))
    }

    @Test fun rowOrderAndBigEndianDimensionsArePartOfTheDigest() {
        val reversed = colors.reversedArray()
        assertEquals("fceeba399f35504137bfe744b14984a5781c54080948a396e4c3fad661e46427", hashRows(reversed))
        val flattened = colors.flatMap { it.asList() }.toIntArray()
        val reshaped = OrdinaryRasterDigest(2, 3).apply {
            repeat(3) { y -> appendRow(2, {}) { x -> flattened[y * 2 + x] } }
        }.finish()
        assertEquals(reference(2, 3, activeRgb.hexToByteArray()), reshaped)
        assertNotEquals(golden, reshaped)
    }

    @Test fun executionHashUsesOnlyActiveRowsEvenWhenPaddingDiffersOrIsAbsentAtEof() {
        val rgb = activeRgb.hexToByteArray()
        val padded = rgb.copyOfRange(0, 9) + byteArrayOf(0x11, 0x22) + rgb.copyOfRange(9, 18)
        RgbImage(3, 2, 11, padded).use { assertEquals(golden, OrdinaryRenderExecution.rasterSha256(it) {}) }
        RgbImage(3, 2, 9, rgb).use { assertEquals(golden, OrdinaryRenderExecution.rasterSha256(it) {}) }
    }

    @Test fun nonpositiveDimensionsAndWrongRowWidthsAreRejected() {
        for ((width, height) in listOf(0 to 2, 3 to 0, -1 to 2)) {
            assertThrows(IllegalArgumentException::class.java) { OrdinaryRasterDigest(width, height) }
        }
        for (width in listOf(2, 4)) {
            val digest = OrdinaryRasterDigest(3, 2)
            assertThrows(IllegalArgumentException::class.java) {
                digest.appendRow(width, {}) { throw AssertionError("Read a sample from a wrong-length row") }
            }
            assertThrows(IllegalStateException::class.java) { digest.finish() }
        }
    }

    @Test fun missingOrExtraRowsCannotReturnADigest() {
        val missing = OrdinaryRasterDigest(3, 2).apply { appendRow(3, {}) { colors[0][it] } }
        assertThrows(IllegalStateException::class.java) { missing.finish() }
        val extra = OrdinaryRasterDigest(3, 2).apply {
            colors.forEach { row -> appendRow(row.size, {}) { row[it] } }
        }
        assertThrows(IllegalStateException::class.java) { extra.appendRow(3, {}) { colors[0][it] } }
        assertThrows(IllegalStateException::class.java) { extra.finish() }
    }

    @Test fun truncatedOrNonopaqueSamplesCannotPublishEvenAfterACompleteRow() {
        val truncated = OrdinaryRasterDigest(3, 2).apply { appendRow(3, {}) { colors[0][it] } }
        val shortRow = colors[1].copyOf(2)
        assertThrows(IndexOutOfBoundsException::class.java) { truncated.appendRow(3, {}) { shortRow[it] } }
        assertThrows(IllegalStateException::class.java) { truncated.appendRow(3, {}) { colors[1][it] } }
        assertThrows(IllegalStateException::class.java) { truncated.finish() }

        val translucent = OrdinaryRasterDigest(3, 1)
        assertThrows(IllegalArgumentException::class.java) {
            translucent.appendRow(3, {}) { colors[0][it] and 0x7fffffff }
        }
        assertThrows(IllegalStateException::class.java) { translucent.finish() }
    }

    @Test fun caughtNestedAppendInvalidatesTheOuterRow() {
        val digest = OrdinaryRasterDigest(1, 1)
        var attempted = false
        assertThrows(IllegalStateException::class.java) {
            digest.appendRow(1, {}) {
                attempted = true
                assertThrows(IllegalStateException::class.java) {
                    digest.appendRow(1, {}) { throw AssertionError("Nested append requested a sample") }
                }
                colors[0][0]
            }
        }
        assertEquals(true, attempted)
        assertThrows(IllegalStateException::class.java) { digest.finish() }
    }

    @Test fun caughtNestedFinishFromCheckpointInvalidatesTheOuterRowBeforeSampling() {
        val digest = OrdinaryRasterDigest(1, 1)
        var attempted = false
        var sampled = false
        assertThrows(IllegalStateException::class.java) {
            digest.appendRow(1, {
                attempted = true
                assertThrows(IllegalStateException::class.java) { digest.finish() }
            }) {
                sampled = true
                colors[0][0]
            }
        }
        assertEquals(true, attempted)
        assertEquals(false, sampled)
        assertThrows(IllegalStateException::class.java) { digest.finish() }
    }

    @Test fun largeRowHashesAllBlocksAndAllowsCancellationBeforeReadingTheNextBlock() {
        val width = 4097
        val rgb = ByteArray(width * 3) { (it * 37 + 191).toByte() }
        val digest = OrdinaryRasterDigest(width, 1)
        val pixel = { x: Int ->
            0xff000000.toInt() or ((rgb[3 * x].toInt() and 255) shl 16) or
                ((rgb[3 * x + 1].toInt() and 255) shl 8) or (rgb[3 * x + 2].toInt() and 255)
        }
        digest.appendRow(width, {}) { pixel(it) }
        assertEquals(reference(width, 1, rgb), digest.finish())
        assertThrows(IllegalStateException::class.java) { digest.finish() }

        val canceled = OrdinaryRasterDigest(width, 1)
        val signal = CancellationException("cancel between digest blocks")
        var checkpoints = 0
        assertSame(signal, assertThrows(CancellationException::class.java) {
            canceled.appendRow(width, { if (++checkpoints == 2) throw signal }) { x ->
                if (x >= 4096) throw AssertionError("Read after cancellation checkpoint")
                pixel(x)
            }
        })
        assertThrows(IllegalStateException::class.java) { canceled.finish() }
    }

    private fun hashRows(rows: Array<IntArray>) = OrdinaryRasterDigest(3, 2).apply {
        rows.forEach { row -> appendRow(row.size, {}) { row[it] } }
    }.finish()

    private fun reference(width: Int, height: Int, packed: ByteArray): String = MessageDigest.getInstance("SHA-256").apply {
        update("pristinepage-ordinary-render-rgb8-v1".toByteArray(Charsets.US_ASCII))
        update(0.toByte())
        update(ByteBuffer.allocate(8).putInt(width).putInt(height).array())
        update(packed)
    }.digest().toHexString()

    private companion object {
        const val activeRgb = "0123fe80ff00c90781fa40130e9b634d0288"
        const val golden = "9434457103f2fbf1dd09735edfea2d591e550254ebca19429aa6b1af9224ff83"
        val colors = arrayOf(
            intArrayOf(0xff0123fe.toInt(), 0xff80ff00.toInt(), 0xffc90781.toInt()),
            intArrayOf(0xfffa4013.toInt(), 0xff0e9b63.toInt(), 0xff4d0288.toInt()),
        )
    }
}
