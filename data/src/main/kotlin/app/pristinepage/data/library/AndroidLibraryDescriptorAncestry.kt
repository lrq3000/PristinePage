package app.pristinepage.data.library

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.pristinepage.data.library.LibraryRootSeparation.Identity
import java.io.File
import java.io.IOException

/** Read-only observation, not a write permit. Callers keep all three descriptors open and undetached and separately bind provider locators to them. Sequential walks do not exclude a concurrent rename; a writer must also hold stable, cooperative topology ownership through its operation. */
internal object AndroidLibraryDescriptorAncestry {
    private const val MAX_CHAIN = 128
    private const val MAX_FD_INFO_BYTES = 1_024

    fun admit(left: ParcelFileDescriptor, right: ParcelFileDescriptor, anchor: ParcelFileDescriptor,
        checkpoint: () -> Unit = {},
    ): LibraryRootSeparation {
        checkpoint()
        val expected = observe(anchor)
        fun chain(leaf: ParcelFileDescriptor): List<Identity> {
            checkpoint()
            if (observe(anchor) != expected) unavailable()
            val result = ArrayList<Identity>()
            val seen = HashSet<Identity>()
            fun visit(current: ParcelFileDescriptor) {
                checkpoint()
                val at = observe(current)
                if (at.mount != expected.mount || at.identity.device != expected.identity.device || !seen.add(at.identity)) unavailable()
                result += at.identity
                if (at == expected) return
                if (result.size == MAX_CHAIN) unavailable()
                // Follow the live kernel-held directory, not the printed link target or a path decoded from a document ID.
                descriptorIo {
                    ParcelFileDescriptor.open(File("/proc/self/fd/${current.fd}/.."), ParcelFileDescriptor.MODE_READ_ONLY)
                }.use(::visit)
            }
            visit(leaf)
            checkpoint()
            return result
        }
        return LibraryRootSeparation.admit({ chain(left) }, { chain(right) })
    }

    private data class Observation(val mount: Long, val identity: Identity)

    private fun observe(descriptor: ParcelFileDescriptor): Observation = descriptorIo {
        val stat = Os.fstat(descriptor.fileDescriptor)
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_ino <= 0L || stat.st_nlink <= 0L) unavailable()
        // Device/inode alone cannot reject an intervening same-device bind mount. Require the kernel's mount ID for each held descriptor.
        val bytes = ByteArray(MAX_FD_INFO_BYTES + 1)
        val size = File("/proc/self/fdinfo/${descriptor.fd}").inputStream().use { input ->
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count == -1) break
                if (count <= 0) unavailable()
                size += count
            }
            size
        }
        if (size !in 1..MAX_FD_INFO_BYTES || bytes[size - 1] != '\n'.code.toByte()) unavailable()
        val mount = String(bytes, 0, size, Charsets.US_ASCII).lineSequence()
            .filter { it.startsWith("mnt_id:") }.singleOrNull()?.substringAfter(':')?.trim()
        if (mount.isNullOrEmpty() || mount.any { it !in '0'..'9' }) unavailable()
        Observation(mount.toLongOrNull()?.takeIf { it > 0L } ?: unavailable(), Identity(stat.st_dev, stat.st_ino))
    }

    private inline fun <T> descriptorIo(block: () -> T): T = try {
        block()
    } catch (_: ErrnoException) {
        unavailable()
    } catch (_: SecurityException) {
        unavailable()
    } catch (_: IllegalStateException) {
        unavailable()
    } catch (_: IOException) {
        unavailable()
    }

    private fun unavailable(): Nothing = throw IOException("Library folder descriptor ancestry is unavailable")
}
