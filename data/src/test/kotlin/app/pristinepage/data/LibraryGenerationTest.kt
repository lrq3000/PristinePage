package app.pristinepage.data

import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class LibraryGenerationTest {
    private val libraryId = "16085bae-ae25-4fa4-8c69-9a8982ef6c13"
    private val generationId = "96b461d1-bc41-4bca-ad7e-53415db3f1a2"
    private val previousId = "01284418-aa30-4cb7-8840-e5d8fa893e26"
    private val abc = ObjectId("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3)
    private val golden = ("pristinepage-library-v1\n16085bae-ae25-4fa4-8c69-9a8982ef6c13\n96b461d1-bc41-4bca-ad7e-53415db3f1a2\n" +
        "01284418-aa30-4cb7-8840-e5d8fa893e26\n8\n3\nba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\n").toByteArray()

    @Test fun committedMarkerBindsLibraryGenerationPredecessorSchemaAndCatalogBytes() {
        val manifest = LibraryGeneration(libraryId, generationId, previousId, 8, abc)
        assertArrayEquals(golden, manifest.encode())
        assertEquals(manifest, LibraryGeneration.read(golden.inputStream()))
        assertEquals("catalogs/96b461d1-bc41-4bca-ad7e-53415db3f1a2.sqlite", manifest.catalogPath)
        manifest.verifyCatalog("abc".byteInputStream())
    }

    @Test fun rootGenerationUsesExplicitAbsenceWithoutAnImplicitMigration() {
        val manifest = LibraryGeneration(libraryId, generationId, null, 8, abc)
        assertEquals(golden.decodeToString().replace(previousId, "-"), manifest.encode().decodeToString())
        assertEquals(manifest, LibraryGeneration.read(manifest.encode().inputStream()))
    }

    @Test fun aMarkerCannotInstructTheAppToReadAnArbitraryCatalogPath() {
        val bytes = golden.decodeToString().replace(generationId, "../unrelated.sqlite").toByteArray()
        assertThrows(IOException::class.java) { LibraryGeneration.read(bytes.inputStream()) }
    }

    @Test fun incompleteUnknownAndNoncanonicalMarkersAreNotACandidate() {
        val text = golden.decodeToString()
        for (invalid in listOf(text.dropLast(1), "$text\n", text.replace("-v1", "-v2"), text.replace("\n3\n", "\n03\n"),
            text.replace("\n8\n", "\n+8\n"), text.replace(previousId, generationId), text.replace(libraryId, libraryId.uppercase()),
            text.replace("\n", "\r\n"), text.replace("\n", "\u0000\n"), text.replace(abc.sha256, abc.sha256.uppercase()),
            text.replace("\n3\n", "\n9223372036854775808\n"))) {
            assertThrows(IOException::class.java) { LibraryGeneration.read(invalid.byteInputStream()) }
        }
    }

    @Test fun markerReadStopsAtItsByteLimitEvenWhenTheStreamNeverEnds() {
        var consumed = 0
        val neverEnds = object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(): Int = 'x'.code.also { consumed++ }
        }
        assertThrows(IOException::class.java) { LibraryGeneration.read(neverEnds) }
        assertEquals(257, consumed)
    }

    @Test fun maximumWidthSchemaAndCatalogLengthRoundTripWithoutAllocatingTheCatalog() {
        val manifest = LibraryGeneration(libraryId, generationId, previousId, Int.MAX_VALUE, abc.copy(byteCount = Long.MAX_VALUE))
        val marker = golden.decodeToString().replace("\n8\n3\n", "\n2147483647\n9223372036854775807\n").toByteArray()
        assertEquals(231, marker.size)
        assertArrayEquals(marker, manifest.encode())
        assertEquals(manifest, LibraryGeneration.read(marker.inputStream()))
    }

    @Test fun catalogReadbackRejectsWrongSizeAndSameLengthWrongBytes() {
        val manifest = LibraryGeneration.read(golden.inputStream())
        for (invalid in listOf("ab", "abcd", "abd")) {
            assertThrows(IOException::class.java) { manifest.verifyCatalog(invalid.byteInputStream()) }
        }
    }
}
