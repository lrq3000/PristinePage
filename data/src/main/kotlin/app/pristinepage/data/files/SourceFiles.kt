package app.pristinepage.data.files

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.model.requireOpaqueId
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class StagedSource internal constructor(val transactionId: String, val sourceBlobId: String, val byteSha256: String, val byteCount: Long, val stagingFile: File)

class SealedSource internal constructor(val staged: StagedSource, metadata: ByteArray, val sealSha256: String) {
    private val ownedMetadata = metadata.copyOf()
    val metadata: ByteArray get() = ownedMetadata.copyOf()
}

@Serializable private data class SourceSeal(val version: Int = 1, val transactionId: String, val sourceBlobId: String, val byteSha256: String, val byteCount: Long, val metadataBase64: String)

/** Only private store bytes live here. The repository must persist intent and hold its fenced journal lease before calling this protocol. Filesystem completion never makes a page visible; the subsequent Room transaction does that. Failed/partial files are retained for explicit recovery, never auto-deleted. */
class SourceFiles @JvmOverloads constructor(root: File, private val directorySync: DirectorySync = PlatformDirectorySync) {
    private val root = root.canonicalFile
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val inputLimit = 100L * 1024 * 1024
    private val metadataLimit = 524288

    /** Directory fsync is best effort on platforms which cannot open directory descriptors. Payload/seal forces still fail normally; this flag is not a power-loss guarantee. */
    var directorySyncSupported: Boolean = true
        private set

