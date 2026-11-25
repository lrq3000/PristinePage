package app.pristinepage.data.library

import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException
import java.security.MessageDigest

/** Private exact selection locator, not a grant. Android must independently re-establish this URI and its persisted access. */
internal data class LibraryFolderGrantRecord(val grantId: String, val exactUri: String, val returnedModes: Int) {
    init {
        requireOpaqueId(grantId)
        require(returnedModes == 1 || returnedModes == 3)
        require(exactUri.length in (PREFIX.length + 1)..4_096 && exactUri.startsWith(PREFIX))
        require(exactUri.drop(PREFIX.length).all { it in '!'..'~' && it !in "/?#" })
    }

    fun encode(): ByteArray {
        val body = "$FORMAT\n$grantId\n$returnedModes\n$exactUri\n".toByteArray(Charsets.US_ASCII)
        val checksum = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
        return body + "$checksum\n".toByteArray(Charsets.US_ASCII)
    }

    companion object {
        private const val FORMAT = "pristinepage-library-grant-v1"
        private const val PREFIX = "content://com.android.externalstorage.documents/tree/"
        const val MAX_BYTES = 4_352

        fun decode(bytes: ByteArray, expectedGrantId: String): LibraryFolderGrantRecord {
            if (bytes.size !in 1..MAX_BYTES) invalid()
            val fields = bytes.toString(Charsets.US_ASCII).split('\n', limit = 7)
            if (fields.size != 6 || fields[0] != FORMAT || fields[1] != expectedGrantId || fields[5].isNotEmpty()) invalid()
            val record = try { LibraryFolderGrantRecord(fields[1], fields[3], fields[2].toIntOrNull() ?: invalid()) }
            catch (_: IllegalArgumentException) { invalid() }
            if (!record.encode().contentEquals(bytes)) invalid()
            return record
        }

        private fun invalid(): Nothing = throw IOException("The private library folder binding is invalid")
    }
}
