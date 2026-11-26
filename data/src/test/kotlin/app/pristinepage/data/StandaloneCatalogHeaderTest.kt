package app.pristinepage.data

import app.pristinepage.data.library.StandaloneCatalogHeader
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Test

class StandaloneCatalogHeaderTest {
    @Test fun aClosedCatalogUsesTheExactSizeAndCommittedPageCount() {
        StandaloneCatalogHeader.verify(header(encodedPageSize = 4096, pages = 25), 102_400)
        StandaloneCatalogHeader.verify(header(encodedPageSize = 1, pages = 2), 131_072)
    }

    @Test fun truncatedAndExtraPagesDoNotMatchTheCatalogHeader() {
        for (extent in listOf(102_399L, 102_401L, 106_496L)) {
            assertThrows(IOException::class.java) { StandaloneCatalogHeader.verify(header(4096, 25), extent) }
        }
    }

    @Test fun staleHeaderCountNonPowerOfTwoPagesAndWalModeAreRefused() {
        for (invalid in listOf(
            header(4096, 25).also { ByteBuffer.wrap(it).putInt(92, 42) },
            header(1536, 25),
            header(4096, 0),
            header(4096, 25).also { it[18] = 2; it[19] = 2 },
        )) {
            assertThrows(IOException::class.java) { StandaloneCatalogHeader.verify(invalid, 102_400) }
        }
    }

    private fun header(encodedPageSize: Int, pages: Int) = ByteArray(100).apply {
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(this)
        this[18] = 1
        this[19] = 1
        ByteBuffer.wrap(this).putShort(16, encodedPageSize.toShort()).putInt(24, 1).putInt(28, pages).putInt(92, 1)
    }
}
