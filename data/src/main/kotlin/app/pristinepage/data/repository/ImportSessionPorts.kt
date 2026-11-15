package app.pristinepage.data.repository

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
}
