package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryObjectTransfer
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalLibraryDirectory
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val abc = ObjectId("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3)
    private val generation = LibraryGeneration(
        "16085bae-ae25-4fa4-8c69-9a8982ef6c13", "96b461d1-bc41-4bca-ad7e-53415db3f1a2", null, 8, abc,
    )

    @Test fun aPartialWriteRemainsOutOfTheFinalObjectAndRetryKeepsAnEarlierObject() {
        val root = temporary.newFolder()
        val directory = LocalLibraryDirectory(root, DirectorySync { true })
        val first = ObjectId("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", 5)
        val transfer = LibraryObjectTransfer(directory)
        transfer.prepare(listOf(LibraryObjectTransfer.Requirement(first) { "hello".byteInputStream() }), 0, 5).copyAndVerify()
        assertThrows(IOException::class.java) {
            directory.create(abc) { sink ->
                sink.write('a'.code)
                throw IOException("Interrupted during copy")
            }
        }
        assertNull(directory.open(abc))
        val prepared = transfer.prepare(listOf(
            LibraryObjectTransfer.Requirement(first) { "hello".byteInputStream() },
            LibraryObjectTransfer.Requirement(abc) { "abc".byteInputStream() },
        ), 0, 3)
        assertEquals(3L, prepared.missingBytes)
        prepared.copyAndVerify()
        assertEquals("abc", directory.open(abc)!!.bufferedReader().use { it.readText() })
        assertEquals("hello", directory.open(first)!!.bufferedReader().use { it.readText() })
    }

    @Test fun afterAnUnacknowledgedDirectorySyncTheCompleteObjectCanBeRecoveredButNotOverwritten() {
        val root = temporary.newFolder()
        var interruptSync = false
        val sync = DirectorySync { directory ->
            if (interruptSync && File(directory, abc.sha256).exists()) throw IOException("Interrupted before sync acknowledgement")
            true
        }
        val directory = LocalLibraryDirectory(root, sync)
        interruptSync = true
        assertThrows(IOException::class.java) { directory.create(abc) { it.write("abc".toByteArray()) } }
        interruptSync = false
        val retry = LibraryObjectTransfer(directory).prepare(listOf(LibraryObjectTransfer.Requirement(abc) { "abc".byteInputStream() }), 0, 0)
        assertEquals(0L, retry.missingBytes)
        retry.copyAndVerify()
        assertThrows(FileAlreadyExistsException::class.java) { directory.create(abc) { fail("Existing payload must not open a sink") } }
        assertEquals("abc", directory.open(abc)!!.bufferedReader().use { it.readText() })
    }

    @Test fun catalogAndMarkerNeverReplaceAnExistingLeaf() {
        val directory = LocalLibraryDirectory(temporary.newFolder(), DirectorySync { true })
        directory.createCatalog(generation) { it.write("abc".toByteArray()) }
        directory.createMarker(generation, generation.encode())
        assertThrows(FileAlreadyExistsException::class.java) { directory.createCatalog(generation) { fail("Unexpected replacement") } }
        assertThrows(FileAlreadyExistsException::class.java) { directory.createMarker(generation, generation.encode()) }
        assertEquals(generation, directory.openMarker(generation)!!.use(LibraryGeneration::read))
        assertEquals("abc", directory.openCatalog(generation)!!.bufferedReader().use { it.readText() })
    }

    @Test fun unsupportedDirectoryDurabilityRefusesBeforeCreatingACommittedObject() {
        val directory = LocalLibraryDirectory(temporary.newFolder(), DirectorySync { false })
        assertThrows(IOException::class.java) { directory.create(abc) { fail("Must refuse before consuming the writer") } }
        assertNull(directory.open(abc))
    }

    @Test fun discoveryObservesTheExactMarkerChainAndLeavesTheFolderUnchanged() {
        val root = temporary.newFolder()
        val directory = LocalLibraryDirectory(root, DirectorySync { true })
        val second = generation.copy(id = "11ec853f-ea18-4cf8-af9e-89a7ccc50c8a", predecessorId = generation.id)
        directory.createMarker(second, second.encode())
        directory.createMarker(generation, generation.encode())
        val markerFolder = File(root, "generations")
        val unknown = File(root, "unrelated.txt").apply { writeText("keep") }
        val before = markerFolder.listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertEquals(second, directory.readTip())
        assertEquals(before, markerFolder.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertEquals("keep", unknown.readText())
    }

    @Test fun aHigherMarkerWithAWrongFilenameDoesNotFallBackToTheOlderTip() {
        val root = temporary.newFolder()
        val directory = LocalLibraryDirectory(root, DirectorySync { true })
        directory.createMarker(generation, generation.encode())
        val later = generation.copy(id = "11ec853f-ea18-4cf8-af9e-89a7ccc50c8a", predecessorId = generation.id)
        val wrongName = File(root, "generations/24ba7913-dfd6-4a7b-8e5e-653206116f31.complete")
        wrongName.writeBytes(later.encode())
        assertThrows(IOException::class.java) { directory.readTip() }
        assertArrayEquals(later.encode(), wrongName.readBytes())
    }
}
