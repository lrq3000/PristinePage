package app.pristinepage.data.model

import java.util.UUID
import kotlinx.serialization.Serializable

/** Canonical UUID TEXT is identity only, never a title, path, source URI, timestamp or position. Random allocation belongs to the repository command, validation to every wrapped boundary. */
internal fun requireOpaqueId(value: String) { require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "Expected canonical opaque UUID" } }

@Serializable @JvmInline value class DocumentId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class PageId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class SourceId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class SourceBlobId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class ImportSessionId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class ExportSnapshotId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class JobId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class RenderId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class TransactionId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class ArtifactId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class PublicationId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class ShareId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class UndoId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class MaskId(val value: String) { init { requireOpaqueId(value) } }
@Serializable @JvmInline value class ConfirmationId(val value: String) { init { requireOpaqueId(value) } }
