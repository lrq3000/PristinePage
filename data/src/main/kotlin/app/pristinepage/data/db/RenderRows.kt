package app.pristinepage.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ExportSnapshot", foreignKeys = [ForeignKey(entity = DocumentRow::class, parentColumns = ["documentId"], childColumns = ["documentId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["documentId"]), Index(value = ["snapshotSha256"]), Index(value = ["state", "createdAt"])])
data class ExportSnapshotRow(@PrimaryKey val snapshotId: String, val documentId: String, val documentRevision: Long, val snapshotSha256: String, val settings: ByteArray, val state: String, val createdAt: Long, val retainedUntil: Long)

/** originalPageId is historical, deliberately not an FK to a mutable/purgeable Page. Copied immutable recipe/source/output identities survive later album edits. */
@Entity(tableName = "ExportPage", primaryKeys = ["snapshotId", "ordinal"], foreignKeys = [
    ForeignKey(entity = ExportSnapshotRow::class, parentColumns = ["snapshotId"], childColumns = ["snapshotId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = SourceRow::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = SourceBlobRow::class, parentColumns = ["sourceBlobId"], childColumns = ["sourceBlobId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = RenderedPageRow::class, parentColumns = ["renderId"], childColumns = ["acceptedRenderId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["sourceId"]), Index(value = ["sourceBlobId"]), Index(value = ["acceptedRenderId"]), Index(value = ["snapshotId", "originalPageId"], unique = true)])
data class ExportPageRow(val snapshotId: String, val ordinal: Int, val originalPageId: String, val sourceId: String, val sourceBlobId: String, val byteSha256: String, val pixelSha256: String, val width: Int, val height: Int, val conversionFingerprint: String, val recipeRevision: Long, val recipeSchemaVersion: Int, val recipe: ByteArray, val recipeSha256: String, val masks: ByteArray, val providers: ByteArray, val assetSetSha256: String, val rendererVersion: String, val requestedOutputWidth: Int, val requestedOutputHeight: Int, val outputWidth: Int, val outputHeight: Int, val fallbackPolicy: String, val acceptedRenderId: String?, val acceptedExecutionSha256: String?)

@Entity(tableName = "Job", foreignKeys = [ForeignKey(entity = ExportSnapshotRow::class, parentColumns = ["snapshotId"], childColumns = ["snapshotId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["idempotenceKey"], unique = true), Index(value = ["state", "leaseUntil"]), Index(value = ["snapshotId", "pageOrdinal"])])
data class JobRow(@PrimaryKey val jobId: String, val kind: String, val idempotenceKey: String, val canonicalInputSha256: String, val snapshotId: String?, val pageOrdinal: Int?, val input: ByteArray, val state: String, val attempt: Long, val leaseOwner: String?, val leaseUntil: Long?, val leaseGeneration: Long, val checkpoint: ByteArray?, val errorCode: String?, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "RenderedPage", foreignKeys = [
    ForeignKey(entity = JobRow::class, parentColumns = ["jobId"], childColumns = ["jobId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = SourceRow::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["jobId"]), Index(value = ["sourceId"]), Index(value = ["cacheKey"], unique = true), Index(value = ["relativePath"], unique = true)])
data class RenderedPageRow(@PrimaryKey val renderId: String, val jobId: String, val sourceId: String, val pageId: String, val recipeRevision: Long, val cacheKey: String, val execution: ByteArray, val executionSha256: String, val relativePath: String, val byteSha256: String, val rasterPixelSha256: String, val byteCount: Long, val width: Int, val height: Int, val renderStatus: String, val state: String)

@Entity(tableName = "ExportArtifact", foreignKeys = [ForeignKey(entity = ExportSnapshotRow::class, parentColumns = ["snapshotId"], childColumns = ["snapshotId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["snapshotId", "ordinal"], unique = true), Index(value = ["relativePath"], unique = true), Index(value = ["state"])])
data class ExportArtifactRow(@PrimaryKey val artifactId: String, val snapshotId: String, val ordinal: Int, val mime: String, val relativePath: String, val byteSha256: String, val byteCount: Long, val pageCount: Int, val verification: String, val state: String, val createdAt: Long)

@Entity(tableName = "FileTransaction", indices = [Index(value = ["kind", "ownerId", "attempt"], unique = true), Index(value = ["state", "updatedAt"])])
data class FileTransactionRow(@PrimaryKey val transactionId: String, val kind: String, val ownerId: String, val state: String, val stagingPath: String?, val finalPath: String?, val sealSha256: String?, val expectedByteSha256: String?, val expectedByteCount: Long?, val payload: ByteArray, val attempt: Long, val errorCode: String?, val updatedAt: Long)

@Entity(tableName = "Publication", foreignKeys = [ForeignKey(entity = ExportArtifactRow::class, parentColumns = ["artifactId"], childColumns = ["artifactId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["artifactId", "state"])])
data class PublicationRow(@PrimaryKey val publicationId: String, val artifactId: String, val targetUri: String?, val state: String, val consentId: String, val targetCreatedByAttempt: Boolean, val expectedSha256: String, val expectedByteCount: Long, val sentByteCount: Long, val readbackSha256: String?, val errorCode: String?, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "ShareLease", foreignKeys = [ForeignKey(entity = ExportArtifactRow::class, parentColumns = ["artifactId"], childColumns = ["artifactId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["artifactId"]), Index(value = ["relativePath"], unique = true), Index(value = ["state", "expiresAt"])])
data class ShareLeaseRow(@PrimaryKey val shareId: String, val artifactId: String, val relativePath: String, val state: String, val createdAt: Long, val expiresAt: Long, val explicitPackages: ByteArray?)
