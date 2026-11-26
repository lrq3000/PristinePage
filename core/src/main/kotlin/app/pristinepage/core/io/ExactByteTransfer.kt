package app.pristinepage.core.io

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Borrows already-authorized streams. Successful return proves input bytes, not that the sink has flushed, closed or retained them. Empty input still requires its exact digest and EOF. */
fun transferExactBytes(
    input: InputStream, output: OutputStream?, byteCount: Long, sha256: String,
    checkpoint: () -> Unit, wrote: (Int) -> Unit = {},
) {
    require(byteCount >= 0)
    val buffer = ByteArray(65_536)
    val digest = MessageDigest.getInstance("SHA-256")
    var remaining = byteCount
    while (remaining > 0) {
        checkpoint()
        val requested = minOf(remaining, buffer.size.toLong()).toInt()
        val count = input.read(buffer, 0, requested)
        checkpoint() // A provider read may return only after cancellation. Do not forward that chunk.
        if (count !in 1..requested) throw IOException("Invalid export read size")
        digest.update(buffer, 0, count)
        if (output != null) {
            output.write(buffer, 0, count)
            wrote(count) // A throwing sink can still have written partially; report only acknowledged counts.
        }
        remaining -= count
    }
    checkpoint()
    val eof = input.read() == -1
    checkpoint()
    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    if (!eof || hash != sha256) throw IOException("Export copy differs")
}
