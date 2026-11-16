package app.pristinepage.data.repository

import android.net.Uri
import app.pristinepage.data.db.ImportSessionRow
import app.pristinepage.data.model.*

/** Short metadata-only preparation for picker/camera handoff. These queries must use the same Room database/writer transaction as MetadataAccess; they never open provider URIs or make a Page visible. */
interface ImportSessionQueries {
    fun session(id: String): ImportSessionRow?
    fun children(parentId: String): List<ImportSessionRow>
    fun activeBatches(documentId: String): List<ImportSessionRow>
    fun insertSession(row: ImportSessionRow)
    fun updateSession(row: ImportSessionRow): Int
}

interface ImportSessionCommands {
    suspend fun beginImport(documentId: DocumentId, expectedDocumentRevision: Long, anchorPageId: PageId?): ImportBatch
    suspend fun reserveImportItems(sessionId: ImportSessionId, count: Int): ImportBatch
    suspend fun bindImportItem(sessionId: ImportSessionId, ordinal: Int, uri: Uri): ImportSessionId
}

/** Pure private-metadata boundary for the Android Uri adapter. It records only a selected provider reference; successful binding is not read permission, a private byte copy or an accepted page. */
interface ImportItemBindings {
    suspend fun bindProviderItem(sessionId: ImportSessionId, ordinal: Int, uriText: String): ImportSessionId
}

/** Whole picker callback handoff. Private provider references are copied/validated and all child IDs/bindings are committed together before any provider IO. Replaying the same ordered selection returns the same children even after terminal URI removal; a changed selection requires a new batch. Empty selection cancels an unselected batch, never creates a page. */
fun interface ImportSelections {
    suspend fun prepareSelection(sessionId: ImportSessionId, references: List<String>): ImportBatch
}

/** Explicitly abandon unresolved insertion, not remove a page/source or promise provider IO already stopped. Terminal children and all files/journals/leases/pins survive; parent cancellation fences late completion. */
fun interface ImportSessionLifecycle {
    suspend fun cancelBatch(sessionId: ImportSessionId): ImportBatch
}
