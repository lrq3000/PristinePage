package app.pristinepage.data.repository

import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/** One private-store lifetime, not the rooted file lane or a requester registry. Closing never waits for an active export. */
internal class OrdinaryExportLifetime(private val closeDatabase: () -> Unit) {
    private val monitor = Any()
    private var active = 0
    private var closeClaimed = false

    suspend fun <T> retain(affectedId: String, block: suspend () -> T): T {
        synchronized(monitor) {
            if (closeClaimed) throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, false, "ordinary_export_store_closed", affectedId))
            active++
        }
        try {
            return coroutineScope { ensureActive(); block() }
        } finally {
            synchronized(monitor) { active-- }
        }
    }

    fun close() {
        synchronized(monitor) {
            if (closeClaimed) return
            if (active != 0) throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, true, "ordinary_export_store_in_use", null))
            closeClaimed = true
        }
        // Room may perform I/O. Keep the claim even if close throws: partial close is not permission to reopen admission.
        closeDatabase()
    }
}
