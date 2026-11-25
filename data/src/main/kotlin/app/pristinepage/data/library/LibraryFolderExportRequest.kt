package app.pristinepage.data.library

import android.net.Uri
import java.io.IOException

/** Binds one explicit app operation to its actual Files result. Current persisted access must still be checked at every provider boundary. */
class LibraryFolderExportRequest private constructor(
    val issuedAt: LibraryActivation, internal val selected: LibraryFolderGrantRecord,
) {
    val operationId: String get() = selected.grantId

    companion object {
        /** Call only for the exact result of the existing confirmed picker request, never to reconstruct write authority from private records. */
        fun bind(operationId: String, issuedAt: LibraryActivation, exactTree: Uri, returnedModes: Int): LibraryFolderExportRequest {
            if (returnedModes != 3 || AndroidLibraryTreeRead.localTreeDocumentId(exactTree) == null) {
                throw IOException("The selected backup folder did not return writable local access")
            }
            val record = try { LibraryFolderGrantRecord(operationId, exactTree.toString(), returnedModes) }
            catch (_: IllegalArgumentException) { throw IOException("The backup selection could not be bound") }
            return LibraryFolderExportRequest(issuedAt, record)
        }
    }
}

/** Historical private record. Completed does not claim that the user can still access the external folder. */
sealed interface LibraryFolderExportState {
    data object NotStarted : LibraryFolderExportState
    data object NeedsVerification : LibraryFolderExportState
    sealed interface Confirmed : LibraryFolderExportState { val receipt: LibraryFolderExportReceipt }
    data class Completed(override val receipt: LibraryFolderExportReceipt) : Confirmed
    data class Acknowledged(override val receipt: LibraryFolderExportReceipt) : Confirmed
}

/** Only owned, verified terminal receipts have IDs. Unresolved entries provide a count, never an action or a path. */
data class LibraryBackupDiscovery internal constructor(val receipts: List<LibraryFolderExportState.Confirmed>, val unresolvedCount: Int)
