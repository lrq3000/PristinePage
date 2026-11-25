package app.pristinepage.data.library

import app.pristinepage.data.repository.PrivateImportStore

/** Debug-only entry to the ordinary writer's verified pre-marker boundary. This is not a replay or provider grant. */
object LibraryBackupInterruptionWitness {
    suspend fun stopBeforeMarker(store: PrivateImportStore, request: LibraryFolderExportRequest,
        stop: (library: String, generation: String) -> Nothing,
    ): Nothing {
        store.exportLibraryTo(request, stop)
        error("The backup did not stop at its verified pre-marker boundary")
    }
}
