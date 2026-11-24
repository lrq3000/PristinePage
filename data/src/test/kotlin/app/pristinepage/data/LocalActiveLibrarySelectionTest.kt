package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.ActiveLibraryFence.Identity
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import app.pristinepage.data.library.LocalActiveLibrarySelection
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalActiveLibrarySelectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val default = Selection(Location.Default, Identity(id(1), 1))
    private val selected = Selection(Location.Folder(id(2), id(5)), Identity(id(3), 2))

    @Test fun selectionMustAdvanceFromTheExactOldPointerWithoutImplicitBootstrap() {
        val directory = temporary.newFolder().canonicalFile
        val settings = LocalActiveLibrarySelection(directory, DirectorySync { true })
        assertNull(settings.readVerified())
        assertFalse(settings.replace(null, selected))
        assertTrue(settings.replace(null, default))
        assertThrows(IOException::class.java) {
            settings.replace(default.copy(identity = default.identity.copy(libraryId = id(4))), selected)
        }
        assertEquals(default, settings.readVerified())
        assertTrue(settings.replace(default, selected))
        assertEquals(selected, LocalActiveLibrarySelection(directory, DirectorySync { true }).readVerified())
        assertFalse(settings.replace(selected, default))
        assertEquals(selected, settings.readVerified())
    }

    @Test fun aVisiblePointerAfterFailedSyncCannotBeAcknowledgedOrReplacedByDefault() {
        val directory = temporary.newFolder().canonicalFile
        val initial = LocalActiveLibrarySelection(directory, DirectorySync { true })
        assertTrue(initial.replace(null, default))
        val refused = LocalActiveLibrarySelection(directory, DirectorySync { initialPointerIsDefault(directory) })
        assertThrows(IOException::class.java) { refused.replace(default, selected) }
        assertThrows(IOException::class.java) { refused.readVerified() }
        assertThrows(IOException::class.java) { refused.replace(default, selected) }
        assertEquals(selected, initial.readVerified())
        assertFalse(initial.replace(null, default))
    }

    @Test fun malformedPointerIsNotAnEmptyOrDefaultSelection() {
        val directory = temporary.newFolder().canonicalFile
        val settings = LocalActiveLibrarySelection(directory, DirectorySync { true })
        assertTrue(settings.replace(null, default))
        val pointer = directory.listFiles().orEmpty().single()
        val corrupted = pointer.readBytes() + byteArrayOf(0)
        pointer.writeBytes(corrupted)
        assertThrows(IOException::class.java) { settings.readVerified() }
        assertThrows(IOException::class.java) { settings.replace(null, default) }
        assertArrayEquals(corrupted, pointer.readBytes())
    }

    @Test fun anOccupiedPointerPathNeverQualifiesForDefaultBootstrap() {
        val directory = temporary.newFolder().canonicalFile
        val settings = LocalActiveLibrarySelection(directory, DirectorySync { true })
        assertTrue(settings.replace(null, default))
        val pointer = directory.listFiles().orEmpty().single()
        check(pointer.delete()) // Fixture mutation only.
        check(pointer.mkdir())
        assertThrows(IOException::class.java) { settings.readVerified() }
        assertThrows(IOException::class.java) { settings.replace(null, default) }
        assertTrue(pointer.isDirectory)
    }

    @Test fun aRecognizedUnsupportedDirectoryBarrierIsRefusedBeforeAnyMutation() {
        val directory = temporary.newFolder().canonicalFile
        val initial = LocalActiveLibrarySelection(directory, DirectorySync { true })
        assertTrue(initial.replace(null, default))
        val refused = LocalActiveLibrarySelection(directory, DirectorySync { false })
        assertThrows(IOException::class.java) { refused.replace(default, selected) }
        assertEquals(default, initial.readVerified())
        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test fun aValidLookingOneDigitChangeCannotRestoreAnOlderActivation() {
        val directory = temporary.newFolder().canonicalFile
        val settings = LocalActiveLibrarySelection(directory, DirectorySync { true })
        assertTrue(settings.replace(null, default))
        assertTrue(settings.replace(default, selected))
        assertTrue(settings.replace(selected, selected.copy(identity = selected.identity.copy(activation = 3))))
        val pointer = directory.listFiles().orEmpty().single()
        val previous = pointer.readText()
        val altered = previous.replace("\n3\n", "\n2\n")
        assertNotEquals(previous, altered)
        pointer.writeText(altered)
        assertThrows(IOException::class.java) { LocalActiveLibrarySelection(directory, DirectorySync { true }).readVerified() }
    }

    private fun initialPointerIsDefault(directory: File): Boolean =
        directory.listFiles().orEmpty().single { !it.name.endsWith(".pending") }.readText().contains("\n1\n")

    private fun id(value: Int) = UUID(0L, value.toLong()).toString()
}
