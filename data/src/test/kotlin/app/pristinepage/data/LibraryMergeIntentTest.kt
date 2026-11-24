package app.pristinepage.data

import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryMergeIntent
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class LibraryMergeIntentTest {
    private val target = generation(1, 2, 'a')
    private val source = generation(3, 4, 'b')
    private val next = generation(1, 5, 'c', target.id)
    private val targetRoot = "d".repeat(64)
    private val sourceRoot = "e".repeat(64)
    private val intent get() = LibraryMergeIntent(targetRoot, sourceRoot, target, source, next)

    @Test fun roundTripBindsBothRootsBothInputsAndTheExactNextCatalog() {
        val bytes = intent.encode()
        val expected = ("pristinepage-library-copy-intent-v1\n$targetRoot\n$sourceRoot\n").encodeToByteArray() +
            target.encode() + source.encode() + next.encode()
        assertArrayEquals(expected, bytes)
        assertEquals(intent, LibraryMergeIntent.read(bytes.inputStream()))
    }

    @Test fun resumesOnlyTheExactChildOrTheUnchangedBaseline() {
        assertEquals(LibraryMergeIntent.Resume.WRITE_MISSING, intent.resume(targetRoot, sourceRoot, target, source, 8))
        assertEquals(LibraryMergeIntent.Resume.VERIFY_PUBLISHED, intent.resume(targetRoot, sourceRoot, next, source, 8))
        val wrongCatalog = next.copy(catalog = ObjectId("f".repeat(64), 4096))
        for (seenTarget in listOf(wrongCatalog, next.copy(id = key(6)), next.copy(id = key(7), predecessorId = next.id),
            target.copy(catalog = target.catalog.copy(sha256 = "f".repeat(64))), next.copy(catalog = next.catalog.copy(byteCount = 8192)))) {
            assertThrows(IOException::class.java) { intent.resume(targetRoot, sourceRoot, seenTarget, source, 8) }
        }
        assertThrows(IOException::class.java) { intent.resume(sourceRoot, targetRoot, target, source, 8) }
        assertThrows(IOException::class.java) { intent.resume(targetRoot, sourceRoot, next, source.copy(id = key(8)), 8) }
        assertThrows(IOException::class.java) {
            intent.resume(targetRoot, sourceRoot, next, source.copy(catalog = source.catalog.copy(sha256 = "f".repeat(64))), 8)
        }
    }

    @Test fun wrongLibraryPredecessorOrAliasedRootsCannotCreateAnIntent() {
        assertThrows(IllegalArgumentException::class.java) { intent.copy(destinationRootKey = sourceRoot) }
        for (bad in listOf(next.copy(libraryId = source.libraryId), next.copy(predecessorId = null), next.copy(catalogSchema = 99))) {
            assertThrows(IllegalArgumentException::class.java) { intent.copy(candidate = bad) }
        }
    }

    @Test fun rejectsAChildThatWouldReuseAKnownAncestorAndAnyImpossibleCatalogExtent() {
        val prior = target.copy(predecessorId = key(7))
        assertThrows(IllegalArgumentException::class.java) { intent.copy(destination = prior, candidate = next.copy(id = key(7))) }
        assertThrows(IllegalArgumentException::class.java) { intent.copy(candidate = next.copy(catalog = ObjectId("f".repeat(64), 511))) }
    }

    @Test fun aFutureIntentCannotNameAnActionUnderTheInstalledSchema() {
        val futureTarget = target.copy(catalogSchema = 99)
        val futureSource = source.copy(catalogSchema = 99)
        val future = intent.copy(destination = futureTarget, incoming = futureSource, candidate = next.copy(catalogSchema = 99))
        val decoded = LibraryMergeIntent.read(future.encode().inputStream())
        assertThrows(IOException::class.java) { decoded.resume(targetRoot, sourceRoot, futureTarget, futureSource, 8) }
    }

    @Test fun noncanonicalTruncatedOrUnboundedIntentNeverPermitsReplay() {
        val bytes = intent.encode()
        for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(10),
            bytes.decodeToString().replace("-intent-v1", "-intent-v2").encodeToByteArray(),
            bytes.decodeToString().replace("\n4096\n", "\n04096\n").encodeToByteArray(),
            bytes.decodeToString().replace(targetRoot, "../local").encodeToByteArray(),
        )) assertThrows(IOException::class.java) { LibraryMergeIntent.read(bad.inputStream()) }
        var reads = 0
        val infinite = object : InputStream() { override fun read(): Int = 'a'.code.also { reads++ } }
        assertThrows(IOException::class.java) { LibraryMergeIntent.read(infinite) }
        assertEquals(1025, reads)
    }

    private fun generation(library: Int, generation: Int, digest: Char, predecessor: String? = null) =
        LibraryGeneration(key(library), key(generation), predecessor, 8, ObjectId(digest.toString().repeat(64), 4096))

    private fun key(value: Int) = "aaaaaaaa-aaaa-4aaa-8aaa-${value.toString().padStart(12, '0')}"
}
