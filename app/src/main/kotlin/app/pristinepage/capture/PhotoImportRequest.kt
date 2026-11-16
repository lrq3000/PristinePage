package app.pristinepage.capture

import android.os.Bundle
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure

/** Identity of an already persisted BATCH, not a document choice or proof of provider access. Restoration never allocates a replacement: Room still validates existence, state and destination when a result is consumed. */
class PhotoImportRequest(val batchId: String) {
    internal val session: ImportSessionId = try { ImportSessionId(batchId) }
    catch (_: IllegalArgumentException) { expired() }

    internal val registryKey: String get() = "pristinepage.photo.result.v1:$batchId"

    /** The app's saved state carries no source URI, user title, pixels or duplicate document/anchor authority. AndroidX owns its separate registry state. */
    fun save(): Bundle = Bundle().apply { putString(SAVED_ID, batchId) }

    companion object {
        private const val SAVED_ID = "pristinepage.photo.batch.v1"

        @JvmStatic fun restore(saved: Bundle?): PhotoImportRequest? {
            if (saved == null) return null
            return try {
                if (!saved.containsKey(SAVED_ID)) return null
                // Bundle.getString logs type-warning values for a wrong type. Inspect without coercion so malformed private state never becomes a URI-bearing log message.
                @Suppress("DEPRECATION")
                val id = saved.get(SAVED_ID) as? String ?: expired()
                PhotoImportRequest(id)
            } catch (failure: RepositoryFailure) { throw failure }
            catch (_: RuntimeException) { expired() }
        }

        private fun expired(): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.SESSION_EXPIRED, false, "photo_request_expired", null))
    }
}
