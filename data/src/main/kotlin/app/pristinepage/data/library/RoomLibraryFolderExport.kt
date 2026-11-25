package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import app.pristinepage.data.db.PristineDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.ensureActive

/** A verified newly written backup, not permission to select or retire either library. */
data class LibraryFolderExportReceipt internal constructor(
    val operationId: String, val folderName: String, val libraryId: String, val generationId: String,
)

/** Export the default library only. Writes into a fresh child and never adopts or automatically retries an existing backup. */
internal class RoomLibraryFolderExport(
    context: Context, private val database: PristineDatabase, private val originalRoot: File,
) {
    private val app = context.applicationContext
    private val history = AndroidLibraryBackupHistory(app)
    private val maxCatalogBytes = RoomLibraryCatalogReader.MAX_PORTABLE_BYTES

    suspend fun write(request: LibraryFolderExportRequest): LibraryFolderExportReceipt = history.withLane { files ->
            val tree = Uri.parse(request.selected.exactUri)
            val checkpoint = { coroutineContext.ensureActive() }
            fun checkSelection() {
                checkpoint()
                history.requireSelection(files, request.issuedAt)
            }
            checkSelection()
            val attempts = LocalLibraryBackupAttempts(files, checkpoint = checkpoint)
            val attempt = attempts.requireNew(request.operationId)
            val source = canonicalLibraryDirectory(originalRoot, files)
            val observed = AndroidLibraryWritableFolder(app).availableBytes(tree)
            val snapshot = RoomLibrarySavedCatalog(database).capture()
            if (files.toFile().usableSpace < 3 * maxCatalogBytes) throw IOException("Not enough private space to stage the library backup")
            checkSelection()
            if (attempts.requireNew(request.operationId) != attempt) throw IOException("The private backup attempt changed")
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
            intent.backupReceipt()
    }

    private fun directoryIdentity(path: Path): Pair<Long, Long> = try {
        Os.stat(path.toString()).let { it.st_dev to it.st_ino }
    } catch (_: ErrnoException) { throw IOException("The private library directory could not be identified") }

}
