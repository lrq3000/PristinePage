package app.pristinepage.image.decode

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.CommittedSources
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Same full-source normalizer as ingestion, not an arbitrary URI/thumbnail loader. Returning a DecodedSource transfers its entire RGB/reservation ownership. */
fun interface SourceFileDecoder {
    suspend fun decodeFile(file: File, expectedByteSha256: String): DecodedSource
}

/** The output is a downstream render destination: geometry samples this full oriented source directly. Neither preview dimensions nor geometry aspect can redefine source identity, trigger premature source resizing or bypass full-source memory admission. The caller retains a page/history/job/snapshot source owner until the result closes. */
class SourceDecoder(private val sources: CommittedSources, private val decoder: SourceFileDecoder) {
    suspend fun decode(sourceId: String, output: OutputSpec): DecodedSource {
        val context = currentCoroutineContext()
        context.ensureActive()
        val id = try { SourceId(sourceId) }
        catch (_: IllegalArgumentException) { throw RepositoryFailure(OperationError(OperationErrorCode.INVALID_INPUT, false, "invalid_source_id", null)) }
        if (output.size.width > 12000 || output.size.height > 12000 || output.size.pixelCount > 48_000_000) refuse(OperationErrorCode.RESOURCE_LIMIT, "render_destination_limit", id)
        val recorded = sources.load(id)
        if (recorded.sourceId != id) refuse(OperationErrorCode.INTEGRITY_FAILED, "committed_source_binding_mismatch", id)
        context.ensureActive()
        val decoded = decoder.decodeFile(recorded.file, recorded.sourceByteHash)
        var delivered = false
        try {
            context.ensureActive()
            // Different platform/policy conversions cannot silently overwrite a retained identity, even if some decoded samples happen to match.
            if (decoded.inspection.conversionFingerprint != recorded.inspection.conversionFingerprint) refuse(OperationErrorCode.UNSUPPORTED_VERSION, "source_conversion_unavailable", id)
            if (decoded.sourceByteHash != recorded.sourceByteHash || decoded.inspection != recorded.inspection || decoded.image.width != recorded.inspection.normalizedSize.width || decoded.image.height != recorded.inspection.normalizedSize.height) refuse(OperationErrorCode.INTEGRITY_FAILED, "source_normalization_changed", id)
            context.ensureActive()
            delivered = true
            return decoded
        } finally { if (!delivered) decoded.close() }
    }

    private fun refuse(code: OperationErrorCode, key: String, source: SourceId): Nothing = throw RepositoryFailure(OperationError(code, false, key, source.value))
}
