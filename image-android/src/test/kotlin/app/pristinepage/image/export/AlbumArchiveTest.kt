package app.pristinepage.image.export

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlbumArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun archivedBytesPreserveReviewedOrderAndOriginalNumbers() {
        val file = folder.newFile()
        val items = listOf("Factures_2.jpg" to byteArrayOf(1, 3), "Factures_5.jpg" to byteArrayOf(3, 5, 7))
        val evidence = file.outputStream().use { sink ->
            AlbumArchive.write(sink, items.map { it.first }, {}) { ordinal, output -> output.write(items[ordinal].second) }
        }
        AlbumArchive.verify(file, evidence) {}
        ZipInputStream(ByteArrayInputStream(file.readBytes())).use { zip ->
            for ((name, bytes) in items) {
                assertEquals(name, zip.nextEntry.name)
                assertArrayEquals(bytes, zip.readBytes())
            }
            assertNull(zip.nextEntry)
        }
    }

    @Test fun readbackRejectsAnotherValidZipWithSameNamesOrDifferentOrder() {
        val file = folder.newFile()
        val names = listOf("Factures_2.jpg", "Factures_5.jpg")
        val proof = file.outputStream().use { AlbumArchive.write(it, names, {}) { i, out -> out.write(i) } }
        file.outputStream().use { AlbumArchive.write(it, names, {}) { i, out -> out.write(1 - i) } }
        assertThrows(IllegalArgumentException::class.java) { AlbumArchive.verify(file, proof) {} }
        file.outputStream().use { AlbumArchive.write(it, names.reversed(), {}) { i, out -> out.write(1 - i) } }
        assertThrows(IllegalArgumentException::class.java) { AlbumArchive.verify(file, proof) {} }
    }

    @Test fun unsafeOrUnexpectedEntriesAreNotExportable() {
        val file = folder.newFile()
        for (names in listOf(listOf("../Factures.jpg", "Factures_5.jpg"), listOf("x.jpg", "x.jpg"), listOf("x.png", "y.jpg"))) {
            assertThrows(IllegalArgumentException::class.java) {
                file.outputStream().use { AlbumArchive.write(it, names, {}) { _, out -> out.write(1) } }
            }
        }
    }
}
