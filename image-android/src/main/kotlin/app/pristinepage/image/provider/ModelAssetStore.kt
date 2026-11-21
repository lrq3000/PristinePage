package app.pristinepage.image.provider

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Byte identity and display estimates only. A runtime profile independently admits inference, including activation and workspace costs. */
internal data class ModelAssetSpec(
    val id: String,
    val version: Int,
    val byteCount: Long,
    val sha256: String,
    val peakInferenceBytes: Long?,
) {
    init {
        require(ID.matches(id) && version > 0)
        require(byteCount in 1..MAX_ASSET_BYTES && DIGEST.matches(sha256))
        require(peakInferenceBytes == null || peakInferenceBytes > 0)
    }

    private companion object {
        const val MAX_ASSET_BYTES = 512L * 1024 * 1024
        val ID = Regex("[a-z][a-z0-9-]{0,62}")
        val DIGEST = Regex("[0-9a-f]{64}")
    }
}

internal enum class ModelAssetStatus { ABSENT, INVALID, VERIFIED }

internal enum class ModelAssetFailure { NOT_VERIFIED, INVALID_ASSET, IN_USE, INSUFFICIENT_STORAGE }

internal class ModelAssetException(val reason: ModelAssetFailure) : IOException(reason.name)

/**
 * Synchronous storage boundary for one application-owned store and a dedicated, stable private parent.
 * Run on a worker dispatcher. No network, native sessions, document data or arbitrary user paths reach this store.
 */
internal class ModelAssetStore(private val root: Path) {
    private val leases = mutableMapOf<Path, Int>()
    private var pending: Path? = null

    @Synchronized
    fun status(spec: ModelAssetSpec): ModelAssetStatus {
        if (!hasRoot()) return ModelAssetStatus.ABSENT
        val path = target(spec)
        return attributes(path)?.let {
            if (it.isRegularFile && it.size() == spec.byteCount && verify(path, spec)) ModelAssetStatus.VERIFIED
            else ModelAssetStatus.INVALID
        } ?: ModelAssetStatus.ABSENT
    }

    /** The caller supplies a nonblocking cancellation check. The closed, verified asset is committed at the atomic move. */
    fun install(spec: ModelAssetSpec, checkpoint: () -> Unit = {}, source: () -> InputStream) {
        checkpoint()
        val destination = target(spec)
        val staged = root.resolve(".stage-${UUID.randomUUID()}")
        synchronized(this) {
            if (pending != null) throw ModelAssetException(ModelAssetFailure.IN_USE)
            when (status(spec)) {
                ModelAssetStatus.VERIFIED -> return
                ModelAssetStatus.INVALID -> throw ModelAssetException(ModelAssetFailure.INVALID_ASSET)
                ModelAssetStatus.ABSENT -> Unit
            }
            if (!hasRoot()) {
                try { Files.createDirectory(root) }
                catch (failure: FileAlreadyExistsException) { if (!hasRoot()) throw failure }
            }
            if (Files.getFileStore(root).usableSpace < spec.byteCount) {
                throw ModelAssetException(ModelAssetFailure.INSUFFICIENT_STORAGE)
            }
            pending = destination
        }
        var ownsStage = false
        try {
            checkpoint()
            // The stream may block or call another store operation; hold no store monitor while consuming it.
            FileChannel.open(staged, WRITE, CREATE_NEW, NOFOLLOW_LINKS).use { output ->
                ownsStage = true
                source().use { input ->
                    if (!readExact(input, spec.byteCount, checkpoint) { buffer, count ->
                            val bytes = ByteBuffer.wrap(buffer, 0, count)
                            while (bytes.hasRemaining()) {
                                checkpoint()
                                output.write(bytes)
                            }
                        }) {
                        throw ModelAssetException(ModelAssetFailure.INVALID_ASSET)
                    }
                }
                checkpoint()
                output.force(true)
            }
            if (!verify(staged, spec, checkpoint)) throw ModelAssetException(ModelAssetFailure.INVALID_ASSET)
            synchronized(this) {
                if (attributes(destination) != null) throw ModelAssetException(ModelAssetFailure.INVALID_ASSET)
                // Observe cancellation immediately before publication. A finished move remains installed even if the caller is later canceled.
                checkpoint()
                Files.move(staged, destination, ATOMIC_MOVE)
                ownsStage = false
            }
        } catch (failure: Throwable) {
            if (ownsStage) {
                try { Files.deleteIfExists(staged) }
                catch (cleanup: IOException) { failure.addSuppressed(cleanup) }
            }
            throw failure
        } finally {
            synchronized(this) { pending = null }
        }
    }

    @Synchronized
    fun acquire(spec: ModelAssetSpec): Lease {
        if (status(spec) != ModelAssetStatus.VERIFIED) throw ModelAssetException(ModelAssetFailure.NOT_VERIFIED)
        val path = target(spec)
        leases[path] = Math.addExact(leases[path] ?: 0, 1)
        return Lease(path) { release(path) }
    }

    @Synchronized
    fun remove(spec: ModelAssetSpec) {
        if (!hasRoot()) return
        val path = target(spec)
        if (pending == path || leases.containsKey(path)) throw ModelAssetException(ModelAssetFailure.IN_USE)
        val existing = attributes(path) ?: return
        if (!existing.isRegularFile) throw ModelAssetException(ModelAssetFailure.INVALID_ASSET)
        Files.delete(path)
    }

    /** Run on private store initialization, when no previous-process writer can still be using this directory. */
    @Synchronized
    fun removeInterruptedInstalls() {
        if (pending != null) throw ModelAssetException(ModelAssetFailure.IN_USE)
        if (!hasRoot()) return
        Files.newDirectoryStream(root).use { files ->
            for (path in files) if (STAGE.matches(path.fileName.toString()) && attributes(path)?.isRegularFile == true) {
                Files.deleteIfExists(path)
            }
        }
    }

    private fun verify(path: Path, spec: ModelAssetSpec, checkpoint: () -> Unit = {}): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val exact = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            readExact(input, spec.byteCount, checkpoint) { bytes, count -> digest.update(bytes, 0, count) }
        }
        return exact && digest.digest().toHexString() == spec.sha256
    }

    private fun readExact(input: InputStream, size: Long, checkpoint: () -> Unit, consume: (ByteArray, Int) -> Unit): Boolean {
        val buffer = ByteArray(minOf(size, 64L * 1024).toInt())
        var remaining = size
        while (remaining > 0) {
            checkpoint()
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count <= 0) return false
            consume(buffer, count)
            remaining -= count
        }
        checkpoint()
        return input.read() == -1
    }

    private fun target(spec: ModelAssetSpec): Path = root.resolve("${spec.id}-v${spec.version}-${spec.sha256}.asset")

    private fun hasRoot(): Boolean {
        val entry = attributes(root) ?: return false
        if (!entry.isDirectory) throw ModelAssetException(ModelAssetFailure.INVALID_ASSET)
        return true
    }

    private fun attributes(path: Path): BasicFileAttributes? =
        try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { null }

    @Synchronized
    private fun release(path: Path) {
        val count = checkNotNull(leases[path])
        if (count == 1) leases.remove(path) else leases[path] = count - 1
    }

    internal class Lease(val path: Path, release: () -> Unit) : AutoCloseable {
        private val onClose = AtomicReference<(() -> Unit)?>(release)

        override fun close() {
            onClose.getAndSet(null)?.invoke()
        }
    }

    private companion object {
        val STAGE = Regex("\\.stage-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
