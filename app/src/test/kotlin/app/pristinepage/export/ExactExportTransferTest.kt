package app.pristinepage.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExactExportTransferTest {
    @Test fun preservesLiteralUtf8AndCountsOnlySuccessfulWrites() {
        val text = "  École\r\n領収書 · إيصال  \n".encodeToByteArray()
        val sink = ByteArrayOutputStream()
        var written = 0
        transferExactExport(ByteArrayInputStream(text), sink, 36, TEXT_SHA, {}, { written += it })
        assertArrayEquals(text, sink.toByteArray())
        assertEquals(36, written)
        transferExactExport(ByteArrayInputStream(sink.toByteArray()), null, 36, TEXT_SHA, {}, { written += it })
        assertEquals(36, written)
    }

    @Test fun acceptsAnExactEmptyResultWithoutWritingAndRefusesExtraBytes() {
        val sink = ByteArrayOutputStream()
        transferExactExport(ByteArrayInputStream(byteArrayOf()), sink, 0, EMPTY_SHA, {}, { error("Unexpected write") })
        assertEquals(0, sink.size())
        assertThrows(IOException::class.java) {
            transferExactExport(ByteArrayInputStream(byteArrayOf(0)), null, 0, EMPTY_SHA, {})
        }
    }

    @Test fun refusesTruncatedExtendedAndSameLengthDifferentBytes() {
        for (invalid in listOf("ab", "abcd", "abd")) {
            assertThrows(invalid, IOException::class.java) {
                transferExactExport(ByteArrayInputStream(invalid.encodeToByteArray()), null, 3, ABC_SHA, {})
            }
        }
    }

    @Test fun refusesToWriteBytesReadWhileTheCallerWasCanceled() {
        var canceled = false
        val input = object : ByteArrayInputStream("abc".encodeToByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { canceled = true }
        }
        val sink = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            transferExactExport(input, sink, 3, ABC_SHA, { if (canceled) throw CancellationException() })
        }
        assertEquals(0, sink.size())
    }

    @Test fun refusesAnImpossibleReadCountBeforeWritingOrAcknowledgingBytes() {
        val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = len + 1
        }
        val sink = ByteArrayOutputStream()
        var written = 0
        assertThrows(IOException::class.java) {
            transferExactExport(input, sink, 3, ABC_SHA, {}, { written += it })
        }
        assertEquals(0, written)
        assertEquals(0, sink.size())
    }

    @Test fun doesNotReportAChunkWhenTheOutputThrows() {
        val sink = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, 1)
                throw IOException("Synthetic partial write")
            }
        }
        var written = 0
        assertThrows(IOException::class.java) {
            transferExactExport(ByteArrayInputStream("abc".encodeToByteArray()), sink, 3, ABC_SHA, {}, { written += it })
        }
        assertEquals(0, written)
        assertEquals(1, sink.size())
    }

    private companion object {
        const val ABC_SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        const val EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val TEXT_SHA = "90b8051d4594a1f0bb3715981105bf56cf5854bd0602e4e483d0520e3f8025f8"
    }
}
