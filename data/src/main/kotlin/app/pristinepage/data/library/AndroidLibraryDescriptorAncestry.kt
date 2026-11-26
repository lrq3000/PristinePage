package app.pristinepage.data.library

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.pristinepage.data.library.LibraryRootSeparation.Identity
import app.pristinepage.data.library.LibraryRootSeparation.Companion.MAX_CHAIN
import java.io.File
import java.io.IOException

/** Read-only observation, not a write permit. Callers keep all three descriptors open and undetached and separately bind provider locators to them. Sequential walks do not exclude a concurrent rename; a writer must also hold stable, cooperative topology ownership through its operation. */
internal object AndroidLibraryDescriptorAncestry {
    private const val MAX_FD_INFO_BYTES = 1_024

    fun admit(left: ParcelFileDescriptor, right: ParcelFileDescriptor, anchor: ParcelFileDescriptor,
        checkpoint: () -> Unit = {},
    ): LibraryRootSeparation {
        checkpoint()
        val expected = observe(anchor)
        return LibraryRootSeparation.admit({ chain(left, anchor, expected, checkpoint) }, { chain(right, anchor, expected, checkpoint) })
    }

    private fun chain(leaf: ParcelFileDescriptor, anchor: ParcelFileDescriptor, expected: Observation,
        checkpoint: () -> Unit,
    ): List<Identity> {
        checkpoint()
        if (observe(anchor) != expected) unavailable()
        val result = ArrayList<Identity>()
        val seen = HashSet<Identity>()
        fun visit(current: ParcelFileDescriptor) {
            checkpoint()
            val at = observe(current)
            if (!expected.sameFilesystem(at) || !seen.add(at.identity)) unavailable()
            result += at.identity
            if (at == expected) return
            if (result.size == MAX_CHAIN) unavailable()
            withParent(current, ::visit)
        }
        visit(leaf)
        checkpoint()
        return result
    }

    /** Both walks must independently arrive at the same directory. The descriptors support metadata proof only and never escape this callback. */
    fun <T> withDerivedAnchor(left: ParcelFileDescriptor, right: ParcelFileDescriptor, hops: Pair<Int, Int>,
        checkpoint: () -> Unit = {}, block: (requireCurrent: () -> Unit) -> T,
    ): T {
        if (hops.first !in 1 until MAX_CHAIN || hops.second !in 1 until MAX_CHAIN) unavailable()
        checkpoint()
        val a = observe(left)
        val b = observe(right)
        if (a == b || !a.sameFilesystem(b)) unavailable()

        fun <R> walk(leaf: ParcelFileDescriptor, steps: Int, start: Observation,
            use: (ParcelFileDescriptor, Observation, List<Identity>) -> R,
        ): R {
            val seen = hashSetOf(start.identity)
            val first = arrayListOf(start.identity)
            var owner: ParcelFileDescriptor? = null
            var observed = start
            return try {
                repeat(steps) {
                    checkpoint()
                    val old = owner
                    val parent = openParent(old ?: leaf)
                    owner = parent
                    old?.close()
                    observed = observe(parent)
                    if (!start.sameFilesystem(observed) || !seen.add(observed.identity)) unavailable()
                    first += observed.identity
                }
                use(checkNotNull(owner), observed, first)
            } finally {
                owner?.close()
            }
        }

        return walk(left, hops.first, a) { leftAnchor, anchor, leftBefore ->
            walk(right, hops.second, b) { rightAnchor, rightObserved, rightBefore ->
                if (anchor != rightObserved) unavailable()
                val proof = LibraryRootSeparation.admitObserved(leftBefore, rightBefore,
                    { chain(left, leftAnchor, anchor, checkpoint) }, { chain(right, rightAnchor, anchor, checkpoint) })
                var live = true
                fun requireCurrent() {
                    if (!live) unavailable()
                    proof.requireCurrent()
                }
                try {
                    requireCurrent()
                    block(::requireCurrent).also { requireCurrent() }
                } finally { live = false }
            }
        }
    }

    data class Observation(val mount: Long, val identity: Identity)

    private fun Observation.sameFilesystem(other: Observation) = mount == other.mount && identity.device == other.identity.device

    // Follow only the live directory descriptor, subject to normal OS permissions; never resolve a URI or enumerate the observed ancestor.
    private fun openParent(directory: ParcelFileDescriptor): ParcelFileDescriptor = descriptorIo {
        ParcelFileDescriptor.open(File("/proc/self/fd/${directory.fd}/.."), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun <T> withParent(directory: ParcelFileDescriptor, use: (ParcelFileDescriptor) -> T): T = openParent(directory).use(use)

    fun observe(descriptor: ParcelFileDescriptor): Observation = descriptorIo {
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
