package app.pristinepage.data.model

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import kotlinx.serialization.Serializable

@Serializable enum class OperationErrorCode { INVALID_INPUT, UNSUPPORTED_FORMAT, UNSUPPORTED_VERSION, PERMISSION_LOST, SOURCE_UNAVAILABLE, SESSION_EXPIRED, REVISION_CONFLICT, PROVIDER_UNAVAILABLE, INVALID_MAP, PROCESSING_FAILED, RESOURCE_LIMIT, STORAGE_FAILED, CANCELED, INTEGRITY_FAILED }

/** Safe keys and opaque IDs only. Raw provider/Room exception messages, titles, paths and URIs never enter a serializable error payload. */
@Serializable data class OperationError(val code: OperationErrorCode, val retryable: Boolean, val safeMessageKey: String, val affectedId: String?) {
    init { require(safeMessageKey.matches(Regex("[a-z][a-z0-9_]{0,127}"))); affectedId?.let(::requireOpaqueId) }
}
open class RepositoryFailure(val error: OperationError) : RuntimeException(error.safeMessageKey)

/** Decoder-produced normalized identity, not a borrowed image lease or an estimate computed from a smaller preview. Metadata ceilings do not grant memory/decode admission. */
@Serializable data class SourceInspection(val mime: String, val encodedSize: PixelSize, val normalizedSize: PixelSize, val exifOrientation: Int, val profileSha256: String?, val conversionFingerprint: String, val sourcePixelHash: String) {
    init {
        require(mime in setOf("image/jpeg", "image/png", "image/webp") && exifOrientation in 1..8)
        for (size in listOf(encodedSize, normalizedSize)) require(size.pixelCount <= 48_000_000 && maxOf(size.width, size.height) <= 12000)
        val expected = if (exifOrientation in 5..8) PixelSize(encodedSize.height, encodedSize.width) else encodedSize
        require(normalizedSize == expected) { "Inspection orientation/dimension mismatch" }
        require(conversionFingerprint.isNotBlank() && conversionFingerprint.length <= 128)
        requireWellFormedUnicode(conversionFingerprint); requireSha256(sourcePixelHash); profileSha256?.let(::requireSha256)
    }
}

@Serializable enum class FileTransactionState { INTENT, COPYING, SEALED, MOVED, COMMITTED, CANCELED, RETRYABLE, QUARANTINED, FAILED }
@Serializable data class ImportBatch(val parentId: ImportSessionId, val childIds: List<ImportSessionId>)

@Serializable sealed class IngestResult {
    @Serializable data class Committed(val sessionId: ImportSessionId, val documentId: DocumentId, val pageId: PageId, val sourceId: SourceId, val sourceByteHash: String, val sourcePixelHash: String, val documentRevision: Long) : IngestResult() {
        init { requireSha256(sourceByteHash); requireSha256(sourcePixelHash); require(documentRevision >= 1) }
    }
    @Serializable data class Rejected(val sessionId: ImportSessionId, val error: OperationError) : IngestResult()
    @Serializable data class Pending(val sessionId: ImportSessionId, val recoveryState: FileTransactionState) : IngestResult()
}

@Serializable sealed class SaveRecipeResult {
    @Serializable data class Saved(val pageId: PageId, val recipeRevision: Long, val documentRevision: Long, val recipeHash: String) : SaveRecipeResult() {
        init { require(recipeRevision >= 1 && documentRevision >= 1); requireSha256(recipeHash) }
    }
    @Serializable data class Conflict(val actualRevision: Long) : SaveRecipeResult()
    @Serializable data class Rejected(val error: OperationError) : SaveRecipeResult()
}

@Serializable sealed class DocumentMutationResult {
    @Serializable data class Applied(val documentRevision: Long, val affectedPageIds: List<PageId>, val undoId: UndoId?) : DocumentMutationResult()
    @Serializable data class Conflict(val actualRevision: Long) : DocumentMutationResult()
    @Serializable data class Rejected(val error: OperationError) : DocumentMutationResult()
}

data class BatchRecipeChange(val pageId: PageId, val expectedRecipeRevision: Long, val recipe: Recipe)
@Serializable enum class RecoveryAction { RETRY, RESELECT, REVIEW_INTEGRITY, REOPEN, NONE }
@Serializable data class RecoveryIssue(val operationId: String, val state: String, val error: OperationError?, val action: RecoveryAction) { init { requireOpaqueId(operationId); require(state.matches(Regex("[A-Z_]{1,64}"))) } }
@Serializable data class RecoveryReport(val inspected: Long, val completed: Long, val resumed: Long, val quarantined: Long, val blocked: Long, val safeIssues: List<RecoveryIssue>)
