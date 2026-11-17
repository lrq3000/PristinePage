package app.pristinepage.data.repository

import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.PageId

interface EditablePages {
    suspend fun load(documentId: DocumentId, pageId: PageId): EditablePage
}

/** Reuse existing raw document/active-membership/source guards, then bound every recipe row field before BLOB allocation or generated Int narrowing. Guards/projections belong to one metadata transaction, not a stale precheck. */
interface EditablePageQueries : DocumentReadQueries, CommittedSourceQueries {
    fun pageReadIsBounded(pageId: String): Boolean?
    fun recipeReadIsBounded(pageId: String, revision: Long): Boolean?
    fun recipeHasMasks(pageId: String, revision: Long): Boolean
}
