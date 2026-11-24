package app.pristinepage.data.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.files.SourceFiles
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
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
    private val source = SourceFiles(originalRoot)
    private val reader = RoomLibraryCatalogReader(application, MAX_PORTABLE_BYTES)

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
        val version = RoomLibraryCatalogStage(application).write(snapshot, closed)
        reader.read(closed) { catalog -> verifyOriginalReferences(catalog, snapshot.originals, checkpoint) }
        val generation = LibraryGeneration(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, version,
            fingerprintClosedLibraryCatalog(closed, checkpoint))
        val requirements = snapshot.originals.map { blob ->
            LibraryObjectTransfer.Requirement(objectId(blob)) { readRegular(source.finalFile(blob.sourceBlobId)) }
        }
        // This estimate deliberately assumes a shared volume; read usable space after staging, then reserve both remaining catalog copies plus filesystem headroom. Runtime IO still can fail.
        val capacity = minOf(parent.toFile().usableSpace, scratch.toFile().usableSpace)
        val metadataReservation = 2 * generation.catalog.byteCount + 16L * 1024 * (requirements.size + 4L)
        val uniqueSizes = HashMap<String, Long>()
        for (requirement in requirements) {
            val previouslySeen = uniqueSizes.putIfAbsent(requirement.id.sha256, requirement.id.byteCount)
            if (previouslySeen != null && previouslySeen != requirement.id.byteCount) throw IOException("Conflicting original lengths")
        }
        if (metadataReservation + uniqueSizes.values.sum() > capacity) throw IOException("Not enough space for library export")
        checkpoint()
        forceAncestry(parent, privateFiles, checkpoint)
        // The directory create, not the earlier absence check, wins ownership. A competing export must not adopt this folder.
        Files.createDirectory(target)
        val adapter = LocalLibraryDirectory(target.toFile())
        val transfer = LibraryObjectTransfer(adapter, checkpoint).prepare(requirements, metadataReservation, capacity)
        checkpoint()
        transfer.copyAndVerify()
        val published = LibraryCatalogPublication(adapter, scratch, checkpoint).publish(
            generation, MAX_PORTABLE_BYTES, { readRegular(closed) }, requiresObjects = requirements.isNotEmpty(),
        ) { verification ->
            reader.read(verification) { catalog ->
                verifyOriginalReferences(catalog, snapshot.originals, checkpoint)
                LibraryObjectTransfer(adapter, checkpoint).prepare(
                    requirements.distinctBy { it.id }.map { LibraryObjectTransfer.Requirement(it.id, null) }, 0L, 0L,
                )
            }
        }
        // Only this attempt's immutable, verified duplicate can go away. Cleanup failure must not hide a valid export.
        try {
            Files.deleteIfExists(closed.toPath())
            Files.deleteIfExists(stage.toPath())
        } catch (_: IOException) { /* Keep the owned stage for explicit maintenance, not an automatic export replay. */ }
        published
    }

    private fun verifyOriginalReferences(catalog: SQLiteDatabase, expected: List<SourceBlobRow>, checkpoint: () -> Unit) {
        if (LibraryPortableOriginals.read(catalog, checkpoint) != expected.associate { it.sourceBlobId to objectId(it) }) {
            throw IOException("Original references changed in the staged catalog")
        }
    }

    private fun readRegular(file: File): InputStream {
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) throw IOException("Library input is not a regular file")
        return Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS)
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
    private fun objectId(blob: SourceBlobRow) = LibraryObjectTransfer.ObjectId(blob.byteSha256, blob.byteCount)

    private companion object {
        val STAGE_ADMISSION = Any()
    }
}
