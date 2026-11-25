package app.pristinepage.image.decode

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.model.SourceInspection
import app.pristinepage.data.repository.CommittedSourceBinding
import app.pristinepage.data.repository.CommittedSources
import app.pristinepage.data.repository.ExportSourceBinding
import app.pristinepage.data.repository.ProducerCommittedSources
import app.pristinepage.data.repository.ScopedCommittedOriginals
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Same full-source normalizer as ingestion, not an arbitrary URI/thumbnail loader. Returning a DecodedSource transfers its entire RGB/reservation ownership. */
fun interface SourceFileDecoder {
    suspend fun decodeFile(file: File, expectedByteSha256: String): DecodedSource
}

/** The output is a downstream render destination: geometry samples this full oriented source directly. Neither preview dimensions nor geometry aspect can redefine source identity, trigger premature source resizing or bypass full-source memory admission. The caller retains a page/history/job/snapshot source owner until the result closes. */
class SourceDecoder(private val sources: ScopedCommittedOriginals, private val decoder: SourceFileDecoder) {
    constructor(sources: CommittedSources, decoder: SourceFileDecoder) : this(
        ScopedCommittedOriginals { id, expected, read ->
            val recorded = if (expected == null) sources.load(id) else {
                // Use the bound view of the same capability, never an ordinary lookup or a proof from a second store.
                val producer = sources as? ProducerCommittedSources
                    ?: refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "producer_committed_source_unavailable", id)
                producer.loadForProducer(id, expected)
            }
            read(recorded.binding(), recorded.file)
        }, decoder,
    )

    suspend fun decode(sourceId: String, output: OutputSpec): DecodedSource = decode(sourceId, output, null)

    internal suspend fun decodeForProducer(sourceId: String, output: OutputSpec, expected: ExportSourceBinding): DecodedSource =
        decode(sourceId, output, expected)

    /** Observe admitted metadata before opening the codec. This never replaces byte or decoded-pixel authentication. */
    internal suspend fun decodeForOcr(
        sourceId: String, output: OutputSpec, checkCapacity: (SourceInspection, Long) -> Unit,
    ): DecodedSource = decode(sourceId, output, null, checkCapacity)

    private suspend fun decode(
        sourceId: String, output: OutputSpec, expected: ExportSourceBinding?,
        checkCapacity: ((SourceInspection, Long) -> Unit)? = null,
    ): DecodedSource {
        val context = currentCoroutineContext()
        context.ensureActive()
        val id = try { SourceId(sourceId) }
        catch (_: IllegalArgumentException) { throw RepositoryFailure(OperationError(OperationErrorCode.INVALID_INPUT, false, "invalid_source_id", null)) }
        if (expected != null && expected.source.sourceId != id.value) refuse(OperationErrorCode.INTEGRITY_FAILED, "committed_source_binding_mismatch", id)
        if (output.size.width > 12000 || output.size.height > 12000 || output.size.pixelCount > 48_000_000) refuse(OperationErrorCode.RESOURCE_LIMIT, "render_destination_limit", id)
        var provisional: DecodedSource? = null
        var accepted = false
        try {
            sources.withOriginal(id, expected) { recorded, file ->
                val loan = currentCoroutineContext()
                loan.ensureActive()
                if (provisional != null || recorded.sourceId != id || (expected != null && !recorded.matches(expected))) {
                    refuse(OperationErrorCode.INTEGRITY_FAILED, "committed_source_binding_mismatch", id)
                }
                checkCapacity?.invoke(recorded.inspection, recorded.byteCount)
                loan.ensureActive()
                val decoded = decoder.decodeFile(file, recorded.sourceByteHash).also { provisional = it }
                loan.ensureActive()
                // Different platform/policy conversions cannot silently overwrite a retained identity, even if some decoded samples happen to match.
                if (decoded.inspection.conversionFingerprint != recorded.inspection.conversionFingerprint) refuse(OperationErrorCode.UNSUPPORTED_VERSION, "source_conversion_unavailable", id)
                if (decoded.sourceByteHash != recorded.sourceByteHash || decoded.inspection != recorded.inspection || decoded.image.width != recorded.inspection.normalizedSize.width || decoded.image.height != recorded.inspection.normalizedSize.height) refuse(OperationErrorCode.INTEGRITY_FAILED, "source_normalization_changed", id)
                accepted = true
            }
            // The file is no longer borrowed. Only now may the authenticated raster and its independent permit leave this operation.
            context.ensureActive()
            val decoded = provisional
            if (decoded == null || !accepted) refuse(OperationErrorCode.INTEGRITY_FAILED, "committed_source_not_delivered", id)
            provisional = null
            return decoded
        } finally { provisional?.close() }
    }

    private fun CommittedSourceBinding.matches(expected: ExportSourceBinding): Boolean {
        val (source, blob) = expected
        val info = inspection
        return source.sourceBlobId == blob.sourceBlobId && sourceBlobId.value == blob.sourceBlobId &&
            sourceByteHash == blob.byteSha256 && byteCount == blob.byteCount &&
            info.mime == blob.mime && info.encodedSize.width == blob.encodedWidth && info.encodedSize.height == blob.encodedHeight &&
            info.normalizedSize.width == source.width && info.normalizedSize.height == source.height &&
            info.exifOrientation == source.exifOrientation && info.profileSha256 == source.profileSha256 &&
            info.conversionFingerprint == source.conversionFingerprint && info.sourcePixelHash == source.pixelSha256
    }

    private companion object {
        fun refuse(code: OperationErrorCode, key: String, source: SourceId): Nothing =
            throw RepositoryFailure(OperationError(code, false, key, source.value))
    }
}
