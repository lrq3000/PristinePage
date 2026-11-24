package app.pristinepage.data.library

import android.content.Context
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.files.SourceFiles
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Isolated app-private restore to an absent root. Both roots must stay under cooperative ownership throughout; this does not change the active library. */
internal class RoomLibraryLocalRestore(context: Context, private val scratchDirectory: File) {
    private val application = context.applicationContext
    private val reader = RoomLibraryCatalogReader(application, RoomLibraryCatalogReader.MAX_PORTABLE_BYTES)

    /** Binds the exact verified destination, which can have a different SQLite byte layout from the backup. */
    data class Completed(val backup: LibraryGeneration, val catalog: LibraryObjectTransfer.ObjectId) {
        fun encodeReceipt(): ByteArray =
            ("pristinepage-private-restore-v1\n${backup.libraryId}\n${backup.id}\n${backup.catalog.sha256}\n" +
                "${catalog.byteCount}\n${catalog.sha256}\n").toByteArray(Charsets.US_ASCII)
    }

    suspend fun restore(backup: File, destination: File): Completed = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        val privateFiles = application.filesDir.toPath().toRealPath()
        val backupRoot = canonicalLibraryDirectory(backup, privateFiles)
        val scratch = canonicalLibraryDirectory(scratchDirectory, privateFiles)
        val parent = canonicalLibraryDirectory(checkNotNull(destination.absoluteFile.parentFile), privateFiles)
        val target = parent.resolve(destination.name)
        if (target != destination.absoluteFile.toPath() || target.startsWith(backupRoot) || backupRoot.startsWith(target) ||
            target.startsWith(scratch) || scratch.startsWith(target) || !Files.notExists(target, NOFOLLOW_LINKS)
        ) throw IOException("Restore requires a previously absent independent private location")
        val source = LocalLibraryDirectory(backupRoot.toFile())
        val selected = RoomLibraryLocalBackup(application, scratchDirectory).project(source, scratch, checkpoint) { catalog ->
            val snapshot = LibraryPortableCatalogProjection(catalog, checkpoint).snapshot()
            LibraryBackupRead.Projection(snapshot, identities(snapshot).values)
        }
        val byBlob = identities(selected.value)
        val byObject = byBlob.entries.groupBy({ it.value }, { it.key })
        val filesystemReservation = 16L * 1024 * (byBlob.size + 8L)
        val stageReservation = 3 * RoomLibraryCatalogReader.MAX_PORTABLE_BYTES + filesystemReservation
        val required = stageReservation + byObject.keys.sumOf { it.byteCount }
        if (parent.toFile().usableSpace < required) throw IOException("Not enough space for the restored library")
        checkpoint()
        forceAncestry(parent, privateFiles)
        Files.createDirectory(target)
        force(parent)
        val stage = target.resolve("catalog.pending.sqlite")
        RoomLibraryCatalogStage(application).write(selected.value, stage.toFile())
        verifyCatalog(stage, selected.value, checkpoint)

        val restored = LocalLibraryDirectory(target.toFile())
        val store = object : LibraryObjectTransfer.Store {
            override fun open(id: LibraryObjectTransfer.ObjectId) = restored.openPrivateOriginal(byObject.getValue(id).first())
            override fun create(id: LibraryObjectTransfer.ObjectId, write: (OutputStream) -> Unit) =
                restored.createPrivateOriginal(byObject.getValue(id).first(), write)
        }
        val transfer = LibraryObjectTransfer(store, checkpoint).prepare(
            byObject.keys.map { id -> LibraryObjectTransfer.Requirement(id) { source.open(id) ?: throw IOException("Backup original disappeared") } },
            filesystemReservation, parent.toFile().usableSpace,
        )
        transfer.copyAndVerify()
        val files = SourceFiles(target.toFile())
        for ((_, blobIds) in byObject) for (duplicate in blobIds.drop(1)) {
            checkpoint()
            restored.linkPrivateOriginal(blobIds.first(), duplicate)
        }
        for ((blob, id) in byBlob) files.verifyOriginal(blob, id.byteCount, id.sha256, checkpoint)
        if (source.readTip(checkpoint) != selected.generation) throw IOException("Backup changed during restore")
        source.openCatalog(selected.generation)?.use { selected.generation.verifyCatalog(it, checkpoint) }
            ?: throw IOException("Backup catalog disappeared")

        // The catalog name alone is not readiness. The completion receipt follows a final reopened readback.
        FileChannel.open(stage, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        checkpoint()
        val catalog = target.resolve("catalog.sqlite")
        Files.createLink(catalog, stage)
        force(target)
        verifyCatalog(catalog, selected.value, checkpoint)
        val completed = Completed(selected.generation, fingerprintClosedLibraryCatalog(catalog.toFile(), checkpoint))
        checkpoint()
        restored.publishRestoreReceipt(completed.encodeReceipt())
        try { Files.deleteIfExists(stage) } catch (_: IOException) { /* Retain only the attempt-owned duplicate on cleanup refusal. */ }
        completed
    }

    private fun identities(snapshot: RoomLibrarySavedCatalog.Snapshot) =
        snapshot.originals.associate { it.sourceBlobId to LibraryObjectTransfer.ObjectId(it.byteSha256, it.byteCount) }

    private fun verifyCatalog(file: Path, expected: RoomLibrarySavedCatalog.Snapshot, checkpoint: () -> Unit) {
        reader.read(file.toFile()) { catalog ->
            val actual = LibraryPortableCatalogProjection(catalog, checkpoint).snapshot()
            if (actual.originals.toSet() != expected.originals.toSet() || actual.sources.toSet() != expected.sources.toSet() ||
                actual.days.toSet() != expected.days.toSet() || actual.albums.map { it.row } != expected.albums.map { it.row }
            ) throw IOException("Restored library metadata changed")
            for ((album, other) in actual.albums.zip(expected.albums)) {
                if (album.pages.size != other.pages.size) throw IOException("Restored library page count changed")
                for ((page, prior) in album.pages.zip(other.pages)) {
                    checkpoint()
                    if (page.row != prior.row || page.filename != prior.filename || page.revisionCount != prior.revisionCount ||
                        page.revisions.zip(prior.revisions).any { (a, b) ->
                            a.copy(canonicalRecipe = b.canonicalRecipe) != b || !a.canonicalRecipe.contentEquals(b.canonicalRecipe)
                        } || !sameOcr(page.savedOcr, prior.savedOcr)
                    ) throw IOException("Restored library page history changed")
                }
            }
        }
    }

    private fun sameOcr(a: LibrarySavedOcr?, b: LibrarySavedOcr?) = if (a == null || b == null) a === b else
        a.producerId == b.producerId && a.intent == b.intent && a.text.value == b.text.value &&
            a.attempt == b.attempt && a.createdAt == b.createdAt && a.updatedAt == b.updatedAt

    private fun forceAncestry(parent: Path, privateFiles: Path) {
        var path = parent
        while (path != privateFiles) {
            path = checkNotNull(path.parent)
            force(path)
        }
    }

    private fun force(directory: Path) {
        if (!PlatformDirectorySync.sync(directory.toFile())) throw IOException("Restored library directory durability is unavailable")
    }
}
