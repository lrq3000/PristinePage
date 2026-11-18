package app.pristinepage.data.files

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.model.requireOpaqueId
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class DerivedKind { RENDER, EXPORT }

data class DerivedFileTarget(val kind: DerivedKind, val objectId: String, val snapshotId: String? = null) {
    init {
        requireOpaqueId(objectId)
        require((kind == DerivedKind.EXPORT) == (snapshotId != null)) { "Invalid derived target binding" }
        snapshotId?.let(::requireOpaqueId)
    }
}

/** Closed-byte receipt, not an encoder path or a lease. Receipts can be used by another instance for the same canonical root. */
class StagedDerived internal constructor(
    private val root: File,
    val transactionId: String,
    val target: DerivedFileTarget,
    val byteSha256: String,
    val byteCount: Long,
) {
    internal fun requireStore(root: File) = require(this.root == root) { "Mismatched derived store" }

    internal fun matches(other: StagedDerived): Boolean =
        root == other.root && transactionId == other.transactionId && target == other.target &&
            byteSha256 == other.byteSha256 && byteCount == other.byteCount
}

/** Authenticated file contents only. Readability does not prove completion of the seal's durability barriers. */
class ObservedDerivedSeal internal constructor(val staged: StagedDerived, metadata: ByteArray, val sealSha256: String) {
    private val ownedMetadata = metadata.copyOf()
    val metadata: ByteArray get() = ownedMetadata.copyOf()

    internal fun matches(other: ObservedDerivedSeal): Boolean =
        staged.matches(other.staged) && sealSha256 == other.sealSha256 && ownedMetadata.contentEquals(other.ownedMetadata)
}

/** Receipt issued only after the seal file and supported directory barriers return successfully. */
class SealedDerived internal constructor(internal val observation: ObservedDerivedSeal) {
    val staged: StagedDerived get() = observation.staged
    val metadata: ByteArray get() = observation.metadata
    val sealSha256: String get() = observation.sealSha256
}

@Serializable
private data class DerivedSeal(
    val version: Int,
    val transactionId: String,
    val kind: DerivedKind,
    val objectId: String,
    val snapshotId: String?,
    val byteSha256: String,
    val byteCount: Long,
    val metadataBase64: String,
)

/** A bounded writer failure is still an IOException to existing callers. */
internal class DerivedByteLimitExceeded : IOException("Derived byte admission limit exceeded")

/**
 * File-only checkpoint protocol. The caller journals intent first and retains exclusive transaction and target ownership, including across instances/processes, for the entire operation. Run IO outside Room. An atomic move is not a race-proof no-replace primitive: only that caller precondition and the preexisting-target check make replay non-clobbering.
 *
 * No file operation commits metadata or makes an artifact ready. The containing directory and root are stable and app-owned. Unsupported directory sync is reported separately; this is not a guarantee against concurrent same-UID mutation or hardware power loss.
 */
