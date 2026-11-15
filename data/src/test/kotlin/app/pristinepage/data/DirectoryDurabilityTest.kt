package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.SourceFiles
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Fault injection changes only the real OS sync boundary; actual payload/seal creation, hashing and movement remain exercised on real retained files. */
class DirectoryDurabilityTest {
    private val tx = "9b58d249-0641-4cb7-bbe7-ff805098b21b"
    private val blob = "180aab39-4036-4979-922e-e91a1ded30c4"
    private fun root(): File {
        val parent = File(System.getProperty("user.dir"), "build/test-source-files").apply { mkdirs() }
        return Files.createTempDirectory(parent.toPath(), "sync-").toFile().canonicalFile
    }

    @Test fun newDirectoryLinksAreSyncedThroughTheirContainingAncestry() {
        val root = root(); val synced = mutableListOf<File>()
        val files = SourceFiles(root, DirectorySync { directory -> synced += directory; true })
        val staged = files.stage(tx, blob, ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        val sealed = files.seal(staged, byteArrayOf(10))
        files.move(sealed)
        for (directory in listOf(root, File(root, "staging"), File(root, "staging/$tx"), File(root, "sources"), File(root, "sources/$blob"))) {
            assertTrue("Directory entry ancestry was not synced: ${directory.name}", directory in synced)
        }
    }

    @Test fun operationalSyncFailureRejectsCompletionAndRetainsPayload() {
        val root = root()
        val files = SourceFiles(root, DirectorySync { directory ->
            if (File(directory, "original.part").exists()) throw IOException("synthetic supported-fsync IO fault")
            true
        })
        assertThrows(IOException::class.java) { files.stage(tx, blob, ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3) }
        assertArrayEquals(byteArrayOf(1, 2, 3), File(root, "staging/$tx/original.part").readBytes())
        assertTrue("Operational IO faults are not capability downgrades", files.directorySyncSupported)
        assertFalse(File(root, "staging/$tx/seal.json").exists())
        assertFalse(files.finalFile(blob).exists())
    }

    @Test fun recognizedUnsupportedDirectorySyncIsExplicitButDoesNotFabricateSupport() {
        val root = root(); val files = SourceFiles(root, DirectorySync { false })
        val staged = files.stage(tx, blob, ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        assertEquals(3L, staged.byteCount)
        assertFalse(files.directorySyncSupported)
        assertNull(files.recover(tx, null))
    }
}
