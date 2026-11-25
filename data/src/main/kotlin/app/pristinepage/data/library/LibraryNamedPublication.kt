package app.pristinepage.data.library

import app.pristinepage.core.pipeline.requireSha256
import java.io.IOException

/** Verify the single same-basename move of a closed, attempt-owned file. Not a catalog publisher or a recovery journal. */
internal class LibraryNamedPublication(private val store: Store, private val checkpoint: () -> Unit = {}) {
    /** Identity is comparable only while the adapter can still prove the same file. A stored Android inode number is not such proof after its anchoring descriptor is gone. */
    data class FileProof(val identity: String, val digest: String, val byteCount: Long) {
        init {
            require(identity.length in 1..256 && identity.all { it in '!'..'~' })
            requireSha256(digest)
            require(byteCount > 0)
        }
    }

    /** Implementations hold stable, separate staging and final parents under cooperative exclusive ownership. Observations require fresh name lookup and hash and identity from the same open regular-file descriptor. Null means positive absence, not access loss or uncertainty. */
    interface Store {
        fun readStage(): FileProof?
        fun readFinal(): FileProof?
        /** Durably consume this exact attempt's move authority, or refuse. Never mint it again if the outcome of the old call is uncertain. */
        fun consumeMoveAuthorization()
        fun moveOnce()
        fun syncStageParent()
        fun syncFinalParent()
    }

    /** The caller binds the exact staged proof and both parents before requesting mutation. Reconciliation only verifies and syncs; the adapter must refuse if it no longer has an identity anchor it can trust. */
    fun commit(expected: FileProof, reconcileOnly: Boolean) {
        checkpoint()
        if (!reconcileOnly) {
            if (observation(store::readStage) != expected || observation(store::readFinal) != null) {
                throw IOException("The staged library file or final name changed")
            }
            store.consumeMoveAuthorization()
            checkpoint()
            // A thrown result can have moved the file. Only an independently authorized reconciliation can acknowledge it later.
            store.moveOnce()
        }
        requireMoved(expected)
        store.syncStageParent()
        checkpoint()
        store.syncFinalParent()
        requireMoved(expected)
    }

    private fun requireMoved(expected: FileProof) {
        checkpoint()
        if (observation(store::readStage) != null || observation(store::readFinal) != expected) {
            throw IOException("The exact staged library file was not found at its final name")
        }
        checkpoint()
    }

    private inline fun observation(read: () -> FileProof?): FileProof? = read().also { checkpoint() }
}
