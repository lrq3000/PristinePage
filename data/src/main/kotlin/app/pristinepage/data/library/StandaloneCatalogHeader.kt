package app.pristinepage.data.library

import java.io.IOException
import java.nio.ByteBuffer

/** Reject trailing/truncated pages and any catalog that needs a journal to interpret its committed state. SQLite performs the separate structural checks. */
internal object StandaloneCatalogHeader {
    const val SIZE = 100
    private val signature = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun verify(header: ByteArray, actualBytes: Long) {
        if (header.size != SIZE || !header.copyOfRange(0, signature.size).contentEquals(signature) || header[18] != 1.toByte() || header[19] != 1.toByte()) {
            throw IOException("Library catalog is not a standalone SQLite file")
        }
        val fields = ByteBuffer.wrap(header)
        // SQLite uses unsigned big-endian page sizes/counts, with a short value of 1 representing 64 KiB.
        val encodedPageSize = fields.getShort(16).toInt() and 0xffff
        val pageBytes = if (encodedPageSize == 1) 65536 else encodedPageSize
        val pages = fields.getInt(28).toLong() and 0xffffffffL
        if (pageBytes !in 512..65536 || Integer.bitCount(pageBytes) != 1 || pages !in 1..0xfffffffeL ||
            fields.getInt(24) != fields.getInt(92) || pageBytes.toLong() * pages != actualBytes) {
            throw IOException("Library catalog extent does not match the committed SQLite header")
        }
    }
}
