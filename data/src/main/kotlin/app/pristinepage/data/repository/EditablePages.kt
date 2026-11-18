package app.pristinepage.data.repository

import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.PageId

interface EditablePages {
    suspend fun load(documentId: DocumentId, pageId: PageId): EditablePage
}

/** Reuse existing raw document/active-membership/source guards, then bound every recipe row field before BLOB allocation or generated Int narrowing. Guards/projections belong to one metadata transaction, not a stale precheck. */
interface EditablePageQueries : DocumentReadQueries, CommittedSourceQueries {
    /** Freeze diagnostic after a false joined source guard. Null means Source absent; true only for a canonical link to an absent exact Blob key. */
    fun sourceBlobLinkIsMissing(id: String): Boolean?
    fun pageReadIsBounded(pageId: String): Boolean?
    fun recipeReadIsBounded(pageId: String, revision: Long): Boolean?
    /** Raw BLOB length by exact revision for freeze's aggregate preflight, not a replacement for the full row guard. Null means absent; -1 means wrong storage class. */
    fun recipeBlobByteCount(pageId: String, revision: Long): Long?
    fun recipeHasMasks(pageId: String, revision: Long): Boolean
}
