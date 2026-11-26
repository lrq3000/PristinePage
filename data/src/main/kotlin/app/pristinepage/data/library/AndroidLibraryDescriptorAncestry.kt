package app.pristinepage.data.library

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.pristinepage.data.library.LibraryRootSeparation.Identity
import app.pristinepage.data.library.LibraryRootSeparation.Companion.MAX_CHAIN
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Paths

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

    /** Compare the actual immediate parent to its held descriptor, not to an ID derived from its displayed name. */
    fun requireDirectChild(child: ParcelFileDescriptor, parent: ParcelFileDescriptor) {
        val expected = observe(parent)
        val leaf = observe(child)
        if (leaf.identity == expected.identity || !leaf.sameFilesystem(expected)) unavailable()
        withParent(child) { if (observe(it) != expected || observe(parent) != expected) unavailable() }
    }

    /** Bind a literal child name without following a final symlink, independently of the provider's reported ID. */
    fun requireNamedDirectory(parent: ParcelFileDescriptor, name: String, held: ParcelFileDescriptor) = descriptorIo {
        val root = observe(parent)
        val seen = observe(held)
        if (root == seen || !root.sameFilesystem(seen)) unavailable()
        val path = childPath(parent, name)
        if (!OsConstants.S_ISDIR(Os.lstat(path).st_mode)) unavailable()
        // Android's public OsConstants lacks O_DIRECTORY; observe requires a directory after the no-follow open. Nonblocking prevents a replaced FIFO from hanging the check.
        val opened = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            ParcelFileDescriptor.dup(opened).use {
                if (observe(it) != seen || observe(parent) != root || observe(held) != seen) unavailable()
            }
        } finally { Os.close(opened) }
    }

    fun requireVacantName(parent: ParcelFileDescriptor, name: String) = descriptorIo {
        val root = observe(parent)
        try {
            Os.lstat(childPath(parent, name))
            unavailable()
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.ENOENT) throw failure
        }
        if (observe(parent) != root) unavailable()
    }

    /** A single physical entry is enough to refuse. A provider cursor alone cannot establish that none exist. */
    fun requirePhysicallyEmpty(directory: ParcelFileDescriptor) = descriptorIo {
        val seen = observe(directory)
        Files.newDirectoryStream(Paths.get("/proc/self/fd/${directory.fd}")).use {
            if (it.iterator().hasNext()) unavailable()
        }
        if (observe(directory) != seen) unavailable()
    }

    private fun childPath(parent: ParcelFileDescriptor, name: String): String {
        if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) unavailable()
        return "/proc/self/fd/${parent.fd}/$name"
    }

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
    } catch (_: DirectoryIteratorException) {
        unavailable()
    }

    private fun unavailable(): Nothing = throw IOException("Library folder descriptor ancestry is unavailable")
}
