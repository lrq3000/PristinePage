package app.pristinepage.image.render

import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.util.concurrent.CancellationException

/** One fail-closed policy shared only by attempted geometry/photometric stage boundaries. Decode, binding, graph admission and final packing must stay outside these catches. Fatal Errors (including allocation) are never converted into fallback pixels. */
internal object StageFallback {
    fun failure(error: Exception, policy: FallbackPolicy, safeProcessingKey: String): OperationError {
        if (error is CancellationException || policy == FallbackPolicy.FAIL) throw error
        if (error is RepositoryFailure) {
            when (error.error.code) {
                OperationErrorCode.PROCESSING_FAILED, OperationErrorCode.INVALID_MAP, OperationErrorCode.PROVIDER_UNAVAILABLE -> return error.error
                else -> throw error
            }
        }
        return OperationError(OperationErrorCode.PROCESSING_FAILED, false, safeProcessingKey, null)
    }
}
