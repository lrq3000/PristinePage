package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read-only exact binding and closed-byte proof. No provider, live page/recipe, decoder, pin update or filesystem mutation; file hashing/path resolution run after releasing the short metadata transaction. Immutable cooperative roots and a caller-held retained owner are required. */
class RoomCommittedSources @JvmOverloads constructor(private val metadata: MetadataAccess, private val bounds: CommittedSourceQueries, private val files: SourceFiles, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : CommittedSources {
    override suspend fun load(sourceId: SourceId): CommittedSource = withContext(dispatcher) {
        try {
            coroutineContext.ensureActive()
            val (source, blob) = metadata.transaction {
                when (bounds.sourceReadIsBounded(sourceId.value)) {
                    null -> refuse(OperationErrorCode.SOURCE_UNAVAILABLE, "source_not_found", sourceId)
                    false -> refuse(OperationErrorCode.INTEGRITY_FAILED, "committed_source_invalid", sourceId)
                    true -> Unit
                }
                coroutineContext.ensureActive()
                val source = metadata.queries.source(sourceId.value) ?: refuse(OperationErrorCode.INTEGRITY_FAILED, "committed_source_invalid", sourceId)
                val blob = metadata.queries.sourceBlob(source.sourceBlobId) ?: refuse(OperationErrorCode.INTEGRITY_FAILED, "committed_source_invalid", sourceId)
                source to blob
            }
            require(source.sourceId == sourceId.value && source.sourceBlobId == blob.sourceBlobId)
            require(source.state == "READY" && blob.state == "READY" && source.refCount >= 0 && blob.sourceCount >= 0)
            val blobId = SourceBlobId(blob.sourceBlobId)
            requireOpaqueId(blob.ingestTransactionId)
            require(blob.relativePath == "sources/${blobId.value}/original.bin")
            val inspection = SourceInspection(blob.mime, PixelSize(blob.encodedWidth, blob.encodedHeight), PixelSize(source.width, source.height), source.exifOrientation, source.profileSha256, source.conversionFingerprint, source.pixelSha256)
            // verifyOriginal validates byte count/hash before any allocation-sized read. Recheck the cooperative canonical path on both sides of proof; no cached preview can stand in for this original.
            files.verifyOriginal(blobId.value, blob.byteCount, blob.byteSha256) { coroutineContext.ensureActive() }
            val result = CommittedSource(sourceId, blobId, blob.byteSha256, blob.byteCount, inspection, files.finalFile(blobId.value))
            coroutineContext.ensureActive()
            result
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INTEGRITY_FAILED, "committed_source_invalid", sourceId) }
        catch (_: Exception) { refuse(OperationErrorCode.STORAGE_FAILED, "committed_source_read_failed", sourceId, true) }
    }

    private fun refuse(code: OperationErrorCode, key: String, source: SourceId, retryable: Boolean = false): Nothing = throw RepositoryFailure(OperationError(code, retryable, key, source.value))
}
