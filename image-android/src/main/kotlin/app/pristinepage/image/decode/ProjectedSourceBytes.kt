package app.pristinepage.image.decode

import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/** Construct a bounded codec-only byte view; authenticate even omitted metadata and trailing gain-map bytes. */
internal class ProjectedSourceBytes(
    private val completeBytes: Long,
    private val primaryBytes: Long,
    excluded: List<LongRange>,
) {
    private val excluded = excluded.toList()

    init {
        require(completeBytes in primaryBytes..104857600 && primaryBytes >= 4)
        var end = 0L
        for (range in this.excluded) {
            require(range.first >= end && !range.isEmpty() && range.last < primaryBytes)
            end = range.last + 1
        }
    }

    fun read(file: File, expected: String, checkpoint: () -> Unit): ByteArray {
        val retained = primaryBytes - excluded.sumOf { it.last + 1 - it.first }
        val bytes = ByteArray(retained.toInt())
        val scratch = ByteArray(65536)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(65536).use { raw ->
            val input = DigestInputStream(raw, digest)
            var position = 0L
            var written = 0
            fun consume(until: Long, retain: Boolean) {
                val target = if (retain) bytes else scratch
                while (position < until) {
                    checkpoint()
                    val request = minOf(until - position, 65536L).toInt()
                    val count = input.read(target, if (retain) written else 0, request)
                    if (count !in 1..request) mismatch()
                    position += count
                    if (retain) written += count
                }
            }
            for (range in excluded) {
                consume(range.first, true)
                consume(range.last + 1, false)
            }
            consume(primaryBytes, true)
            consume(completeBytes, false)
            if (input.read() != -1 || written != bytes.size) mismatch()
        }
        if (digest.digest().toHexString() != expected) mismatch()
        checkpoint()
        return bytes
    }

    private fun mismatch(): Nothing = throw RepositoryFailure(OperationError(
        OperationErrorCode.INTEGRITY_FAILED, false, "source_byte_identity_mismatch", null))
}
