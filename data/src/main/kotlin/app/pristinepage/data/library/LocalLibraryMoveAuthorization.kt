package app.pristinepage.data.library

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.LibraryNamedPublication.FileProof
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** One-shot spend records beneath the same exclusively owned private operation root on every open. The caller bounds the operation, makes its ancestry durable, and supplies a stable key per exact destination leaf that cannot change with the parent proof or process. */
internal class LocalLibraryMoveAuthorization(directory: File, private val sync: DirectorySync = PlatformDirectorySync) {
    private val root = directory.absoluteFile.toPath()

    /** A spend neither proves that the move ran nor authorizes replay. On write/force failure preserve any visible record. */
    fun consume(leaf: String, parents: String, proof: FileProof) {
        val path = record(leaf)
        val expected = encode(leaf, parents, proof)
        forceRoot()
        FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteBuffer.wrap(expected)
            while (bytes.hasRemaining()) {
                if (channel.write(bytes) <= 0) throw IOException("Could not record library publication authorization")
            }
            channel.force(true)
        }
        forceRoot()
        requireExact(path, expected)
    }

    /** Verification only. Positive absence is distinct from a corrupt, uncertain or inaccessible spend. It is not permission to move. */
    fun isConsumed(leaf: String, parents: String, proof: FileProof): Boolean {
        val path = record(leaf)
        val expected = encode(leaf, parents, proof)
        if (Files.notExists(path, NOFOLLOW_LINKS)) return false
        requireExact(path, expected, force = true)
        forceRoot()
        requireExact(path, expected)
        return true
    }

    private fun record(leaf: String): Path {
        requireSha256(leaf)
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || root.toRealPath() != root) {
            throw IOException("Private library publication journal is unavailable")
        }
        return root.resolve("$leaf.spent")
    }

    private fun encode(leaf: String, parents: String, proof: FileProof): ByteArray {
        requireSha256(parents)
        return "pristinepage-library-move-spent-v1\n$leaf\n$parents\n${proof.identity}\n${proof.digest}\n${proof.byteCount}\n"
            .toByteArray(Charsets.US_ASCII)
    }

    private fun requireExact(path: Path, expected: ByteArray, force: Boolean = false) {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("Library publication authorization is unavailable")
        val flags = if (force) arrayOf<OpenOption>(READ, WRITE, NOFOLLOW_LINKS) else arrayOf<OpenOption>(READ, NOFOLLOW_LINKS)
        FileChannel.open(path, *flags).use { channel ->
            val bytes = ByteBuffer.allocate(expected.size + 1)
            while (bytes.hasRemaining()) {
                val n = channel.read(bytes)
                if (n == -1) break
                if (n == 0) throw IOException("Library publication authorization could not be read")
            }
            if (bytes.position() != expected.size || !bytes.array().copyOf(expected.size).contentEquals(expected)) {
                throw IOException("Library publication authorization does not match")
            }
            if (force) channel.force(true)
        }
    }

    private fun forceRoot() {
        if (!sync.sync(root.toFile())) throw IOException("Library publication authorization is not durable")
    }
}
