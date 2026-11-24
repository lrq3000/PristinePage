package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryBackupRead
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalLibraryDirectory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryBackupReadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsAClosedCatalogAndEveryDistinctReferencedObjectWithoutChangingTheBackup() {
        val fixture = fixture()
        val verified = fixture.reader.read(8) { _, catalog ->
            assertFalse(catalog.canonicalPath.startsWith(fixture.backup.path + File.separator))
            assertArrayEquals(fixture.catalog, catalog.readBytes())
            LibraryBackupRead.Projection("validated records", listOf(fixture.original, fixture.original))
        }
        assertEquals(fixture.generation, verified.generation)
        assertEquals("validated records", verified.value)
        assertArrayEquals(fixture.catalog, File(fixture.backup, fixture.generation.catalogPath).readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.originalFile.readBytes())
        assertEquals(emptyList<String>(), fixture.scratch.list().orEmpty().toList())
    }

    @Test fun aChangedCatalogIsRejectedBeforeTheRowReaderRuns() {
        val fixture = fixture()
        File(fixture.backup, fixture.generation.catalogPath).writeBytes(byteArrayOf(9, 6, 5))
        assertThrows(IOException::class.java) {
            fixture.reader.read<Unit>(8) { _, _ -> error("Unauthenticated bytes reached the row reader") }
        }
        assertFalse(fixture.scratch.list().orEmpty().any())
    }

    @Test fun scratchCannotBeInsideTheBackupEvenAtTheLowerReadBoundary() {
        val fixture = fixture()
        val scratch = File(fixture.backup, "owned-test-child").apply { check(mkdir()) }
        val reader = LibraryBackupRead(fixture.store, scratch.toPath(), 1024)
        assertThrows(IOException::class.java) {
            reader.read(8) { _, _ -> LibraryBackupRead.Projection(Unit, listOf(fixture.original)) }
        }
        assertEquals(emptyList<String>(), scratch.list().orEmpty().toList())
    }

    @Test fun oversizedOrUnsupportedMarkersNeverReachTheRowReader() {
        for ((schema, maximum) in listOf(9 to 1024L, 8 to 2L)) {
            val fixture = fixture(schema, maximum)
            assertThrows(IOException::class.java) {
                fixture.reader.read<Unit>(8) { _, _ -> error("Unsupported catalog reached the row reader") }
            }
            assertFalse(fixture.scratch.list().orEmpty().any())
        }
    }

    @Test fun aSameLengthCorruptOriginalPreventsReadSuccess() {
        val fixture = fixture()
        val damage = byteArrayOf(1, 3, 2)
        fixture.originalFile.writeBytes(damage)
        assertThrows(IOException::class.java) {
            fixture.reader.read(8) { _, _ -> LibraryBackupRead.Projection(Unit, listOf(fixture.original)) }
        }
        assertArrayEquals(damage, fixture.originalFile.readBytes())
    }

    @Test fun anAliasedOriginalRefusesWithAnIoFailureWithoutTouchingTheLinkTarget() {
        val fixture = fixture()
        val unexpected = ObjectId("f".repeat(64), 3)
        val link = File(fixture.backup, "objects/${unexpected.sha256}").toPath()
        try { Files.createSymbolicLink(link, fixture.originalFile.toPath()) }
        catch (unsupported: UnsupportedOperationException) { assumeNoException(unsupported) }
        catch (unprivileged: IOException) { assumeNoException(unprivileged) }
        assertThrows(IOException::class.java) {
            fixture.reader.read(8) { _, _ -> LibraryBackupRead.Projection(Unit, listOf(unexpected)) }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.originalFile.readBytes())
    }

    @Test fun replacingTheCatalogAtTheSameLocatorDoesNotAcknowledgeTheEarlierCopy() {
        val fixture = fixture()
        val damage = byteArrayOf(9, 6, 5)
        val catalog = File(fixture.backup, fixture.generation.catalogPath)
        assertThrows(IOException::class.java) {
            fixture.reader.read(8) { _, _ ->
                catalog.writeBytes(damage)
                LibraryBackupRead.Projection(Unit, listOf(fixture.original))
            }
        }
        assertArrayEquals(damage, catalog.readBytes())
    }

    @Test fun aNewTipDuringValidationNeverAcknowledgesTheSupersededBackup() {
        val fixture = fixture()
        val successor = fixture.generation.copy(id = "1bbb6a89-836f-4858-811d-e73237b95422", predecessorId = fixture.generation.id)
        assertThrows(IOException::class.java) {
            fixture.reader.read(8) { _, _ ->
                fixture.store.createMarker(successor, successor.encode())
                LibraryBackupRead.Projection(Unit, listOf(fixture.original))
            }
        }
        assertEquals(successor, fixture.store.readTip())
    }

    private class Fixture(
        val backup: File, val scratch: File, val store: LocalLibraryDirectory, val reader: LibraryBackupRead,
        val generation: LibraryGeneration, val catalog: ByteArray, val original: ObjectId,
    ) {
        val originalFile = File(backup, "objects/${original.sha256}")
    }

    private fun fixture(schema: Int = 8, maximum: Long = 1024): Fixture {
        val backup = temporary.newFolder().canonicalFile
        val scratch = temporary.newFolder().canonicalFile
        val store = LocalLibraryDirectory(backup, DirectorySync { true })
        val catalog = byteArrayOf(7, 6, 5)
        val bytes = byteArrayOf(1, 2, 3)
        val original = identity(bytes)
        val marker = LibraryGeneration("26ff0221-9e6e-4aae-8c4b-39aa9ea07d32", "9dfc2b46-9d57-43f4-946a-ea1670e78616", null, schema, identity(catalog))
        store.createCatalog(marker) { it.write(catalog) }
        store.create(original) { it.write(bytes) }
        store.createMarker(marker, marker.encode())
        return Fixture(backup, scratch, store, LibraryBackupRead(store, scratch.toPath(), maximum), marker, catalog, original)
    }

    private fun identity(bytes: ByteArray) = ObjectId(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
        bytes.size.toLong(),
    )
}
