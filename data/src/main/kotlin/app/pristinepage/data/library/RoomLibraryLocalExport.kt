package app.pristinepage.data.library

import android.content.Context
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.files.PlatformDirectorySync
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** First local-folder consumer. Uses only canonical app-private locations, a stable original root, and a caller-owned scratch directory. No SAF grant or live library switch. */
internal class RoomLibraryLocalExport(
    context: Context,
    private val sourceDatabase: PristineDatabase,
    private val originalRoot: File,
    private val scratchDirectory: File,
) {
    private val application = context.applicationContext
    suspend fun writeNewFolder(destination: File): LibraryGeneration = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        val privateFiles = application.filesDir.toPath().toRealPath()
        val parent = canonicalLibraryDirectory(checkNotNull(destination.absoluteFile.parentFile), privateFiles)
        val target = parent.resolve(destination.name)
        val scratch = canonicalLibraryDirectory(scratchDirectory, privateFiles)
        val originals = canonicalLibraryDirectory(originalRoot, privateFiles)
        if (target != destination.absoluteFile.toPath() || target.startsWith(scratch) || scratch.startsWith(target) ||
            target.startsWith(originals) || originals.startsWith(target) || overlap(scratch, originals)
        ) {
            throw IOException("Library export locations overlap")
        }
        if (!Files.notExists(target, NOFOLLOW_LINKS)) throw IOException("Library export requires a new folder")
        val snapshot = RoomLibrarySavedCatalog(sourceDatabase).capture()
        // A scratch database and its rollback journal are separate from the later destination/scratch copy budget.
        if (scratch.toFile().usableSpace < 3 * MAX_PORTABLE_BYTES) throw IOException("Not enough space to stage a library catalog")
        checkpoint()
        val stage = newStage(scratch)
        val closed = File(stage, "catalog.sqlite")
        val input = RoomLibraryExportInput.prepare(application, snapshot, originals.toFile(), closed, checkpoint)
        val generation = input.generation
        val requirements = input.requirements
        // This estimate deliberately assumes a shared volume; read usable space after staging, then reserve both remaining catalog copies plus filesystem headroom. Runtime IO still can fail.
        val capacity = minOf(parent.toFile().usableSpace, scratch.toFile().usableSpace)
        val metadataReservation = input.metadataBytes(scratchCopies = 1)
        if (input.requiredSpace(scratchCopies = 1) > capacity) throw IOException("Not enough space for library export")
        checkpoint()
        forceAncestry(parent, privateFiles, checkpoint)
        // The directory create, not the earlier absence check, wins ownership. A competing export must not adopt this folder.
        Files.createDirectory(target)
        val adapter = LocalLibraryDirectory(target.toFile())
        val transfer = LibraryObjectTransfer(adapter, checkpoint).prepare(requirements, metadataReservation, capacity)
        checkpoint()
        transfer.copyAndVerify()
        val published = LibraryCatalogPublication(adapter, scratch, checkpoint).publish(
            generation, MAX_PORTABLE_BYTES, input::openCatalog, requiresObjects = requirements.isNotEmpty(),
        ) { verification ->
            input.verifyCatalog(verification)
            LibraryObjectTransfer(adapter, checkpoint).prepare(
                requirements.distinctBy { it.id }.map { LibraryObjectTransfer.Requirement(it.id, null) }, 0L, 0L,
            )
        }
        // Only this attempt's immutable, verified duplicate can go away. Cleanup failure must not hide a valid export.
        try {
            Files.deleteIfExists(closed.toPath())
            Files.deleteIfExists(stage.toPath())
        } catch (_: IOException) { /* Keep the owned stage for explicit maintenance, not an automatic export replay. */ }
        published
    }

    private fun newStage(scratch: Path): File = synchronized(STAGE_ADMISSION) {
        Files.newDirectoryStream(scratch, "library-stage-*").use {
            if (it.asSequence().take(4).count() == 4) throw IOException("Retained library staging needs attention")
        }
        Files.createTempDirectory(scratch, "library-stage-").toFile()
    }

    private fun forceAncestry(parent: Path, privateFiles: Path, checkpoint: () -> Unit) {
        var child = parent
        while (child != privateFiles) {
            checkpoint()
            val containing = checkNotNull(child.parent)
            if (!PlatformDirectorySync.sync(containing.toFile())) throw IOException("Library directory durability is unavailable")
            child = containing
        }
    }

    private fun overlap(a: Path, b: Path) = a.startsWith(b) || b.startsWith(a) || Files.isSameFile(a, b)

    private companion object {
        val STAGE_ADMISSION = Any()
    }
}