class DerivedFiles internal constructor(
    root: File,
    private val directorySync: DirectorySync,
    private val forceSeal: (FileChannel) -> Unit,
) {
    @JvmOverloads
    constructor(root: File, directorySync: DirectorySync = PlatformDirectorySync) : this(root, directorySync, { it.force(true) })

    private val paths = PrivateStorePaths(root)

    var directorySyncSupported: Boolean = true
        private set

    /** The encoder borrows a synchronous, thread-confined stream only; it never receives a writable File or descriptor. Close ends the borrow, not the owner's force/close sequence. */
    @Synchronized
    fun stage(
        transactionId: String,
        target: DerivedFileTarget,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
        checkpoint: () -> Unit = {},
    ): StagedDerived {
        requireOpaqueId(transactionId)
        require(maxBytes > 0) { "Invalid derived byte admission limit" }
        checkpoint()
        val file = stagingFile(transactionId)
        finalFile(target)
        // The stage path may already be absent after a move. Its retained seal still makes this transaction unavailable for a fresh write.
        require(attributes(sealFile(transactionId)) == null) { "Derived attempt already sealed" }
        val parent = checkNotNull(file.parentFile)
        paths.createDirectories(parent, ::syncDirectory)
        checkpoint()
        val staged = FileChannel.open(file.toPath(), CREATE_NEW, WRITE).use { channel ->
            val output = BoundedWriter(channel, maxBytes, checkpoint)
            try {
                writer(output)
            } finally {
                output.close()
            }
            output.requireComplete()
            checkpoint()
            channel.force(true)
            StagedDerived(paths.root, transactionId, target, output.byteSha256(), output.byteCount)
        }
        syncDirectory(parent)
        checkpoint()
        return staged
    }

    /** Seal only a closed, reverified payload. The caller supplies bounded canonical metadata; its domain meaning is checked by the later consumer. */
    @JvmOverloads
    @Synchronized
    fun seal(staged: StagedDerived, metadata: ByteArray, checkpoint: () -> Unit = {}): SealedDerived {
        staged.requireStore(paths.root)
        require(metadata.size in 1..METADATA_LIMIT) { "Invalid derived metadata size" }
        val ownedMetadata = metadata.copyOf()
        checkpoint()
        val payload = stagingFile(staged.transactionId)
        verifyPayload(payload, attributes(payload), staged, checkpoint)
        val target = staged.target
        val record = DerivedSeal(
            SEAL_VERSION, staged.transactionId, target.kind, target.objectId, target.snapshotId,
            staged.byteSha256, staged.byteCount, Base64.getEncoder().encodeToString(ownedMetadata),
        )
        val bytes = encode(record)
        val file = sealFile(staged.transactionId)
        checkpoint()
        FileChannel.open(file.toPath(), CREATE_NEW, WRITE).use { channel ->
            writeAll(channel, ByteBuffer.wrap(bytes))
            forceSeal(channel)
        }
        syncDirectory(checkNotNull(file.parentFile))
        checkpoint()
        return SealedDerived(ObservedDerivedSeal(staged, ownedMetadata, sha256(bytes)))
    }

    /** Read-only validation for a known intent; a missing seal returns null. The result cannot advance SEALED or authorize move. */
    @JvmOverloads
    @Synchronized
    fun inspectSeal(transactionId: String, expectedSealSha256: String?, checkpoint: () -> Unit = {}): ObservedDerivedSeal? =
        inspect(transactionId, expectedSealSha256, checkpoint, durable = false)?.seal

    /** Observe an existing final outside Room under the caller's stable store/target ownership. The callbacks borrow independent metadata snapshots and readback must not write or retain the file. No durability or publication authority is returned. */
    @Synchronized
    internal fun inspectBoundFinal(
        transactionId: String, expectedSealSha256: String, target: DerivedFileTarget, maxBytes: Long,
        checkpoint: () -> Unit, authenticate: (String, Long, ByteArray) -> Unit, readback: (File, ByteArray) -> Unit,
    ): ObservedDerivedSeal? {
        require(maxBytes > 0) { "Invalid derived byte admission limit" }
        return inspect(transactionId, expectedSealSha256, checkpoint, durable = false,
            beforePayloads = { staged, metadata ->
                require(staged.target == target) { "Derived target binding mismatch" }
                if (staged.byteCount > maxBytes) throw DerivedByteLimitExceeded()
                authenticate(staged.byteSha256, staged.byteCount, metadata.copyOf())
            }, requireFinal = true,
            readback = { file, metadata -> readback(file, metadata.copyOf()) },
        )?.seal
    }

    /** Under the caller's exact exclusive lease, authenticate the known intent and re-establish the seal barriers before returning an actionable receipt. A null expected hash is only for a lagging journal. */
    @JvmOverloads
    @Synchronized
    fun recover(transactionId: String, expectedSealSha256: String?, checkpoint: () -> Unit = {}): SealedDerived? =
        inspect(transactionId, expectedSealSha256, checkpoint, durable = true)?.let { SealedDerived(it.seal) }

    /** Borrow the exact closed private payload for synchronous, read-only semantic verification. seal() checks it again before writing anything. */
    @Synchronized
    internal fun <T> readStaged(staged: StagedDerived, checkpoint: () -> Unit, readback: (File) -> T): T {
        staged.requireStore(paths.root)
        val payload = stagingFile(staged.transactionId)
        verifyPayload(payload, attributes(payload), staged, checkpoint)
        checkpoint()
        val result = readback(payload)
        checkpoint()
        return result
    }

    /** Check the canonical header against caller-owned identities before resolving/opening its payload paths. Then check every surviving copy and re-establish seal durability. */
    @Synchronized
    internal fun recoverBound(
        transactionId: String, expectedSealSha256: String?, target: DerivedFileTarget, maxBytes: Long, requireFinal: Boolean,
        checkpoint: () -> Unit, authenticate: (String, Long, ByteArray) -> Unit, readback: (File, ByteArray) -> Unit,
    ): SealedDerived? = inspect(transactionId, expectedSealSha256, checkpoint, durable = true,
        beforePayloads = { staged, metadata ->
            require(staged.target == target) { "Derived target binding mismatch" }
            if (staged.byteCount > maxBytes) throw DerivedByteLimitExceeded()
            authenticate(staged.byteSha256, staged.byteCount, metadata)
        }, requireFinal = requireFinal, readback = readback)?.let { SealedDerived(it.seal) }

    /** Match the receipt, check every present copy and re-establish the seal barriers before touching the final path. Reuse a verified existing final without rewriting or removing either copy. */
    @JvmOverloads
    @Synchronized
    fun move(sealed: SealedDerived, checkpoint: () -> Unit = {}): File =
        moveWithFence(sealed, checkpoint) {}

    /** The metadata-only authorization callback fences destination setup after seal re-verification, and fences rename or final reuse again after setup. */
    @Synchronized
    internal fun moveWithFence(sealed: SealedDerived, checkpoint: () -> Unit, authorize: () -> Unit): File {
        val staged = sealed.staged
        staged.requireStore(paths.root)
        val observed = checkNotNull(inspect(staged.transactionId, sealed.sealSha256, checkpoint, durable = true, expected = sealed.observation)) { "Derived seal is missing" }
        val targetParent = checkNotNull(observed.target.parentFile)
        val stageParent = checkNotNull(observed.stage.parentFile)
        authorize()
        paths.createDirectories(targetParent, ::syncDirectory)
        checkpoint()
        authorize()
        checkpoint()
        if (!observed.finalPresent) {
            val target = finalFile(staged.target)
            require(attributes(target) == null) { "Derived target appeared during move" }
            // No non-atomic fallback. If the platform cannot do this move, the closed sealed stage remains recoverable.
            Files.move(stagingFile(staged.transactionId).toPath(), target.toPath(), ATOMIC_MOVE)
        }
        syncDirectory(targetParent)
        syncDirectory(stageParent)
        if (!observed.finalPresent) {
            val target = finalFile(staged.target)
            verifyPayload(target, attributes(target), staged, checkpoint)
        }
        checkpoint()
        return observed.target
    }

    /** Private readback location only; not evidence of file existence, integrity, an ownership pin or readiness. */
    fun finalFile(target: DerivedFileTarget): File = paths.beneath(when (target.kind) {
        DerivedKind.RENDER -> "renders/${target.objectId}/page.bin"
        DerivedKind.EXPORT -> "exports/${checkNotNull(target.snapshotId)}/${target.objectId}.bin"
    })

    private fun inspect(
        transactionId: String,
        expectedSealSha256: String?,
        checkpoint: () -> Unit,
        durable: Boolean,
        expected: ObservedDerivedSeal? = null,
        beforePayloads: ((StagedDerived, ByteArray) -> Unit)? = null,
        requireFinal: Boolean = false,
        readback: ((File, ByteArray) -> Unit)? = null,
    ): Observation? {
        requireOpaqueId(transactionId)
        expectedSealSha256?.let(::requireSha256)
        checkpoint()
        val file = sealFile(transactionId)
        val attrs = attributes(file) ?: return null
        require(attrs.isRegularFile && attrs.size() in 1L..SEAL_LIMIT.toLong()) { "Invalid derived seal extent" }
        // A readable seal may be left behind by a failed force. For durable recovery, force the same descriptor that supplied the authenticated bytes, without creation, truncation or rewriting.
        val options = if (durable) arrayOf<OpenOption>(READ, WRITE, NOFOLLOW_LINKS) else arrayOf<OpenOption>(READ, NOFOLLOW_LINKS)
        checkpoint()
        val observed = FileChannel.open(file.toPath(), *options).use { channel ->
            val bytes = readSeal(channel, attrs.size().toInt(), checkpoint)
            val observed = verifySealAndPayloads(transactionId, expectedSealSha256, bytes, checkpoint, beforePayloads, requireFinal, readback)
            require(expected == null || expected.matches(observed.seal)) { "Derived seal binding mismatch" }
            if (durable) {
                checkpoint()
                forceSeal(channel)
            }
            observed
        }
        if (durable) syncDirectory(checkNotNull(file.parentFile))
        checkpoint()
        return observed
    }

    private fun readSeal(channel: FileChannel, length: Int, checkpoint: () -> Unit): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            checkpoint()
            require(channel.read(buffer) > 0) { "Truncated derived seal" }
        }
        require(channel.read(ByteBuffer.allocate(1)) == -1) { "Extended derived seal" }
        return buffer.array()
    }

    private fun verifySealAndPayloads(
        transactionId: String,
        expectedSealSha256: String?,
        bytes: ByteArray,
        checkpoint: () -> Unit,
        beforePayloads: ((StagedDerived, ByteArray) -> Unit)?,
        requireFinal: Boolean,
        readback: ((File, ByteArray) -> Unit)?,
    ): Observation {
        val hash = sha256(bytes)
        require(expectedSealSha256 == null || expectedSealSha256 == hash) { "Derived seal integrity mismatch" }
        val record = JSON.decodeFromString(DerivedSeal.serializer(), bytes.toString(Charsets.UTF_8))
        require(record.version == SEAL_VERSION && record.transactionId == transactionId) { "Unknown or mismatched derived seal" }
        require(record.byteCount > 0) { "Empty derived payload in seal" }
        requireSha256(record.byteSha256)
        val target = DerivedFileTarget(record.kind, record.objectId, record.snapshotId)
        val metadata = Base64.getDecoder().decode(record.metadataBase64)
        require(metadata.size in 1..METADATA_LIMIT) { "Invalid derived metadata size" }
        // Requiring our exact encoding also rejects duplicate keys, noncanonical Base64 and malformed UTF-8 repaired by decoding.
        val canonical = record.copy(metadataBase64 = Base64.getEncoder().encodeToString(metadata))
        require(bytes.contentEquals(encode(canonical))) { "Noncanonical derived seal" }
        val staged = StagedDerived(paths.root, transactionId, target, record.byteSha256, record.byteCount)
        beforePayloads?.invoke(staged, metadata)
        checkpoint()
        val stage = stagingFile(transactionId)
        checkpoint()
        val final = finalFile(target)
        val stageAttrs = attributes(stage)
        val finalAttrs = attributes(final)
        require(stageAttrs != null || finalAttrs != null) { "Sealed derived payload is missing" }
        require(!requireFinal || finalAttrs != null) { "Moved derived payload is missing" }
        if (stageAttrs != null) verifyPayload(stage, stageAttrs, staged, checkpoint)
        if (finalAttrs != null) verifyPayload(final, finalAttrs, staged, checkpoint)
        if (readback != null) {
            checkpoint()
            // Both copies already matched; decode one, then re-resolve both paths. Readback cannot add or remove a copy, or change its bytes.
            readback(if (finalAttrs != null) final else stage, metadata)
            checkpoint()
            verifyUnchangedCopy(stagingFile(transactionId), stageAttrs != null, staged, checkpoint)
            checkpoint()
            verifyUnchangedCopy(finalFile(target), finalAttrs != null, staged, checkpoint)
        }
        return Observation(ObservedDerivedSeal(staged, metadata, hash), stage, final, finalAttrs != null)
    }

    private fun verifyUnchangedCopy(file: File, wasPresent: Boolean, staged: StagedDerived, checkpoint: () -> Unit) {
        val attrs = attributes(file)
        require((attrs != null) == wasPresent) { "Derived copy presence changed during readback" }
        if (wasPresent) verifyPayload(file, attrs, staged, checkpoint)
    }

    private fun verifyPayload(file: File, attrs: BasicFileAttributes?, staged: StagedDerived, checkpoint: () -> Unit) {
        require(attrs != null && attrs.isRegularFile && attrs.size() == staged.byteCount) { "Derived payload extent mismatch" }
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = staged.byteCount
        checkpoint()
        FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            while (remaining > 0) {
                checkpoint()
                buffer.clear().limit(minOf(remaining, BUFFER_SIZE.toLong()).toInt())
                val read = channel.read(buffer)
                require(read > 0) { "Truncated derived payload" }
                digest.update(buffer.array(), 0, read)
                remaining -= read.toLong()
            }
            buffer.clear().limit(1)
            require(channel.read(buffer) == -1 && hex(digest.digest()) == staged.byteSha256) { "Derived payload integrity mismatch" }
        }
    }

    private fun stagingFile(id: String): File = paths.beneath("staging/$id/derived.part")
    private fun sealFile(id: String): File = paths.beneath("staging/$id/derived-seal.json")

    private fun syncDirectory(directory: File) {
        if (!directorySync.sync(directory)) directorySyncSupported = false
    }

    private data class Observation(val seal: ObservedDerivedSeal, val stage: File, val target: File, val finalPresent: Boolean)

    private class BoundedWriter(private val channel: FileChannel, private val limit: Long, private val checkpoint: () -> Unit) : OutputStream() {
        private val owner = Thread.currentThread()
        private val digest = MessageDigest.getInstance("SHA-256")
        private val single = ByteArray(1)
        private var closed = false
        private var writing = false
        private var failure: Exception? = null
        var byteCount = 0L
            private set

        override fun write(b: Int) = guardWrite {
            single[0] = b.toByte()
            writeBytes(single, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) = guardWrite { writeBytes(b, off, len) }

        private inline fun guardWrite(write: () -> Unit) {
            requireOwner()
            failure?.let { throw it }
            if (writing) {
                val error = IOException("Derived stream cannot be written reentrantly")
                failure = error
                throw error
            }
            writing = true
            try {
                if (closed) throw IOException("Derived stream is closed")
                write()
            } catch (error: Exception) {
                // A codec that catches an output failure must not be able to return a sealable prefix.
                if (failure == null) failure = error
                throw error
            } finally {
                writing = false
            }
        }

        private fun writeBytes(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
            if (len.toLong() > limit - byteCount) throw DerivedByteLimitExceeded()
            var offset = off
            val end = off + len
            while (offset < end) {
                checkpoint()
                // The callback may swallow a rejected reentrant write. Refuse before this chunk can reach the file.
                failure?.let { throw it }
                if (closed) throw IOException("Derived stream is closed")
                val size = minOf(end - offset, BUFFER_SIZE)
                writeAll(channel, ByteBuffer.wrap(b, offset, size))
                digest.update(b, offset, size)
                byteCount += size.toLong()
                offset += size
            }
        }

        override fun close() {
            requireOwner()
            closed = true
        }

        fun requireComplete() {
            failure?.let { throw it }
            require(byteCount > 0) { "Empty derived payload" }
        }

        fun byteSha256(): String = hex(digest.digest())

        private fun requireOwner() {
            if (Thread.currentThread() !== owner) throw IOException("Derived stream used outside its owner thread")
        }
    }

    private companion object {
        const val SEAL_VERSION = 1
        const val METADATA_LIMIT = 524_288
        const val SEAL_LIMIT = METADATA_LIMIT * 2
        const val BUFFER_SIZE = 65_536
        val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }

        fun encode(seal: DerivedSeal): ByteArray =
            JSON.encodeToString(DerivedSeal.serializer(), seal).toByteArray(Charsets.UTF_8)

        // Do not confuse an inaccessible or non-regular path with a missing entry; an unknown target cannot authorize a move.
        fun attributes(file: File): BasicFileAttributes? =
            try { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) { null }

        fun writeAll(channel: FileChannel, buffer: ByteBuffer) {
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw IOException("Derived file write made no progress")
            }
        }

        fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
