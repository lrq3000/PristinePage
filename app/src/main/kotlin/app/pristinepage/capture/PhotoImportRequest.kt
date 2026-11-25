package app.pristinepage.capture

import android.os.Bundle
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure

/** Identity of an already persisted BATCH, not a document choice or proof of provider access. Restoration never allocates a replacement: Room still validates existence, state and destination when a result is consumed. */
enum class PhotoImportKind { GALLERY, FILES, CAMERA }

class PhotoImportRequest private constructor(
    val batchId: String, val kind: PhotoImportKind, val issuedAt: LibraryActivation?, internal val canDeliver: Boolean,
) {
    @JvmOverloads constructor(batchId: String, kind: PhotoImportKind = PhotoImportKind.GALLERY, issuedAt: LibraryActivation? = null) :
        this(batchId, kind, issuedAt, true)

    internal val session: ImportSessionId = try { ImportSessionId(batchId) }
    catch (_: IllegalArgumentException) { expired() }

    internal val registryKey: String get() = issuedAt?.let { "pristinepage.photo.result.v2:${it.libraryId}:${it.number}:$batchId:${kind.name}" }
        ?: "pristinepage.photo.result.v1:$batchId"

    internal fun matches(other: PhotoImportRequest) =
        batchId == other.batchId && kind == other.kind && issuedAt == other.issuedAt && canDeliver == other.canDeliver

    internal fun routedAs(kind: PhotoImportKind) = PhotoImportRequest(batchId, kind, issuedAt, canDeliver)

    /** The app's saved state carries no source URI, user title, pixels or duplicate document/anchor authority. AndroidX owns its separate registry state. */
    fun save(): Bundle = Bundle().apply {
        check(canDeliver)
        putString(SAVED_ID, batchId)
        if (kind != PhotoImportKind.GALLERY) putString(SAVED_KIND, kind.name)
        saveActivation(issuedAt)
    }

    companion object {
        private const val SAVED_ID = "pristinepage.photo.batch.v1"
        private const val SAVED_KIND = "kind"
        private const val SAVED_LIBRARY = "library"
        private const val SAVED_ACTIVATION = "activation"

        internal fun abandonmentOnly(batch: ImportSessionId, activation: LibraryActivation? = null) =
            PhotoImportRequest(batch.value, PhotoImportKind.GALLERY, activation, false)

        internal fun Bundle.saveActivation(activation: LibraryActivation?) {
            if (activation != null) {
                putString(SAVED_LIBRARY, activation.libraryId)
                putLong(SAVED_ACTIVATION, activation.number)
            }
        }

        @Suppress("DEPRECATION")
        internal fun Bundle.restoreActivation(): LibraryActivation? {
            if (!containsKey(SAVED_LIBRARY) && !containsKey(SAVED_ACTIVATION)) return null
            return try {
                LibraryActivation(get(SAVED_LIBRARY) as? String ?: expired(), get(SAVED_ACTIVATION) as? Long ?: expired())
            } catch (_: IllegalArgumentException) { expired() }
        }

        @JvmStatic fun restore(saved: Bundle?): PhotoImportRequest? {
            if (saved == null) return null
            return try {
                if (!saved.containsKey(SAVED_ID)) return null
                // Bundle.getString logs type-warning values for a wrong type. Inspect without coercion so malformed private state never becomes a URI-bearing log message.
                @Suppress("DEPRECATION")
                val id = saved.get(SAVED_ID) as? String ?: expired()
                @Suppress("DEPRECATION")
                val kind = if (saved.containsKey(SAVED_KIND)) PhotoImportKind.valueOf(saved.get(SAVED_KIND) as? String ?: expired()) else PhotoImportKind.GALLERY
                PhotoImportRequest(id, kind, saved.restoreActivation())
            } catch (failure: RepositoryFailure) { throw failure }
            catch (_: RuntimeException) { expired() }
        }

        private fun expired(): Nothing = throw RepositoryFailure(OperationError(OperationErrorCode.SESSION_EXPIRED, false, "photo_request_expired", null))
    }
}
