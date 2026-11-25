package app.pristinepage.data.library

import android.content.Context
import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Private backup receipts do not need Room, originals or the file provider, even on cold startup. */
class AndroidLibraryBackupHistory(context: Context) {
    private val app = context.applicationContext

    suspend fun inspect(operationId: String, issuedAt: LibraryActivation): LibraryFolderExportState = withLane { files ->
        requireSelection(files, issuedAt)
        val attempt = attempt(files, operationId)
        if (Files.notExists(attempt, NOFOLLOW_LINKS)) {
            requireSelection(files, issuedAt)
            return@withLane LibraryFolderExportState.NotStarted
        }
        val journal = openJournal(files, attempt) { coroutineContext.ensureActive() }
        val observed = journal.reopen(operationId)
        if (observed != null && observed.intent.issuedAt != issuedAt) throw IOException("The backup belongs to another activation")
        requireSelection(files, issuedAt)
        when (observed?.phase) {
            null, LocalLibraryFolderExportJournal.Phase.INTENT_ONLY -> LibraryFolderExportState.NeedsVerification
            LocalLibraryFolderExportJournal.Phase.COMPLETED -> LibraryFolderExportState.Completed(observed.intent.backupReceipt())
            LocalLibraryFolderExportJournal.Phase.ACKNOWLEDGED -> LibraryFolderExportState.Acknowledged(observed.intent.backupReceipt())
        }
    }

    suspend fun acknowledge(receipt: LibraryFolderExportReceipt, issuedAt: LibraryActivation): Unit = withLane { files ->
        requireSelection(files, issuedAt)
        val journal = openJournal(files, attempt(files, receipt.operationId)) { coroutineContext.ensureActive() }
        val observed = journal.reopen(receipt.operationId) ?: throw IOException("This backup has no retained receipt")
        if (observed.intent.issuedAt != issuedAt || observed.intent.backupReceipt() != receipt) {
            throw IOException("The observed backup receipt does not match the requested acknowledgment")
        }
        requireSelection(files, issuedAt)
        journal.acknowledge(observed.intent)
        requireSelection(files, issuedAt)
    }

    internal fun requireSelection(files: Path, issuedAt: LibraryActivation) {
        val authority = LocalLibrarySettingsBootstrap(files.toFile()).openExisting()
            ?: throw IOException("The default library selection is unavailable")
        val identity = (authority.read() as? LocalSelectedLibraryAuthority.Selected.Default)?.selection?.identity
        if (identity?.libraryId != issuedAt.libraryId || identity.activation != issuedAt.number) {
            throw IOException("The backup no longer belongs to the selected default library")
        }
    }

    internal fun attempt(files: Path, operationId: String): Path {
        requireOpaqueId(operationId)
        return files.resolve("library-out-$operationId")
    }

    private fun openJournal(files: Path, attempt: Path, checkpoint: () -> Unit): LocalLibraryFolderExportJournal =
        LocalLibraryFolderExportJournal(canonicalLibraryDirectory(attempt.toFile(), files).toFile(), checkpoint = checkpoint)

    internal suspend fun <T> withLane(block: suspend CoroutineScope.(Path) -> T): T = withContext(Dispatchers.IO) {
        if (!exportLane.tryLock()) throw IOException("A default library export operation is already in progress")
        try {
            coroutineContext.ensureActive()
            block(app.filesDir.canonicalFile.toPath())
        } finally { exportLane.unlock() }
    }

    private companion object {
        val exportLane = Mutex()
    }
}

internal fun LibraryFolderExportRecords.Intent.backupReceipt() =
    LibraryFolderExportReceipt(operationId, backupName, generation.libraryId, generation.id)
