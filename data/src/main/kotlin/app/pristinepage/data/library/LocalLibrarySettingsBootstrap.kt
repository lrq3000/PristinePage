package app.pristinepage.data.library

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.ActiveLibraryFence.Identity
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

/** First-use upgrade in the app's durable files directory. A pre-existing settings root can never bootstrap a second selection. */
internal class LocalLibrarySettingsBootstrap(
    filesDirectory: File, private val sync: DirectorySync = PlatformDirectorySync,
) {
    private val files = filesDirectory.absoluteFile.toPath()

    /** Cooperating Android callers share this process lane. A multiprocess consumer would need the same exclusive setup lane across processes. */
    fun open(): LocalSelectedLibraryAuthority = synchronized(INITIALIZATION) {
        if (files.toRealPath() != files || !Files.isDirectory(files, NOFOLLOW_LINKS)) {
            throw IOException("The private library settings parent is unavailable")
        }
        val settings = files.resolve("library-settings")
        if (!Files.notExists(settings, NOFOLLOW_LINKS)) return@synchronized readExisting(settings)
        if (!sync.sync(files.toFile())) throw IOException("The library settings directory cannot be made durable")
        Files.newDirectoryStream(files, ".library-settings-pending-*").use {
            if (it.asSequence().take(4).count() == 4) throw IOException("Retained library settings stages need attention")
        }
        // Force a complete default pointer inside the staging directory before giving the settings root its durable name.
        val stage = Files.createTempDirectory(files, ".library-settings-pending-")
        val initial = Selection(Location.Default, Identity(UUID.randomUUID().toString(), 1))
        if (!LocalSelectedLibraryAuthority(stage.toFile(), sync).initializeDefault(initial)) {
            throw IOException("The initial library selection was refused")
        }
        if (!Files.notExists(settings, NOFOLLOW_LINKS)) throw IOException("The library settings location became occupied")
        Files.move(stage, settings, ATOMIC_MOVE)
        readExisting(settings)
    }

    private fun readExisting(settings: Path): LocalSelectedLibraryAuthority {
        if (!Files.isDirectory(settings, NOFOLLOW_LINKS) || settings.toRealPath() != settings) {
            throw IOException("The private library settings location is unavailable")
        }
        if (!sync.sync(files.toFile())) throw IOException("The library settings root could not be acknowledged")
        return LocalSelectedLibraryAuthority(settings.toFile(), sync).also {
            if (it.read() == null) throw IOException("The existing library settings have no active selection")
        }
    }

    private companion object { val INITIALIZATION = Any() }
}
