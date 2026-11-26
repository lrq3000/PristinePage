package app.pristinepage.data.library

import android.content.Context
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Start an empty detached app-private folder. The caller retains exclusive stable ownership, knows the platform filesDir entry is durable, and owns scratch outside every library. No activation or retirement authority. */
internal class RoomLibraryEmptyFolder(
    context: Context,
    private val scratchDirectory: File,
    private val sync: DirectorySync = PlatformDirectorySync,
) {
    private val app = context.applicationContext
    private val reader = RoomLibraryCatalogReader(app, MAX_PORTABLE_BYTES)

    class Proposal internal constructor(internal val root: Path, internal val rootKey: String)

    suspend fun prepare(folder: File): Proposal = withContext(Dispatchers.IO) {
        val privateFiles = app.filesDir.toPath().toRealPath()
        val root = canonicalLibraryDirectory(folder, privateFiles)
        val key = localLibraryRootKey(root)
        requireEmpty(root, key)
        Proposal(root, key)
    }

    /** A failure can leave a visible unacknowledged marker. Do not retry as a fresh initialization or authorize activation from a mere detached read. */
    suspend fun start(confirmed: Proposal): LibraryGeneration = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        val privateFiles = app.filesDir.toPath().toRealPath()
        val root = canonicalLibraryDirectory(confirmed.root.toFile(), privateFiles)
        val scratch = canonicalLibraryDirectory(scratchDirectory, privateFiles)
        if (root.startsWith(scratch) || scratch.startsWith(root) || Files.isSameFile(root, scratch)) {
            throw IOException("Empty library and private scratch locations overlap")
        }
        requireEmpty(root, confirmed.rootKey)
        if (scratch.toFile().usableSpace < 3 * MAX_PORTABLE_BYTES) throw IOException("Not enough private space to stage an empty library")
        val stage = newStage(scratch)
        val catalog = stage.resolve("catalog.sqlite")
        val empty = RoomLibrarySavedCatalog.Snapshot(emptyList(), emptyList(), emptyList(), emptyList())
        val version = RoomLibraryCatalogStage(app).write(empty, catalog.toFile())
        reader.verifyStagedProjection(catalog.toFile(), empty, checkpoint)
        val generation = LibraryGeneration(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, version,
            fingerprintClosedLibraryCatalog(catalog.toFile(), checkpoint))
        if (minOf(root.toFile().usableSpace, scratch.toFile().usableSpace) < 2 * generation.catalog.byteCount + 64 * 1024) {
            throw IOException("Not enough space to publish an empty library")
        }
        // Confirm links back to an app-owned durable ancestor before any destination publication.
        var directory = root
        while (directory != privateFiles) {
            checkpoint()
            directory = checkNotNull(directory.parent)
            if (!sync.sync(directory.toFile())) throw IOException("Empty library directory durability is unavailable")
        }
        requireEmpty(root, confirmed.rootKey)
        val destination = LocalLibraryDirectory(root.toFile(), sync)
        val published = LibraryCatalogPublication(destination, stage, checkpoint).publish(generation, MAX_PORTABLE_BYTES,
            { Files.newInputStream(catalog, READ, NOFOLLOW_LINKS) },
        ) { file ->
            reader.verifyStagedProjection(file, empty, checkpoint)
            if (localLibraryRootKey(root) != confirmed.rootKey) throw IOException("Empty library root changed during publication")
        }
        if (localLibraryRootKey(root) != confirmed.rootKey || destination.readTip(checkpoint) != published) {
            throw IOException("Empty library publication changed")
        }
        try {
            Files.deleteIfExists(catalog)
            Files.deleteIfExists(stage)
        } catch (_: IOException) { /* Only private attempt files can be removed; a successful generation remains valid. */ }
        published
    }

    private fun newStage(scratch: Path): Path = synchronized(STAGE_ADMISSION) {
        val attempts = scratch.resolve("empty-library-attempts")
        if (Files.notExists(attempts, NOFOLLOW_LINKS)) Files.createDirectory(attempts)
        if (!Files.isDirectory(attempts, NOFOLLOW_LINKS) || attempts.toRealPath() != attempts) {
            throw IOException("Empty-library staging directory is not owned")
        }
        Files.newDirectoryStream(attempts).use {
            if (it.asSequence().take(4).count() == 4) throw IOException("Retained empty-library stages need attention")
        }
        Files.createTempDirectory(attempts, "attempt-")
    }

    private fun requireEmpty(root: Path, key: String) {
        if (localLibraryRootKey(root) != key) throw IOException("Confirmed empty library folder changed")
        Files.newDirectoryStream(root).use {
            if (it.iterator().hasNext()) throw IOException("Start empty requires a folder with no files")
        }
        if (localLibraryRootKey(root) != key) throw IOException("Confirmed empty library folder changed")
    }

    private companion object { val STAGE_ADMISSION = Any() }
}
