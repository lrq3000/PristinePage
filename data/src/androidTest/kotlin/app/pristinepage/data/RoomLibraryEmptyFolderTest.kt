package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.RoomLibraryEmptyFolder
import app.pristinepage.data.library.RoomLibraryPortableView
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryEmptyFolderTest {
    @Test fun startingEmptyPublishesOnlyAnEmptyVerifiedCatalogAndDoesNotTouchThePreviousLibrary() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val parent = File(context.filesDir.canonicalFile, "empty-library-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
        val scratch = File(parent, "scratch").apply { check(mkdir()) }
        val destination = File(parent, "destination").apply { check(mkdir()) }
        val previous = File(parent, "previous").apply { check(mkdir()) }
        val existing = File(previous, "retained.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val starter = RoomLibraryEmptyFolder(context, scratch)

        val proposal = starter.prepare(destination)
        val unrelated = File(destination, "unrelated.txt").apply { writeText("keep") }
        assertTrue(runCatching { starter.start(proposal) }.exceptionOrNull() is IOException)
        assertEquals(listOf("unrelated.txt"), destination.list().orEmpty().toList())
        assertEquals("keep", unrelated.readText())
        check(unrelated.renameTo(File(parent, "unrelated.txt"))) // Test-owned fixture, preserved.

        val generation = starter.start(proposal)
        assertNull(generation.predecessorId)
        assertFalse(File(destination, "objects").exists())
        val view = RoomLibraryPortableView(context, scratch)
        val opened = view.open(view.prepare(destination), generation)
        assertTrue(opened.catalog.albums.isEmpty())
        assertTrue(opened.catalog.originals.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), existing.readBytes())
        assertTrue(runCatching { starter.prepare(destination) }.exceptionOrNull() is IOException)
    }

    @Test fun visibleInitialMarkerAfterAFailedBarrierIsNotAcknowledgedOrReplayedAsNew() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val parent = File(context.filesDir.canonicalFile, "empty-library-sync-${UUID.randomUUID()}").apply { check(mkdir()) }
        val scratch = File(parent, "scratch").apply { check(mkdir()) }
        val destination = File(parent, "destination").apply { check(mkdir()) }
        val refusing = DirectorySync { directory ->
            !(directory.name == "generations" && directory.listFiles().orEmpty().any { it.name.endsWith(".complete") }) &&
                PlatformDirectorySync.sync(directory)
        }
        val starter = RoomLibraryEmptyFolder(context, scratch, refusing)
        val proposal = starter.prepare(destination)
        assertTrue(runCatching { starter.start(proposal) }.exceptionOrNull() is IOException)
        val actual = destination.walkTopDown().filter(File::isFile).associate { it.relativeTo(destination).path to it.readBytes().toList() }
        assertEquals(1, actual.keys.count { it.endsWith(".complete") })
        assertEquals(1, actual.keys.count { it.endsWith(".sqlite") })
        assertEquals(1, scratch.walkTopDown().count { it.isFile && it.name == "catalog.sqlite" })
        assertTrue(runCatching { RoomLibraryEmptyFolder(context, scratch).start(proposal) }.exceptionOrNull() is IOException)
        assertEquals(actual, destination.walkTopDown().filter(File::isFile).associate { it.relativeTo(destination).path to it.readBytes().toList() })
    }
}
