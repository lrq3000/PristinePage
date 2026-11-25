package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.LocalLibrarySettingsBootstrap
import app.pristinepage.data.library.LocalSelectedLibraryAuthority
import java.io.IOException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LocalLibrarySettingsBootstrapTest {
    @get:Rule val roots = TemporaryFolder()
    private val sync = DirectorySync { true }

    @Test fun readingOnlyAnExistingSelectionDoesNotCreateDefaultSettings() {
        val files = roots.newFolder().canonicalFile
        assertEquals(null, LocalLibrarySettingsBootstrap(files, sync).openExisting())
        assertEquals(emptyList(), files.list().orEmpty().toList())
    }

    @Test fun firstOpenAssignsOnePersistentDefaultActivation() {
        val files = roots.newFolder().canonicalFile
        val initial = assertIs<LocalSelectedLibraryAuthority.Selected.Default>(LocalLibrarySettingsBootstrap(files, sync).open().read())
        val reopened = assertIs<LocalSelectedLibraryAuthority.Selected.Default>(LocalLibrarySettingsBootstrap(files, sync).open().read())
        assertEquals(initial, reopened)
        assertEquals(1L, initial.selection.identity.activation)
        assertEquals(Location.Default, initial.selection.location)
    }

    @Test fun aMissingPointerUnderAnExistingSettingsRootNeverReinitializesDefault() {
        val files = roots.newFolder().canonicalFile
        LocalLibrarySettingsBootstrap(files, sync).open()
        val settings = files.resolve("library-settings")
        check(settings.resolve("active-library").delete())
        val unrelated = settings.resolve("retain.txt").apply { writeText("Preserved") }
        assertFailsWith<IOException> { LocalLibrarySettingsBootstrap(files, sync).open() }
        assertEquals(listOf(unrelated), settings.listFiles().orEmpty().toList())
    }

    @Test fun anUnacknowledgedDirectoryPublicationIsReconciledWithoutMintingAnotherDefault() {
        val files = roots.newFolder().canonicalFile
        val settings = files.resolve("library-settings")
        val failFinalBarrier = DirectorySync { directory -> directory != files || !settings.exists() }
        assertFailsWith<IOException> { LocalLibrarySettingsBootstrap(files, failFinalBarrier).open() }
        val visible = assertIs<LocalSelectedLibraryAuthority.Selected.Default>(LocalSelectedLibraryAuthority(settings, sync).read())
        assertEquals(visible, LocalLibrarySettingsBootstrap(files, sync).open().read())
        assertEquals(listOf(settings), files.listFiles().orEmpty().toList())
    }

    @Test fun definitePrePublicationFailuresDoNotFillTheBootstrapStageBudget() {
        val files = roots.newFolder().canonicalFile
        val failing = DirectorySync { directory -> !directory.name.startsWith(".library-settings-pending-") }
        repeat(4) { assertFailsWith<IOException> { LocalLibrarySettingsBootstrap(files, failing).open() } }
        assertEquals(emptyList(), files.list().orEmpty().toList())
        val recovered = LocalLibrarySettingsBootstrap(files, sync).open().read()
        assertIs<LocalSelectedLibraryAuthority.Selected.Default>(recovered)
        assertEquals(recovered, LocalLibrarySettingsBootstrap(files, sync).open().read())
    }

    @Test fun anUnconfirmedParentOfTheFilesDirectoryCannotAcknowledgeFirstUse() {
        val files = roots.newFolder().canonicalFile
        val failing = DirectorySync { directory -> directory != files.parentFile }
        assertFailsWith<IOException> { LocalLibrarySettingsBootstrap(files, failing).open() }
    }
}
