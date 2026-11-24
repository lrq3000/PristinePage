package app.pristinepage.data.library

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.ActiveLibraryFence.Identity
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** One private pointer in an already durable settings directory. The caller serializes access across cooperating processes and verifies the selected storage and generation independently of this record. */
internal class LocalActiveLibrarySelection(root: File, private val directorySync: DirectorySync = PlatformDirectorySync) {
    private val directory = root.absoluteFile
    private val parent = directory.toPath()
    private val pointer = parent.resolve("active-library")

    /** Null is positive absence, not permission to create a default after an earlier selection may have been lost. */
    fun readVerified(): Selection? {
        val first = readExact()
        forceDirectory()
        if (readExact() != first) throw IOException("Active library selection changed during verification")
        return first
    }

    /** False does not change an authoritative expected selection. A missing or different non-null predecessor must be reconciled, not treated as a refused switch. */
    fun replace(expected: Selection?, next: Selection): Boolean {
        val previous = readVerified()
        if (previous != expected) {
            if (expected == null) return false
            throw IOException("The expected active library selection is no longer authoritative")
        }
        if ((expected == null && next.location != Location.Default) ||
            next.identity.activation != (expected?.identity?.activation ?: 0L) + 1
        ) return false

        // Keep uncertain prepublication stages bounded; no stale stage is selection authority or implicitly deleted.
        Files.newDirectoryStream(parent, ".active-library-*.pending").use {
            if (it.asSequence().take(4).count() == 4) throw IOException("Retained active library stages need attention")
        }
        val stage = Files.createTempFile(parent, ".active-library-", ".pending")
        FileChannel.open(stage, WRITE, NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteBuffer.wrap(encode(next))
            while (bytes.hasRemaining()) {
                if (channel.write(bytes) <= 0) throw IOException("Could not write the active library selection")
            }
            channel.force(true)
        }
        // No in-place rewrite and no copy/delete fallback. The old pointer remains until the atomic replacement.
        Files.move(stage, pointer, ATOMIC_MOVE, REPLACE_EXISTING)
        if (readVerified() != next) throw IOException("Published active library selection changed")
        return true
    }

    private fun readExact(): Selection? {
        if (parent.toRealPath() != parent || !Files.isDirectory(parent, NOFOLLOW_LINKS)) {
            throw IOException("Active library settings directory is unavailable")
        }
        val attributes = try { Files.readAttributes(pointer, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return null }
        if (!attributes.isRegularFile || attributes.size() !in 1..MAX_BYTES.toLong()) invalid()
        val data = ByteBuffer.allocate(MAX_BYTES + 1)
        FileChannel.open(pointer, READ, NOFOLLOW_LINKS).use { channel ->
            while (data.hasRemaining()) {
                when (channel.read(data)) {
                    -1 -> break
                    0 -> invalid()
                }
            }
        }
        val extent = data.position()
        if (extent.toLong() != attributes.size()) invalid()
        val bytes = data.array().copyOf(extent)
        val fields = bytes.toString(Charsets.US_ASCII).split('\n')
        if (fields.size != 7 || fields[0] != HEADER || fields[6].isNotEmpty()) invalid()
        val selection = try {
            val location = if (fields[3] == "-" && fields[4] == "-") Location.Default else Location.Folder(fields[3], fields[4])
            Selection(location, Identity(fields[1], fields[2].toLongOrNull() ?: invalid()))
        } catch (_: IllegalArgumentException) { invalid() }
        if (!bytes.contentEquals(encode(selection))) invalid()
        return selection
    }

    private fun forceDirectory() {
        if (!directorySync.sync(directory)) throw IOException("Active library selection durability is unavailable")
    }

    private fun encode(selection: Selection): ByteArray {
        val location = selection.location as? Location.Folder
        val body = "$HEADER\n${selection.identity.libraryId}\n${selection.identity.activation}\n${location?.grantId ?: "-"}\n${location?.admittedGenerationId ?: "-"}\n"
            .toByteArray(Charsets.US_ASCII)
        // Detect accidental corruption of valid-looking fields; this cannot establish authenticity or rule out replay of an entire prior record.
        val checksum = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
        return body + "$checksum\n".toByteArray(Charsets.US_ASCII)
    }

    private fun invalid(): Nothing = throw IOException("Invalid active library selection")

    private companion object {
        const val HEADER = "pristinepage-active-library-v1"
        const val MAX_BYTES = 256
    }
}
