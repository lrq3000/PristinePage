package app.pristinepage.data.library

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.ActiveLibraryFence.Identity
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
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
        forceFilesAndParent()
        try {
            Files.newDirectoryStream(files, ".library-settings-pending-*").use {
                if (it.asSequence().take(4).count() == 4) throw IOException("Retained library settings stages need attention")
            }
        } catch (failure: DirectoryIteratorException) { throw IOException("The private settings stages could not be read", failure) }
        // Force a complete default pointer inside the staging directory before giving the settings root its durable name.
        val stage = Files.createTempDirectory(files, ".library-settings-pending-")
        try {
            val initial = Selection(Location.Default, Identity(UUID.randomUUID().toString(), 1))
            if (!LocalSelectedLibraryAuthority(stage.toFile(), sync).initializeDefault(initial)) {
                throw IOException("The initial library selection was refused")
            }
            if (!Files.notExists(settings, NOFOLLOW_LINKS)) throw IOException("The library settings location became occupied")
        } catch (failure: IOException) {
            retireUnpublished(stage)
            throw failure
        }
        // After invoking the move, a failure is uncertain. Keep the stage (if any) and never remove the final root.
        Files.move(stage, settings, ATOMIC_MOVE)
        readExisting(settings)
    }

    private fun retireUnpublished(stage: Path) {
        try {
            if (stage.toRealPath() != stage || !Files.isDirectory(stage, NOFOLLOW_LINKS)) return
            val owned = Files.newDirectoryStream(stage).use { it.asSequence().take(3).toList() }
            if (owned.size >= 3 || owned.any {
                    val leaf = it.fileName.toString()
                    !Files.isRegularFile(it, NOFOLLOW_LINKS) ||
                        (leaf != "active-library" && !(leaf.startsWith(".active-library-") && leaf.endsWith(".pending")))
                }
            ) return
            owned.forEach { Files.delete(it) }
            Files.delete(stage)
        } catch (_: IOException) { /* Retain unconfirmed names; the final root was not touched. */ }
        catch (_: DirectoryIteratorException) { /* Unenumerated children are not cleanup targets. */ }
    }

    private fun forceFilesAndParent() {
        // Android may have created filesDir lazily. Its installed app-data parent is the known stable ancestor.
        if (!sync.sync(files.toFile()) || !sync.sync(checkNotNull(files.parent).toFile())) {
            throw IOException("The library settings root could not be acknowledged")
        }
    }

    private fun readExisting(settings: Path): LocalSelectedLibraryAuthority {
        if (!Files.isDirectory(settings, NOFOLLOW_LINKS) || settings.toRealPath() != settings) {
            throw IOException("The private library settings location is unavailable")
        }
        forceFilesAndParent()
        return LocalSelectedLibraryAuthority(settings.toFile(), sync).also {
            if (it.read() == null) throw IOException("The existing library settings have no active selection")
        }
    }

    private companion object { val INITIALIZATION = Any() }
}
