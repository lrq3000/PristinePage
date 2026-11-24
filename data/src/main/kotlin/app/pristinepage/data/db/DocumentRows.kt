package app.pristinepage.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Stored rows use opaque TEXT IDs and bounded metadata only. Immutable source bytes remain in the journaled private file store, never a database BLOB. */
@Entity(tableName = "Document", indices = [Index(value = ["lifecycle", "updatedAt"])])
data class DocumentRow(@PrimaryKey val documentId: String, val title: String, val documentRevision: Long, val lifecycle: String, val createdAt: Long, val updatedAt: Long, val deletedAt: Long?)

@Entity(tableName = "SourceBlob", indices = [Index(value = ["relativePath"], unique = true), Index(value = ["byteSha256", "byteCount"]), Index(value = ["state"])])
data class SourceBlobRow(@PrimaryKey val sourceBlobId: String, val byteSha256: String, val byteCount: Long, val mime: String, val encodedWidth: Int, val encodedHeight: Int, val relativePath: String, val state: String, val sourceCount: Long, val ingestTransactionId: String, val createdAt: Long)

@Entity(tableName = "Source", foreignKeys = [ForeignKey(entity = SourceBlobRow::class, parentColumns = ["sourceBlobId"], childColumns = ["sourceBlobId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["sourceBlobId", "conversionFingerprint"], unique = true), Index(value = ["pixelSha256"])])
data class SourceRow(@PrimaryKey val sourceId: String, val sourceBlobId: String, val pixelSha256: String, val width: Int, val height: Int, val exifOrientation: Int, val conversionFingerprint: String, val profileSha256: String?, val refCount: Long, val state: String, val createdAt: Long)

/** Only the current-recipe FK is deferred: the first Page and its revision must be inserted in one transaction. Null ordinals distinguish removed rows without conflicting with active-order uniqueness. */
@Entity(tableName = "Page", foreignKeys = [
    ForeignKey(entity = DocumentRow::class, parentColumns = ["documentId"], childColumns = ["documentId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = SourceRow::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = RecipeRevisionRow::class, parentColumns = ["pageId", "recipeRevision"], childColumns = ["pageId", "currentRecipeRevision"], deferred = true),
], indices = [Index(value = ["documentId", "state", "ordinal"]), Index(value = ["documentId", "ordinal"], unique = true), Index(value = ["sourceId"]), Index(value = ["pageId", "currentRecipeRevision"]), Index(value = ["pageId", "documentId"], unique = true)])
data class PageRow(@PrimaryKey val pageId: String, val documentId: String, val sourceId: String, val currentRecipeRevision: Long, val state: String, val ordinal: Int?, val removedAt: Long?, val createdAt: Long)

/** A logical filename belongs to one ordered scan slot. Its comparison key is supplied by the shared name allocator, never by a provider or SQLite's ASCII-only NOCASE. */
@Entity(tableName = "LibraryScanName", foreignKeys = [
    ForeignKey(entity = PageRow::class, parentColumns = ["pageId", "documentId"], childColumns = ["pageId", "documentId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["pageId", "documentId"]), Index(value = ["documentId", "comparisonKey"], unique = true)])
internal data class LibraryScanNameRow(@PrimaryKey val pageId: String, val documentId: String, val filename: String, val comparisonKey: String)

@Entity(tableName = "RecipeRevision", primaryKeys = ["pageId", "recipeRevision"], foreignKeys = [
    ForeignKey(entity = PageRow::class, parentColumns = ["pageId"], childColumns = ["pageId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = SourceRow::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = RecipeRevisionRow::class, parentColumns = ["pageId", "recipeRevision"], childColumns = ["pageId", "parentRevision"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["sourceId"]), Index(value = ["pageId", "parentRevision"])])
data class RecipeRevisionRow(val pageId: String, val recipeRevision: Long, val sourceId: String, val recipeSchemaVersion: Int, val canonicalRecipe: ByteArray, val recipeSha256: String, val parentRevision: Long?, val createdAt: Long)

@Entity(tableName = "ImportSession", foreignKeys = [
    ForeignKey(entity = ImportSessionRow::class, parentColumns = ["sessionId"], childColumns = ["parentSessionId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = DocumentRow::class, parentColumns = ["documentId"], childColumns = ["documentId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["parentSessionId", "selectionOrdinal"], unique = true), Index(value = ["plannedPageId"], unique = true), Index(value = ["transactionId"], unique = true), Index(value = ["documentId", "state"])])
data class ImportSessionRow(@PrimaryKey val sessionId: String, val parentSessionId: String?, val kind: String, val documentId: String, val anchorPageId: String?, val selectionOrdinal: Int?, val plannedPageId: String?, val uriText: String?, val state: String, val expectedMembershipToken: String, val transactionId: String?, val createdAt: Long, val expiresAt: Long, val errorCode: String?)

@Entity(tableName = "UndoEntry", foreignKeys = [ForeignKey(entity = DocumentRow::class, parentColumns = ["documentId"], childColumns = ["documentId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["documentId", "state", "expiresAt"])])
data class UndoEntryRow(@PrimaryKey val undoId: String, val documentId: String, val beforeState: ByteArray, val afterState: ByteArray, val action: String, val appliedDocumentRevision: Long, val expiresAt: Long, val state: String)

@Entity(tableName = "Confirmation", foreignKeys = [ForeignKey(entity = DocumentRow::class, parentColumns = ["documentId"], childColumns = ["documentId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["documentId"]), Index(value = ["expiresAt"])])
data class ConfirmationRow(@PrimaryKey val confirmationId: String, val documentId: String, val documentRevision: Long, val action: String, val pageCount: Int, val expiresAt: Long, val consumedAt: Long?)
