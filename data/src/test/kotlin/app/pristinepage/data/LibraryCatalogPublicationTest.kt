package app.pristinepage.data

import app.pristinepage.data.library.LibraryCatalogPublication
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryCatalogPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val predecessor = "01284418-aa30-4cb7-8840-e5d8fa893e26"
    private val generation = LibraryGeneration(
        "16085bae-ae25-4fa4-8c69-9a8982ef6c13", "96b461d1-bc41-4bca-ad7e-53415db3f1a2", predecessor, 8,
        ObjectId("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3),
    )

    @Test fun reopensNewCatalogBeforePublishingAndKeepsPreviousGeneration() {
        val directory = fixture()
        val published = publish(directory) { reopened ->
            assertFalse(directory.marker.exists())
            assertEquals("abc", reopened.readText())
            assertEquals("old catalog", directory.previous.readText())
        }
        assertEquals(generation, published)
        assertEquals(generation, directory.marker.inputStream().use(LibraryGeneration::read))
        assertEquals("old catalog", directory.previous.readText())
    }

    @Test fun doesNotPublishWhenActualCandidateCannotBeReopened() {
        val directory = fixture()
        assertThrows(IOException::class.java) { publish(directory) { throw IOException("SQLite reopen refused") } }
        assertFalse(directory.marker.exists())
        assertEquals("abc", directory.catalog.readText())
        assertEquals("old catalog", directory.previous.readText())
    }

    @Test fun verifiesDestinationBytesBeforeHandingThemToCatalogReader() {
        val directory = fixture()
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit) {
                directory.createCatalog(generation, write)
                directory.catalog.writeText("xyz")
            }
        }
        assertThrows(IOException::class.java) {
            publisher(store).publish(generation, 100, { "abc".byteInputStream() }) { fail("Corrupt candidate reached reopen") }
        }
        assertFalse(directory.marker.exists())
    }

    @Test fun retryAfterMarkerWriteLostItsAcknowledgmentReusesTheCompleteGeneration() {
        val directory = fixture()
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
                directory.createMarker(generation, bytes)
                throw IOException("Lost provider acknowledgment")
            }
        }
        assertThrows(IOException::class.java) {
            publisher(store).publish(generation, 100, { "abc".byteInputStream() }) {}
        }
        assertEquals(generation, publisher(directory).publish(generation, 100, { error("Must not recopy") }) {})
        assertEquals("old catalog", directory.previous.readText())
    }

    @Test fun aVisibleMarkerIsNotSuccessfulWhileItsDurabilityBarrierStillFails() {
        val directory = fixture()
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
                directory.createMarker(generation, bytes)
                throw IOException("Lost acknowledgement after exposing marker")
            }

            override fun confirmMarkerDurability(generation: LibraryGeneration) {
                throw IOException("Directory durability could not be confirmed")
            }
        }
        val publisher = publisher(store)
        assertThrows(IOException::class.java) { publisher.publish(generation, 100, { "abc".byteInputStream() }) {} }
        assertTrue(directory.marker.exists())
        assertThrows(IOException::class.java) { publisher.publish(generation, 100, { error("Must not recopy") }) {} }
        assertEquals("old catalog", directory.previous.readText())
    }

    @Test fun secondOpenCannotSubstituteTheCatalogAfterByteProof() {
        val directory = fixture()
        directory.catalog.writeText("abc")
        var opens = 0
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun openCatalog(generation: LibraryGeneration): InputStream? =
                if (++opens == 1) directory.openCatalog(generation) else "xyz".byteInputStream()
        }
        assertThrows(IOException::class.java) {
            publisher(store).publish(generation, 100, { error("Must not recopy") }) { reopened ->
                assertEquals("abc", reopened.readText())
            }
        }
        assertFalse(directory.marker.exists())
    }

    @Test fun sizeAdmissionPrecedesCatalogOrDestinationAccess() {
        val directory = fixture()
        val store = object : LibraryCatalogPublication.Store by directory {
            override fun openCatalog(generation: LibraryGeneration): InputStream? = error("Must not access catalog")
        }
        assertThrows(IOException::class.java) {
            publisher(store).publish(generation, 2, { error("Must not access source") }) { fail("Must not reopen") }
        }
        assertFalse(directory.catalog.exists())
        assertFalse(directory.marker.exists())
    }

    private fun fixture() = Folder(temporary.newFolder(), generation, predecessor)
    private fun publisher(store: LibraryCatalogPublication.Store) =
        LibraryCatalogPublication(store, temporary.newFolder().toPath())
    private fun publish(directory: Folder, verify: (File) -> Unit) =
        publisher(directory).publish(generation, 100, { "abc".byteInputStream() }, verify)

    private class Folder(root: File, generation: LibraryGeneration, predecessor: String) : LibraryCatalogPublication.Store {
        val catalog = File(root, generation.catalogPath).apply { checkNotNull(parentFile).mkdirs() }
        val previous = File(catalog.parentFile, "$predecessor.sqlite").apply { writeText("old catalog") }
        val marker = File(root, "${generation.id}.complete")
        override fun openCatalog(generation: LibraryGeneration) = catalog.takeIf(File::isFile)?.inputStream()
        override fun openMarker(generation: LibraryGeneration) = marker.takeIf(File::isFile)?.inputStream()
        override fun createCatalog(generation: LibraryGeneration, write: (OutputStream) -> Unit) {
            Files.newOutputStream(catalog.toPath(), CREATE_NEW, WRITE).use(write)
        }
        override fun createMarker(generation: LibraryGeneration, bytes: ByteArray) {
            Files.newOutputStream(marker.toPath(), CREATE_NEW, WRITE).use { it.write(bytes) }
        }
        override fun confirmPayloadDurability(generation: LibraryGeneration) = Unit
        override fun confirmMarkerDurability(generation: LibraryGeneration) = Unit
    }
}
