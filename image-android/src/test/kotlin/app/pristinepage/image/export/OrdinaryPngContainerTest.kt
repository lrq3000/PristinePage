package app.pristinepage.image.export

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrdinaryPngContainerTest {
    @Test fun `exact capped stream returns literal encoded identity and leaves the borrow open`() {
        val bytes = png()
        val input = TrackingInput(bytes)
        val budget = budget()
        val receipt = OrdinaryPngContainer(budget).read(input, 88, 88, size) {}
        assertEquals(88L, receipt.byteLength)
        assertEquals("0a5c5dd3efaa4a825f73d2f402313a3a8d4e1006038687a333f884ebbba80ba1", receipt.sha256)
        assertEquals(88, input.position)
        assertFalse(input.closed)
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun `byte ceiling and shared headroom refuse before reading any input`() {
        val input = TrackingInput(png())
        assertRefusal(OperationErrorCode.RESOURCE_LIMIT, "export_png_readback_byte_limit") {
            OrdinaryPngContainer(budget()).read(input, 88, 87, size) {}
        }
        val budget = budget()
        budget.reserve(budget.capacityBytes - 1).use {
            assertRefusal(OperationErrorCode.RESOURCE_LIMIT, "image_allocation_limit") {
                OrdinaryPngContainer(budget).read(input, 88, 88, size) {}
            }
            assertEquals(budget.capacityBytes - 1, budget.usedBytes)
        }
        assertEquals(0, input.position)
    }

    @Test fun `CRC covers IDAT payload and framing cannot narrow unsigned extent`() {
        val badCrc = png().apply { this[43] = (this[43].toInt() xor 1).toByte() }
        refuses(badCrc, "export_png_crc")
        val overlong = png().apply { be(-1).copyInto(this, 33) }
        val input = TrackingInput(overlong)
        assertRefusal(OperationErrorCode.INTEGRITY_FAILED, "export_png_structure") {
            OrdinaryPngContainer(budget()).read(input, 88, 88, size) {}
        }
        assertEquals("stop at the IDAT header", 41, input.position)
    }

    @Test fun `format shape and unsupported PNG methods remain distinct`() {
        refuses(png().apply { this[0] = 0 }, "export_png_format", OperationErrorCode.UNSUPPORTED_FORMAT)
        for (width in listOf(0, -1, 2)) refuses(png(header = ihdr(w = width)), "export_png_dimensions")
        for ((at, value) in listOf(8 to 16, 9 to 3, 10 to 1, 11 to 1, 12 to 1, 12 to 2)) {
            val header = ihdr().apply { this[at] = value.toByte() }
            refuses(png(header = header), "export_png_methods", OperationErrorCode.UNSUPPORTED_FORMAT)
        }
    }

    @Test fun `leading header aggregate data terminal CRC and physical EOF are required`() {
        val iend = chunk("IEND")
        for (bytes in listOf(
            signature + data + iend, png(chunk("IHDR", ihdr()), data),
            png(iend), png(chunk("IDAT"), chunk("IDAT")),
            png(data, chunk("IEND", byteArrayOf(1))),
            png().dropLast(1).toByteArray(), png().dropLast(iend.size).toByteArray(),
            png() + byteArrayOf(0), png() + iend,
        )) refuses(bytes, "export_png_structure")
        refuses(png().apply { this[lastIndex] = 0 }, "export_png_crc")
        val excess = TrackingInput(png() + byteArrayOf(0))
        assertRefusal(OperationErrorCode.INTEGRITY_FAILED, "export_png_structure") {
            OrdinaryPngContainer(budget()).read(excess, 88, 88, size) {}
        }
    }

    @Test fun `split IDAT may include empty chunks but chunk count is independently capped`() {
        val pieces = listOf(chunk("IDAT"), chunk("IDAT", idat.copyOfRange(0, 6)), chunk("IDAT", idat.copyOfRange(6, idat.size)))
        val split = png(*pieces.toTypedArray())
        assertEquals(split.size.toLong(), read(split).byteLength)
        val many = png(*Array(4095) { chunk("IDAT") }, data)
        refuses(many, "export_png_chunk_limit", OperationErrorCode.RESOURCE_LIMIT)
    }

    @Test fun `benign fields have strict lengths values placement and uniqueness`() {
        val fields = listOf(
            "gAMA" to be(45455),
            "cHRM" to listOf(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000).flatMap { be(it).asList() }.toByteArray(),
            "sRGB" to byteArrayOf(3), "PLTE" to byteArrayOf(0, 0, 0),
            "pHYs" to (be(3780) + be(3780) + byteArrayOf(1)), "eXIf" to identityLe,
        )
        read(png(*fields.map { chunk(it.first, it.second) }.toTypedArray(), data))
        for ((name, payload) in fields) {
            val field = chunk(name, payload)
            refuses(png(field, field, data), "export_png_structure")
            refuses(png(data, field), "export_png_structure")
            refuses(png(chunk(name, payload.copyOf(payload.size - 1)), data), "export_png_metadata", OperationErrorCode.UNSUPPORTED_FORMAT)
            if (name in listOf("gAMA", "cHRM", "sRGB")) {
                refuses(png(chunk("PLTE", byteArrayOf(0, 0, 0)), field, data), "export_png_structure")
            }
        }
        for ((name, field) in listOf(
            "gAMA" to be(45454), "cHRM" to ByteArray(32), "sRGB" to byteArrayOf(4),
            "pHYs" to (be(3780) + be(3780) + byteArrayOf(2)),
        )) refuses(png(chunk(name, field), data), "export_png_metadata", OperationErrorCode.UNSUPPORTED_FORMAT)
    }

    @Test fun `privacy refuses carriers and any EXIF besides the exact identity tag`() {
        read(png(chunk("eXIf", identityLe), data))
        read(png(chunk("eXIf", hex("4d4d002a00000008000101120003000000010001000000000000")), data))
        for (type in listOf("tEXt", "iTXt", "zTXt", "iCCP", "acTL", "fcTL", "fdAT", "cICP", "mDCv", "cLLi", "tRNS", "vpAg")) {
            refuses(png(chunk(type, byteArrayOf(1)), data), "export_png_metadata", OperationErrorCode.UNSUPPORTED_FORMAT)
        }
        for (tiff in listOf(identityLe + byteArrayOf(0), identityLe.copyOf().apply { this[18] = 2 }, identityLe.copyOf().apply { this[22] = 1 })) {
            refuses(png(chunk("eXIf", tiff), data), "export_png_metadata", OperationErrorCode.UNSUPPORTED_FORMAT)
        }
    }

    @Test fun `framing does not pretend to prove pixel data or RGBA opacity`() {
        val envelopeOnly = png(chunk("IDAT", byteArrayOf(1, 2, 3, 4)), header = ihdr(type = 6))
        assertEquals(envelopeOnly.size.toLong(), read(envelopeOnly).byteLength)
    }

    @Test fun `short reads and midstream cancellation or IO release only the gate reservation`() {
        val bytes = png()
        val partial = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 2))
        }
        assertEquals(read(bytes), OrdinaryPngContainer(budget()).read(partial, 88, 88, size) {})
        val budget = budget()
        budget.reserve(100).use {
            val canceled = CancellationException("fixture cancellation")
            val input = TrackingInput(bytes)
            assertSame(canceled, assertThrows(CancellationException::class.java) {
                OrdinaryPngContainer(budget).read(input, 88, 88, size) {
                    if (input.position > 0) {
                        assertTrue(budget.usedBytes > 100)
                        throw canceled
                    }
                }
            })
            assertFalse(input.closed)
            assertEquals(100L, budget.usedBytes)
            val io = object : ByteArrayInputStream(bytes.copyOf(41)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    if (available() == 0) throw IOException("fixture read failure") else super.read(b, off, len)
            }
            assertRefusal(OperationErrorCode.STORAGE_FAILED, "export_png_read_failed") {
                OrdinaryPngContainer(budget).read(io, 88, 88, size) {}
            }
            assertEquals(100L, budget.usedBytes)
        }
        assertEquals(0L, budget.usedBytes)
    }

    private fun read(bytes: ByteArray) = OrdinaryPngContainer(budget()).read(
        ByteArrayInputStream(bytes), bytes.size.toLong(), bytes.size.toLong(), size,
    ) {}

    private fun refuses(bytes: ByteArray, key: String, code: OperationErrorCode = OperationErrorCode.INTEGRITY_FAILED) {
        assertRefusal(code, key) { read(bytes) }
    }

    private fun assertRefusal(code: OperationErrorCode, key: String, call: () -> Unit) {
        val error = assertThrows(RepositoryFailure::class.java) { call() }.error
        assertEquals(code, error.code)
        assertEquals(key, error.safeMessageKey)
    }

    private class TrackingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val position get() = pos
        var closed = false
        override fun close() { closed = true }
    }

    private fun budget() = ImageAllocationBudget(4L * 1024 * 1024)
    private fun ihdr(w: Int = 3, type: Int = 2) = be(w) + be(2) + byteArrayOf(8, type.toByte(), 0, 0, 0)
    private fun png(vararg fields: ByteArray, header: ByteArray = ihdr()) =
        (if (fields.isEmpty()) listOf(data) else fields.asList()).fold(signature + chunk("IHDR", header), ByteArray::plus) + chunk("IEND")

    private fun chunk(name: String, payload: ByteArray = byteArrayOf()): ByteArray {
        val body = name.encodeToByteArray() + payload
        return be(payload.size) + body + be(CRC32().apply { update(body) }.value.toInt())
    }

    private fun be(n: Int) = ByteArray(4) { (n ushr (24 - 8 * it)).toByte() }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val size = PixelSize(3, 2)
    private val signature = hex("89504e470d0a1a0a")
    // Independent zlib stored block: filter 0 and nine RGB bytes per row; generated and hashed using Python's standard library.
    private val idat = hex("7801011400ebff000123fe80ff00c9078100fa40130e9b634d02884c290723")
    private val data = chunk("IDAT", idat)
    private val identityLe = hex("49492a0008000000010012010300010000000100000000000000")
}
