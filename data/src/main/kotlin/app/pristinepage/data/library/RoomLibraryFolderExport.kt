package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import app.pristinepage.core.io.transferExactBytes
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.files.PlatformDirectorySync
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** A verified newly written backup, not permission to select or retire either library. */
data class LibraryFolderExportReceipt internal constructor(val folderName: String, val libraryId: String, val generationId: String)

/** Export the default library only. Writes into a fresh child and never adopts or automatically retries an existing backup. */
internal class RoomLibraryFolderExport(
    context: Context, private val database: PristineDatabase, private val originalRoot: File,
) {
    private val app = context.applicationContext
    private val maxCatalogBytes = RoomLibraryCatalogReader.MAX_PORTABLE_BYTES

    suspend fun write(tree: Uri): LibraryFolderExportReceipt = withContext(Dispatchers.IO) {
        if (!exportLane.tryLock()) throw IOException("A default library export is already in progress")
        try {
            val checkpoint = { coroutineContext.ensureActive() }
            val files = app.filesDir.canonicalFile.toPath()
            val source = canonicalLibraryDirectory(originalRoot, files)
            val authority = LocalLibrarySettingsBootstrap(files.toFile()).openExisting()
                ?: throw IOException("The default library selection is unavailable")
            val expected = authority.read() as? LocalSelectedLibraryAuthority.Selected.Default
                ?: throw IOException("The default library is no longer selected")
            fun checkSelection() {
                checkpoint()
                if (authority.read() != expected) throw IOException("The default library selection changed")
            }

            val observed = AndroidLibraryWritableFolder(app).availableBytes(tree)
            val snapshot = RoomLibrarySavedCatalog(database).capture()
            if (files.toFile().usableSpace < 3 * maxCatalogBytes) throw IOException("Not enough private space to stage the library backup")
            Files.newDirectoryStream(files, "library-out-*").use {
                if (it.asSequence().take(4).count() >= 4) throw IOException("Retained library export attempts need attention")
            }
            checkSelection()
            val attempt = Files.createTempDirectory(files, "library-out-")
            val closed = attempt.resolve("catalog.sqlite")
            val input = RoomLibraryExportInput.prepare(app, snapshot, source.toFile(), closed.toFile(), checkpoint)
            val generation = input.generation
            val folder = "PristinePage-backup-${generation.id}"
            val required = input.requiredSpace(scratchCopies = 1, maximumOriginalBytes = AndroidLibraryTreeRead.MAX_TREE_ORIGINAL_BYTES)
            if (observed < required || files.toFile().usableSpace < input.metadataBytes(scratchCopies = 1)) {
                throw IOException("Not enough space for the library backup")
            }
            val privateIdentities = listOf(files, source, attempt).mapTo(HashSet()) { directoryIdentity(it) }
            val journal = LocalLibraryMoveAuthorization(attempt.toFile())
            val published = AndroidLibraryWritableFolder(app).useTree(tree) { provider ->
                AndroidNewLibraryBackup.create(provider, folder, required, privateIdentities, journal, recordIntent = { parent ->
                    checkSelection()
                    persistIntent(attempt, files, closed, generation, tree, parent, folder, expected.selection.identity, checkpoint)
                    checkSelection()
                }) { target, capacity ->
                    val transfer = LibraryObjectTransfer(target, checkpoint)
                    transfer.prepare(input.requirements, input.metadataBytes(scratchCopies = 1), capacity).copyAndVerify()
                    val verified = LibraryCatalogPublication(target, attempt, checkpoint).publish(
                        generation, maxCatalogBytes, input::openCatalog, requiresObjects = input.requirements.isNotEmpty(),
                    ) { candidate ->
                        input.verifyCatalog(candidate)
                        transfer.prepare(input.requirements.map { LibraryObjectTransfer.Requirement(it.id, null) }, 0L, 0L)
                        checkSelection()
                    }
                    target.requireCompleteLayout()
                    checkSelection()
                    val receipt = "pristinepage-library-export-complete-v1\n$folder\n".toByteArray(Charsets.US_ASCII) + verified.encode()
                    persistRecord(attempt.resolve("export.complete"), receipt, checkpoint)
                    verified
                }
            }
            // Delivery across a coroutine boundary can still be canceled. Keep the exact private intent and completion; the UI must eventually acknowledge a known result instead of replaying it.
            LibraryFolderExportReceipt(folder, published.libraryId, published.id)
        } finally { exportLane.unlock() }
    }

    private fun persistIntent(
        attempt: Path, files: Path, catalog: Path, generation: LibraryGeneration, tree: Uri,
        parent: Pair<Long, Long>, folder: String, identity: ActiveLibraryFence.Identity, checkpoint: () -> Unit,
    ) {
        val body = "pristinepage-library-export-v1\n${identity.libraryId}\n${identity.activation}\n$tree\n$parent\n$folder\n".toByteArray(Charsets.UTF_8) +
            generation.encode()
        Files.newInputStream(catalog, READ, NOFOLLOW_LINKS).use { generation.verifyCatalog(it, checkpoint) }
        FileChannel.open(catalog, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        force(files)
        force(checkNotNull(files.parent))
        persistRecord(attempt.resolve("export.intent"), body, checkpoint)
    }

    private fun persistRecord(file: Path, body: ByteArray, checkpoint: () -> Unit) {
        force(checkNotNull(file.parent))
        checkpoint()
        Files.newOutputStream(file, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.write(body) }
        FileChannel.open(file, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        force(checkNotNull(file.parent))
        Files.newInputStream(file, READ, NOFOLLOW_LINKS).use {
            transferExactBytes(it, null, body.size.toLong(), backupSha256(body), checkpoint)
        }
    }

    private fun force(path: Path) {
        if (!PlatformDirectorySync.sync(path.toFile())) throw IOException("Private export journal durability is unavailable")
    }

    private fun directoryIdentity(path: Path): Pair<Long, Long> = try {
        Os.stat(path.toString()).let { it.st_dev to it.st_ino }
    } catch (_: ErrnoException) { throw IOException("The private library directory could not be identified") }

    private companion object {
        val exportLane = Mutex()
    }
}