    @Synchronized fun stage(transactionId: String, sourceBlobId: String, input: InputStream, maxBytes: Long): StagedSource {
        validateId(transactionId); validateId(sourceBlobId)
        require(maxBytes in 1..inputLimit) { "Invalid encoded-byte admission limit" }
        val file = staging(transactionId)
        createDirectories(checkNotNull(file.parentFile))
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        input.use { source ->
            FileChannel.open(file.toPath(), CREATE_NEW, WRITE).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    var length = source.read(buffer)
                    if (length < 0) break
                    if (length == 0) {
                        val single = source.read()
                        if (single < 0) break
                        buffer[0] = single.toByte(); length = 1
                    }
                    require(length.toLong() <= maxBytes - count) { "Encoded source exceeds admission limit" }
                    val bytes = java.nio.ByteBuffer.wrap(buffer, 0, length)
                    while (bytes.hasRemaining()) output.write(bytes)
                    digest.update(buffer, 0, length); count += length
                }
                require(count > 0) { "Empty source" }
                output.force(true)
            }
        }
        syncDirectory(checkNotNull(file.parentFile))
        return StagedSource(transactionId, sourceBlobId, hex(digest.digest()), count, file)
    }

    /** Metadata is the caller's bounded canonical normalized-inspection/ownership payload. It is opaque to this file layer; the repository validates its typed meaning before commit. */
    @Synchronized fun seal(staged: StagedSource, metadata: ByteArray): SealedSource {
        require(metadata.isNotEmpty() && metadata.size <= metadataLimit)
        validateStaged(staged)
        verify(staged.stagingFile, staged.byteCount, staged.byteSha256)
        val seal = SourceSeal(transactionId = staged.transactionId, sourceBlobId = staged.sourceBlobId, byteSha256 = staged.byteSha256, byteCount = staged.byteCount, metadataBase64 = Base64.getEncoder().encodeToString(metadata))
        val bytes = json.encodeToString(SourceSeal.serializer(), seal).toByteArray(Charsets.UTF_8)
        val file = sealFile(staged.transactionId)
        FileChannel.open(file.toPath(), CREATE_NEW, WRITE).use { output ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) output.write(buffer)
            output.force(true)
        }
        syncDirectory(checkNotNull(file.parentFile))
        return SealedSource(staged, metadata, sha256(bytes))
    }

    /** Under the caller's exclusive lease, atomic same-filesystem move to an opaque destination. A preexisting file is verified, not replaced. Replays retain a second matching staging payload instead of deleting evidence. */
    @Synchronized fun move(sealed: SealedSource): File {
        val staged = sealed.staged
        validateStaged(staged)
        val recovered = checkNotNull(recover(staged.transactionId, sealed.sealSha256)) { "Sealed source is missing" }
        require(recovered.staged.sourceBlobId == staged.sourceBlobId && recovered.staged.byteSha256 == staged.byteSha256 && recovered.staged.byteCount == staged.byteCount) { "Sealed identity mismatch" }
        val target = finalFile(staged.sourceBlobId)
        if (target.exists()) {
            verify(target, staged.byteCount, staged.byteSha256)
            createDirectories(checkNotNull(target.parentFile))
            syncDirectory(checkNotNull(target.parentFile)); syncDirectory(checkNotNull(staged.stagingFile.parentFile))
            return target
        }
        verify(staged.stagingFile, staged.byteCount, staged.byteSha256)
        createDirectories(checkNotNull(target.parentFile))
        // No non-atomic copy fallback: an unsupported move leaves a recoverable sealed staging object, never a partial final original.
        Files.move(staged.stagingFile.toPath(), target.toPath(), ATOMIC_MOVE)
        syncDirectory(checkNotNull(target.parentFile)); syncDirectory(checkNotNull(staged.stagingFile.parentFile))
        verify(target, staged.byteCount, staged.byteSha256)
        return target
    }

    /** Missing seal means uncommitted. A valid seal can lead the DB journal after interruption; every present payload must still match, including when both stage and final exist. */
    @Synchronized fun recover(transactionId: String, expectedSealSha256: String?): SealedSource? {
        validateId(transactionId); expectedSealSha256?.let(::requireSha256)
        val file = sealFile(transactionId)
        if (!file.exists()) return null
        require(file.length() in 1..(metadataLimit.toLong() * 2)) { "Invalid seal size" }
        val bytes = file.readBytes()
        val hash = sha256(bytes)
        require(expectedSealSha256 == null || hash == expectedSealSha256) { "Seal integrity mismatch" }
        val seal = json.decodeFromString(SourceSeal.serializer(), bytes.toString(Charsets.UTF_8))
        require(seal.version == 1 && seal.transactionId == transactionId) { "Unknown or mismatched source seal" }
        validateId(seal.sourceBlobId); requireSha256(seal.byteSha256)
        require(seal.byteCount in 1..inputLimit)
        val metadata = Base64.getDecoder().decode(seal.metadataBase64)
        require(metadata.isNotEmpty() && metadata.size <= metadataLimit)
        val stage = staging(transactionId); val target = finalFile(seal.sourceBlobId)
        require(stage.exists() || target.exists()) { "Sealed payload is missing" }
        if (stage.exists()) verify(stage, seal.byteCount, seal.byteSha256)
        if (target.exists()) verify(target, seal.byteCount, seal.byteSha256)
        return SealedSource(StagedSource(transactionId, seal.sourceBlobId, seal.byteSha256, seal.byteCount, stage), metadata, hash)
    }

    fun finalFile(sourceBlobId: String): File { validateId(sourceBlobId); return beneath("sources/$sourceBlobId/original.bin") }
    private fun staging(id: String): File = beneath("staging/$id/original.part")
    private fun sealFile(id: String): File = beneath("staging/$id/seal.json")

    private fun validateStaged(staged: StagedSource) {
        validateId(staged.transactionId); validateId(staged.sourceBlobId); requireSha256(staged.byteSha256)
        require(staged.byteCount in 1..inputLimit && staged.stagingFile == staging(staged.transactionId)) { "Mismatched staging ownership" }
    }

    private fun beneath(relative: String): File {
        val path = root.toPath().resolve(relative).normalize()
        require(path.startsWith(root.toPath()) && path != root.toPath()) { "Unsafe private path" }
        var current = root.toPath()
        for (part in root.toPath().relativize(path)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Private path is a symbolic link" }
            if (Files.exists(current)) require(current.toFile().canonicalFile.toPath() == current) { "Private path redirects outside its owned location" }
        }
        return path.toFile()
    }

    private fun createDirectories(directory: File) {
        require(directory.toPath().startsWith(root.toPath()))
        Files.createDirectories(directory.toPath())
        require(directory.canonicalFile == directory && directory.isDirectory) { "Invalid private directory" }
        // Persist the links, not just eventual leaf contents. Revisit the bounded ancestry even on replay: a previous operation may have created a directory and failed before its parent was forced. The store's containing directory is app-owned by the constructor contract.
        var child = directory
        while (child.toPath().startsWith(root.toPath())) {
            val parent = checkNotNull(child.parentFile)
            syncDirectory(parent)
            if (child == root) break
            child = parent
        }
    }

    private fun verify(file: File, length: Long, expectedHash: String) {
        require(file.isFile && file.length() == length) { "Source length mismatch" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(65536)
            var count = 0L
            while (true) {
                val n = source.read(buffer); if (n < 0) break
                count += n; require(count <= length)
                digest.update(buffer, 0, n)
            }
            require(count == length && hex(digest.digest()) == expectedHash) { "Source hash mismatch" }
        }
    }

    private fun syncDirectory(directory: File) {
        // Only an adapter's explicit unsupported result downgrades capability. Real open/force failures propagate, preserving files and preventing callers from advancing the durable journal boundary.
        if (!directorySync.sync(directory)) directorySyncSupported = false
    }

    private fun validateId(value: String) = requireOpaqueId(value)
    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
