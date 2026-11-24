package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.ActiveLibraryFence.Identity
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import app.pristinepage.data.library.LocalActiveLibrarySelection
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryActiveSelectionTest {
    @Test fun aSelectionVisibleAfterFailedAcknowledgmentReopensOnTheActualPrivateFilesystem() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val files = context.filesDir.canonicalFile
        val directory = File(files, "active-library-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
        assertTrue(PlatformDirectorySync.sync(files))
        val default = Selection(Location.Default, Identity(id(), 1))
        val selected = Selection(Location.Folder(id(), id()), Identity(id(), 2))
        val settings = LocalActiveLibrarySelection(directory)
        assertTrue(settings.replace(null, default))
        assertEquals(default, settings.readVerified())
        val refused = LocalActiveLibrarySelection(directory, DirectorySync { folder ->
            folder.listFiles().orEmpty().single { !it.name.endsWith(".pending") }.readText().contains("\n1\n") &&
                PlatformDirectorySync.sync(folder)
        })
        assertThrows(IOException::class.java) { refused.replace(default, selected) }
        assertThrows(IOException::class.java) { refused.readVerified() }
        val reopened = LocalActiveLibrarySelection(directory)
        assertEquals(selected, reopened.readVerified())
        assertFalse(reopened.replace(null, default))
        assertEquals(selected, reopened.readVerified())
    }

    private fun id(): String = UUID.randomUUID().toString()
}
