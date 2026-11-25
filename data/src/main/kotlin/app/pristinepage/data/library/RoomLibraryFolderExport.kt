package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.requireOpaqueId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** A verified newly written backup, not permission to select or retire either library. */
data class LibraryFolderExportReceipt internal constructor(
    val operationId: String, val folderName: String, val libraryId: String, val generationId: String,
)

/** Export the default library only. Writes into a fresh child and never adopts or automatically retries an existing backup. */
internal class RoomLibraryFolderExport(
    context: Context, private val database: PristineDatabase, private val originalRoot: File,
) {
    private val app = context.applicationContext
    private val maxCatalogBytes = RoomLibraryCatalogReader.MAX_PORTABLE_BYTES

    suspend fun write(request: LibraryFolderExportRequest): LibraryFolderExportReceipt = locked {
            val tree = Uri.parse(request.selected.exactUri)
            val checkpoint = { coroutineContext.ensureActive() }
            val files = app.filesDir.canonicalFile.toPath()
            fun checkSelection() {
                checkpoint()
                requireSelection(files, request.issuedAt)
            }
            checkSelection()
            val attempt = attempt(files, request.operationId)
            if (!Files.notExists(attempt, NOFOLLOW_LINKS)) throw IOException("This backup operation cannot be repeated")
            val source = canonicalLibraryDirectory(originalRoot, files)
            val observed = AndroidLibraryWritableFolder(app).availableBytes(tree)
            val snapshot = RoomLibrarySavedCatalog(database).capture()
            if (files.toFile().usableSpace < 3 * maxCatalogBytes) throw IOException("Not enough private space to stage the library backup")
            Files.newDirectoryStream(files, "library-out-*").use {
                if (it.asSequence().take(4).count() >= 4) throw IOException("Retained library export attempts need attention")
            }
            checkSelection()
            Files.createDirectory(attempt)
            val closed = attempt.resolve("catalog.sqlite")
            val input = RoomLibraryExportInput.prepare(app, snapshot, source.toFile(), closed.toFile(), checkpoint)
            val generation = input.generation
            val folder = "PristinePage-backup-${generation.id}"
            val required = input.requiredSpace(scratchCopies = 1, maximumOriginalBytes = AndroidLibraryTreeRead.MAX_TREE_ORIGINAL_BYTES)
            if (observed < required || files.toFile().usableSpace < input.metadataBytes(scratchCopies = 1)) {
                throw IOException("Not enough space for the library backup")
            }
            val privateIdentities = listOf(files, source, attempt).mapTo(HashSet()) { directoryIdentity(it) }
            val spend = LocalLibraryMoveAuthorization(attempt.toFile())
            val journal = LocalLibraryFolderExportJournal(attempt.toFile(), checkpoint = checkpoint)
            lateinit var intent: LibraryFolderExportRecords.Intent
            lateinit var live: LocalLibraryFolderExportJournal.LiveCompletion
            AndroidLibraryWritableFolder(app).useTree(tree) { provider ->
                AndroidNewLibraryBackup.create(provider, folder, required, privateIdentities, spend, recordIntent = { parent ->
                    checkSelection()
                    intent = LibraryFolderExportRecords.Intent(
                        request.operationId, request.issuedAt, request.selected, parent.first, parent.second, generation,
                    )
                    live = journal.start(intent)
                    checkSelection()
                }) { target, capacity ->
                    val transfer = LibraryObjectTransfer(target, checkpoint)
                    transfer.prepare(input.requirements, input.metadataBytes(scratchCopies = 1), capacity).copyAndVerify()
                    LibraryCatalogPublication(target, attempt, checkpoint).publish(
                        generation, maxCatalogBytes, input::openCatalog, requiresObjects = input.requirements.isNotEmpty(),
                    ) { candidate ->
                        input.verifyCatalog(candidate)
                        transfer.prepare(input.requirements.map { LibraryObjectTransfer.Requirement(it.id, null) }, 0L, 0L)
                        checkSelection()
                    }
                    target.requireCompleteLayout()
                    checkSelection()
                }
            }
            // All provider readbacks, parent barriers and descriptor closes finished. The remaining private commit has no provider access.
            checkSelection()
            live.recordCompletion()
            checkSelection()
            intent.receipt()
    }

    suspend fun inspect(operationId: String, issuedAt: LibraryActivation): LibraryFolderExportState = locked {
        val files = app.filesDir.canonicalFile.toPath()
        requireSelection(files, issuedAt)
        val attempt = attempt(files, operationId)
        if (Files.notExists(attempt, NOFOLLOW_LINKS)) return@locked LibraryFolderExportState.NotStarted
        val journal = openJournal(files, attempt) { coroutineContext.ensureActive() }
        val observed = journal.reopen(operationId)
        if (observed != null && observed.intent.issuedAt != issuedAt) throw IOException("The backup belongs to another activation")
        requireSelection(files, issuedAt)
        when (observed?.phase) {
            null, LocalLibraryFolderExportJournal.Phase.INTENT_ONLY -> LibraryFolderExportState.NeedsVerification
            LocalLibraryFolderExportJournal.Phase.COMPLETED -> LibraryFolderExportState.Completed(observed.intent.receipt())
            LocalLibraryFolderExportJournal.Phase.ACKNOWLEDGED -> LibraryFolderExportState.Acknowledged(observed.intent.receipt())
        }
    }

    suspend fun acknowledge(receipt: LibraryFolderExportReceipt, issuedAt: LibraryActivation): Unit = locked {
        val files = app.filesDir.canonicalFile.toPath()
        requireSelection(files, issuedAt)
        val journal = openJournal(files, attempt(files, receipt.operationId)) { coroutineContext.ensureActive() }
        val observed = journal.reopen(receipt.operationId) ?: throw IOException("This backup has no retained receipt")
        if (observed.intent.issuedAt != issuedAt || observed.intent.receipt() != receipt) {
            throw IOException("The observed backup receipt does not match the requested acknowledgment")
        }
        requireSelection(files, issuedAt)
        journal.acknowledge(observed.intent)
        requireSelection(files, issuedAt)
    }

    private fun requireSelection(files: Path, issuedAt: LibraryActivation) {
        val authority = LocalLibrarySettingsBootstrap(files.toFile()).openExisting()
            ?: throw IOException("The default library selection is unavailable")
        val identity = (authority.read() as? LocalSelectedLibraryAuthority.Selected.Default)?.selection?.identity
        if (identity?.libraryId != issuedAt.libraryId || identity.activation != issuedAt.number) {
            throw IOException("The backup no longer belongs to the selected default library")
        }
    }

    private fun attempt(files: Path, operationId: String): Path {
        requireOpaqueId(operationId)
        return files.resolve("library-out-$operationId")
    }

    private fun openJournal(files: Path, attempt: Path, checkpoint: () -> Unit): LocalLibraryFolderExportJournal =
        LocalLibraryFolderExportJournal(canonicalLibraryDirectory(attempt.toFile(), files).toFile(), checkpoint = checkpoint)

    private fun LibraryFolderExportRecords.Intent.receipt() =
        LibraryFolderExportReceipt(operationId, backupName, generation.libraryId, generation.id)

    private suspend fun <T> locked(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO) {
        if (!exportLane.tryLock()) throw IOException("A default library export operation is already in progress")
        try { block() } finally { exportLane.unlock() }
    }

    private fun directoryIdentity(path: Path): Pair<Long, Long> = try {
        Os.stat(path.toString()).let { it.st_dev to it.st_ino }
    } catch (_: ErrnoException) { throw IOException("The private library directory could not be identified") }

    private companion object {
        val exportLane = Mutex()
    }
}
