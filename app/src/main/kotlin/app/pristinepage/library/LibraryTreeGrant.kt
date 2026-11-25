package app.pristinepage.library

import java.io.IOException

/** The picker capability and its exact persisted read permission are separate facts. This slice never persists write permission. */
internal class LibraryTreeGrant private constructor(val exactUri: String, val returnedWrite: Boolean) {
    data class Held(val exactUri: String, val read: Boolean, val write: Boolean)

    fun needsReadGrant(held: List<Held>): Boolean = held.none { it.exactUri == exactUri && it.read }

    fun requirePersistedRead(held: List<Held>) {
        if (needsReadGrant(held)) throw IOException("The selected folder's exact read permission is unavailable")
    }

    companion object {
        fun inspect(exactUri: String, returnedRead: Boolean, returnedWrite: Boolean, persistable: Boolean): LibraryTreeGrant {
            if (!returnedRead || !persistable) throw IOException("The selected folder did not grant persistent read access")
            return LibraryTreeGrant(exactUri, returnedWrite)
        }
    }
}
