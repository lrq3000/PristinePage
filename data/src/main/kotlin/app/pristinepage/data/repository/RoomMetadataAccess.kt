package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.AtelierDraftDao
import java.util.concurrent.Callable

/** SQLite/Room owns rollback and writer serialization; this does not pretend that source-file moves are part of the same atomic transaction. */
class RoomMetadataAccess(private val database: PristineDatabase) : MetadataAccess {
    override val queries: CatalogQueries get() = database.catalog()
    override fun <T> transaction(action: () -> T): T = database.runInTransaction(Callable { action() })
    override fun isHiddenAtelierDraft(documentId: String): Boolean {
        val drafts = database.atelierDrafts()
        if (drafts.savedDocumentIsHidden(documentId) == true) return true
        val encoded = drafts.savedPromotionReceipt(documentId) ?: return false
        return try {
            val receipt = AtelierPublicationReceipt.decode(encoded)
            receipt.document.value != documentId || receipt.witnessed.map { it.page } != receipt.saved
        } catch (_: IllegalArgumentException) { true }
    }
    override fun retainedAtelierCopyAttempts(documentId: String): Long = database.atelierDrafts().retainedCopyAttempts(documentId)
    override fun hasAtelierCopyOwner(documentId: String): Boolean = database.atelierDrafts().hasCopyOwner(documentId)
    override fun retainedStagingCopyAttempts(limit: Int): Int = database.atelierDrafts().retainedStagingCopyAttempts(limit)
    override fun atelierEditingDocument(draftId: String, generation: Long): String? = database.atelierDrafts().editingDocument(draftId, generation)
    override fun ownsAtelierPublication(drafts: AtelierDraftDao): Boolean = database.atelierDrafts() === drafts
}
