package app.pristinepage.image.ocr

import android.app.Application
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.S_IWGRP
import android.system.OsConstants.S_IWOTH
import app.pristinepage.core.ocr.OcrLanguagePacks
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Own an immutable catalog below a dedicated app-private parent. All writers must cooperate through this owner; an arbitrary external or same-UID writable directory is not an input capability. A published set has no replace/remove operation and stays owned through native close. */
internal class PrivateOcrPackOwner private constructor(
    parent: Path,
    pins: List<VerifiedOcrPackDirectory.Pin>,
    private val ownedLeaf: (Path) -> Boolean,
    private val ownedDirectory: (Path) -> Boolean,
) {
    private val parent = parent.toAbsolutePath().normalize()
    private val pins = pins.sortedBy { it.identity.code }.also {
        require(it.size in 1..32 && it.distinctBy { pin -> pin.identity.code }.size == it.size && it.sumOf { pin -> pin.bytes } <= MAX_SET)
    }
    private val all = OcrLanguagePacks(this.pins.map { it.identity })
    private val identities = all.ordered.toSet()
    private val names = this.pins.map { "${it.identity.code}.traineddata" }.toSet()
    private val key = MessageDigest.getInstance("SHA-256").digest(this.pins.joinToString("\n", prefix = "pristinepage-ocr-packs-v1\n") {
        "${it.identity.code}\t${it.bytes}\t${it.identity.sha256}"
    }.encodeToByteArray()).toHexString()
    private val destination = this.parent.resolve("ocr-packs-$key")
    private val installed = VerifiedOcrPackDirectory(destination, this.pins)
    private val posix = "posix" in this.parent.fileSystem.supportedFileAttributeViews()

    fun resolveOrdered(codes: List<String>, checkpoint: () -> Unit = {}): OcrLanguagePacks? =
        readInstalled(checkpoint) { installed.resolveOrdered(codes, checkpoint)?.takeIf { installed.verifies(all, checkpoint) } }

    /** The path is stable because no cooperating writer can touch a published set. The caller borrows it only for the verified engine session. */
    fun acquire(packs: OcrLanguagePacks, checkpoint: () -> Unit = {}): Path? = readInstalled(checkpoint) {
        // Native receives the whole directory, not open descriptors for only the selected inputs.
        destination.takeIf { packs.ordered.all(identities::contains) && installed.verifies(all, checkpoint) }
    }

    /** Reuse a verified commit, never replace one. The factory opens only preselected catalog pack codes and may not mutate the private parent. */
    fun install(checkpoint: () -> Unit = {}, open: (String) -> InputStream): Boolean {
        checkpoint()
        if (!installing.compareAndSet(false, true)) return false
        try {
            if (!physicalDirectory(parent)) return false
            val lock = parent.resolve(".ocr-packs-$key.lock")
            // The process gate prevents another channel in this process from interfering with a live OS lock.
            if (Files.exists(lock, NOFOLLOW_LINKS) && !ordinaryLeaf(lock)) return false
            FileChannel.open(lock, setOf<OpenOption>(CREATE, WRITE, NOFOLLOW_LINKS), *mode(privateFileMode)).use { channel ->
                if (!ordinaryLeaf(lock)) return false
                val ownership = channel.tryLock() ?: return false
                ownership.use {
                    if (!physicalDirectory(parent)) return false
                    if (Files.exists(destination, NOFOLLOW_LINKS)) return acquire(all, checkpoint) != null
                    // Android's NIO provider refuses getFileStore even for our own private directory. The path-local space estimate is only an admission hint; writes still handle I/O failure.
                    if (parent.toFile().usableSpace < pins.sumOf { it.bytes }) return false
                    return stageAndPublish(checkpoint, open)
                }
            }
        } catch (_: IOException) {
            checkpoint()
            return false
        } catch (_: DirectoryIteratorException) {
            checkpoint()
            return false
        } finally {
            installing.set(false)
        }
    }

    private fun stageAndPublish(checkpoint: () -> Unit, open: (String) -> InputStream): Boolean {
        val stage = Files.createTempDirectory(parent, ".ocr-packs-stage-", *mode(privateDirectoryMode))
        val data = stage.resolve("tessdata")
        val created = ArrayList<Path>()
        var moved = false
        try {
            Files.createDirectory(data, *mode(privateDirectoryMode))
            val buffer = ByteArray(64 * 1024)
            for ((pack, bytes) in pins) {
                checkpoint()
                val path = data.resolve("${pack.code}.traineddata")
                FileChannel.open(path, setOf<OpenOption>(WRITE, CREATE_NEW, NOFOLLOW_LINKS), *mode(privateFileMode)).use { output ->
                    created.add(path)
                    open(pack.code).use { input ->
                        var remaining = bytes
                        while (remaining != 0L) {
                            checkpoint()
                            val requested = minOf(remaining, buffer.size.toLong()).toInt()
                            val n = input.read(buffer, 0, requested)
                            if (n !in 1..requested) return false
                            val part = ByteBuffer.wrap(buffer, 0, n)
                            while (part.hasRemaining()) {
                                checkpoint()
                                output.write(part)
                            }
                            remaining -= n
                        }
                        if (input.read() != -1) return false
                        output.force(true)
                    }
                }
            }
            if (!admittedInventory(stage, checkpoint) ||
                !VerifiedOcrPackDirectory(stage, pins).verifies(all, checkpoint) || Files.exists(destination, NOFOLLOW_LINKS)
            ) return false
            checkpoint()
            // No copy fallback and no REPLACE_EXISTING. The cooperating writers serialize the absence check above with this move.
            Files.move(stage, destination, ATOMIC_MOVE)
            moved = true
            // Cancellation after the commit does not remove a published set; reopen validates it again.
            return acquire(all, checkpoint) != null
        } finally {
            if (!moved) {
                // Only this invocation's known staging entries, never discovered old stages or installed output.
                for (leaf in created.asReversed()) quietDelete(leaf)
                quietDelete(data)
                quietDelete(stage)
            }
        }
    }

    private fun <T> readInstalled(checkpoint: () -> Unit, read: () -> T?): T? {
        checkpoint()
        val result = try {
            if (!admittedInventory(destination, checkpoint)) null else read()
        } catch (_: IOException) { null } catch (_: DirectoryIteratorException) { null }
        checkpoint()
        return result
    }

    private fun admittedInventory(root: Path, checkpoint: () -> Unit): Boolean {
        val data = root.resolve("tessdata")
        if (!physicalDirectory(parent) || !physicalDirectory(root) || !physicalDirectory(data)) return false
        Files.newDirectoryStream(root).use { contents ->
            val it = contents.iterator()
            if (!it.hasNext() || it.next() != data || it.hasNext()) return false
        }
        Files.newDirectoryStream(data).use { contents ->
            val remaining = names.toMutableSet()
            for (path in contents) {
                checkpoint()
                if (!remaining.remove(path.fileName.toString()) || !ordinaryLeaf(path)) return false
            }
            return remaining.isEmpty()
        }
    }

    private fun ordinaryLeaf(path: Path): Boolean = Files.isRegularFile(path, NOFOLLOW_LINKS) && ownedLeaf(path)

    private fun physicalDirectory(path: Path): Boolean =
        Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == path && ownedDirectory(path)

    private fun mode(attribute: FileAttribute<*>): Array<FileAttribute<*>> = if (posix) arrayOf(attribute) else emptyArray()

    private fun quietDelete(path: Path) {
        try { Files.deleteIfExists(path) } catch (_: IOException) { /* Incomplete scratch is not discoverable as an installed set. */ }
    }

    companion object {
        private const val MAX_SET = 64L * 1024 * 1024
        private val installing = AtomicBoolean(false)
        private val privateDirectoryMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val privateFileMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

        /** Do not accept a caller-provided destination. Resolve the exact files child of the Android-issued package data directory. Call off Main. */
        fun forApplication(context: Context, pins: List<VerifiedOcrPackDirectory.Pin>): PrivateOcrPackOwner? {
            val app = context.applicationContext as? Application ?: return null
            return try {
                val sandbox = Paths.get(app.applicationInfo.dataDir).toRealPath()
                val files = app.filesDir.toPath().toRealPath()
                if (files != sandbox.resolve("files") || !androidOwned(sandbox) ||
                    !androidOwned(files, platformFilesDirectory = true)
                ) return null
                val parent = files.resolve("ocr-verified-packs-v1")
                try { Files.createDirectory(parent, privateDirectoryMode) } catch (_: FileAlreadyExistsException) { }
                if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || parent.toRealPath() != parent || !androidOwned(parent)) return null
                PrivateOcrPackOwner(parent, pins, { androidOwned(it, singleLink = true) }, ::androidOwnedDirectory)
            } catch (_: IOException) { null }
            catch (_: UnsupportedOperationException) { null }
        }

        private fun androidOwnedDirectory(path: Path): Boolean = androidOwned(path)

        private fun androidOwned(path: Path, singleLink: Boolean = false, platformFilesDirectory: Boolean = false): Boolean = try {
            val stat = Os.lstat(path.toString())
            val uid = Os.getuid()
            // Android can create the fixed "files" directory as 0771. Admit its group write bit only for this app's own UID/GID, never for the pack tree or its lock.
            val appGroup = platformFilesDirectory && stat.st_gid == uid && Os.getgid() == uid
            stat.st_uid == uid && (stat.st_mode and S_IWOTH) == 0 &&
                (appGroup || (stat.st_mode and S_IWGRP) == 0) && (!singleLink || stat.st_nlink == 1L)
        } catch (_: ErrnoException) { false }
    }
}
