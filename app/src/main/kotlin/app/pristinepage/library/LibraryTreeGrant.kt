package app.pristinepage.library

import android.content.Intent
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** An exact Android grant must not look pre-existing to a second preview while the first can still release it. */
internal class ProvisionalFolderGrants {
    private val active = HashSet<String>()

    @Synchronized fun claim(exactUri: String): Claim {
        val claim = Claim(exactUri)
        if (!active.add(exactUri)) throw IOException("Another review is using this folder permission")
        return claim
    }

    inner class Claim internal constructor(private val exactUri: String) {
        private val finished = AtomicBoolean()

        /** Keep the key claimed through the platform release; a subsequent owner must take a fresh baseline. */
        fun finish(cleanup: () -> Unit) {
            if (!finished.compareAndSet(false, true)) return
            try { cleanup() }
            finally { synchronized(this@ProvisionalFolderGrants) { check(active.remove(exactUri)) } }
        }
    }
}

/** The picker capability and the currently persisted access modes are separate facts. */
internal class LibraryTreeGrant private constructor(val exactUri: String, val returnedWrite: Boolean) {
    data class Held(val exactUri: String, val read: Boolean, val write: Boolean)

    fun needsReadGrant(held: List<Held>): Boolean = held.none { it.exactUri == exactUri && it.read }

    fun requirePersistedRead(held: List<Held>) {
        if (needsReadGrant(held)) throw IOException("The selected folder's exact read permission is unavailable")
    }

    /** Existing write access does not allow a newly returned read-only selection to authorize a write. */
    fun missingWriteModes(held: List<Held>): Int {
        if (!returnedWrite) throw IOException("The selected folder did not grant write access")
        val exact = held.singleOrNull { it.exactUri == exactUri }
        return (if (exact?.read == true) 0 else Intent.FLAG_GRANT_READ_URI_PERMISSION) or
            (if (exact?.write == true) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun requirePersistedWrite(held: List<Held>) {
        if (missingWriteModes(held) != 0) throw IOException("The selected folder's exact read and write permissions are unavailable")
    }

    /** Check cancellation after permission lookup and immediately before the irreversible platform call. */
    fun persistMissing(missing: Int, checkpoint: () -> Unit, take: (Int) -> Unit) {
        if (missing == 0) return
        checkpoint()
        take(missing)
    }

    companion object {
        fun inspect(exactUri: String, returnedRead: Boolean, returnedWrite: Boolean, persistable: Boolean): LibraryTreeGrant {
            if (!returnedRead || !persistable) throw IOException("The selected folder did not grant persistent read access")
            return LibraryTreeGrant(exactUri, returnedWrite)
        }
    }
}
