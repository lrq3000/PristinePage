package app.pristinepage.data.repository

import app.pristinepage.data.model.IngestJournalIntent
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Shared pre-execution identity for preparation, final completion and retained commit replay. It hashes canonical intent bytes only, never provider references, lease attempts or a later commit receipt. */
internal object IngestJobIdentity {
    fun key(intent: IngestJournalIntent) = "ingest-v1:${intent.sessionId.value}:${intent.attempt}"
    fun hash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("pristinepage-ingest-input-v1\u0000".toByteArray(Charsets.UTF_8))
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
