package app.pristinepage.library

import android.content.Intent
import java.io.IOException

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

    companion object {
        fun inspect(exactUri: String, returnedRead: Boolean, returnedWrite: Boolean, persistable: Boolean): LibraryTreeGrant {
            if (!returnedRead || !persistable) throw IOException("The selected folder did not grant persistent read access")
            return LibraryTreeGrant(exactUri, returnedWrite)
        }
    }
}
