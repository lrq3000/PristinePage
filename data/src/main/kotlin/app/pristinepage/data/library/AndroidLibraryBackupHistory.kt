package app.pristinepage.data.library

import android.content.Context
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Private backup receipts do not need Room, originals or the file provider, even on cold startup. */
class AndroidLibraryBackupHistory(context: Context) {
    private val app = context.applicationContext

    /** Read-only admission before taking a new persistent grant. The writer still has to acquire the lane and recheck. */
    suspend fun preflightNew(operationId: String, issuedAt: LibraryActivation) = withLane { files ->
        requireSelection(files, issuedAt)
        LocalLibraryBackupAttempts(files).requireNew(operationId)
        requireSelection(files, issuedAt)
    }

    suspend fun inspect(operationId: String, issuedAt: LibraryActivation): LibraryFolderExportState = withLane { files ->
        requireSelection(files, issuedAt)
        val attempt = LocalLibraryBackupAttempts(files) { coroutineContext.ensureActive() }.find(operationId)
        if (attempt == null) {
            requireSelection(files, issuedAt)
            return@withLane LibraryFolderExportState.NotStarted
        }
        val journal = openJournal(files, attempt) { coroutineContext.ensureActive() }
        val observed = journal.reopen(operationId)
        if (observed != null && observed.intent.issuedAt != issuedAt) throw IOException("The backup belongs to another activation")
        requireSelection(files, issuedAt)
        observed.backupState()
    }

    /** Enumerate at most the ordinary active quota. An unresolved row is not confirmation of a finished provider write. */
    suspend fun discoverActive(issuedAt: LibraryActivation): LibraryBackupDiscovery = withLane { files ->
        val origin = historyOrigin(files, issuedAt)
        val active = LocalLibraryBackupAttempts(files) { coroutineContext.ensureActive() }.discoverActive(issuedAt, origin)
        if (historyOrigin(files, issuedAt) != origin) throw IOException("The default library backup history changed")
        active
    }

    suspend fun acknowledge(receipt: LibraryFolderExportReceipt, issuedAt: LibraryActivation): Unit = withLane { files ->
        val origin = historyOrigin(files, issuedAt)
        val attempt = LocalLibraryBackupAttempts(files) { coroutineContext.ensureActive() }.find(receipt.operationId)
            ?: throw IOException("This backup has no retained receipt")
        val journal = openJournal(files, attempt) { coroutineContext.ensureActive() }
        val observed = journal.reopen(receipt.operationId) ?: throw IOException("This backup has no retained receipt")
        if (!backupReceiptOwner(issuedAt, origin)(observed.intent.issuedAt) || observed.intent.backupReceipt() != receipt) {
            throw IOException("The observed backup receipt does not match the requested acknowledgment")
        }
        if (historyOrigin(files, issuedAt) != origin) throw IOException("The default library backup history changed")
        journal.acknowledge(observed.intent)
        if (historyOrigin(files, issuedAt) != origin) throw IOException("The default library backup history changed")
    }

    /** Explicit private housekeeping after acknowledgment; leaves the external folder and all replay evidence untouched. */
    suspend fun archiveAcknowledged(operationId: String, issuedAt: LibraryActivation): Boolean = withLane { files ->
        val origin = historyOrigin(files, issuedAt)
        val archived = LocalLibraryBackupAttempts(files) { coroutineContext.ensureActive() }.archiveAcknowledged(operationId, issuedAt, origin)
        if (historyOrigin(files, issuedAt) != origin) throw IOException("The default library backup history changed")
        archived
    }

    private fun historyOrigin(files: Path, issuedAt: LibraryActivation): LibraryActivation? =
        withDefaultSelection(files, issuedAt) { authority, selected -> authority.verifiedDefaultOrigin(selected) }

    internal fun requireSelection(files: Path, issuedAt: LibraryActivation) =
        withDefaultSelection(files, issuedAt) { _, _ -> }

    private fun <T> withDefaultSelection(files: Path, issuedAt: LibraryActivation,
        read: (LocalSelectedLibraryAuthority, LocalSelectedLibraryAuthority.Selected.Default) -> T,
    ): T {
        val authority = LocalLibrarySettingsBootstrap(files.toFile()).openExisting()
            ?: throw IOException("The default library selection is unavailable")
        val selected = authority.read() as? LocalSelectedLibraryAuthority.Selected.Default
            ?: throw IOException("The default library selection is unavailable")
        val identity = selected.selection.identity
        if (identity.libraryId != issuedAt.libraryId || identity.activation != issuedAt.number) {
            throw IOException("The backup no longer belongs to the selected default library")
        }
        return read(authority, selected)
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
