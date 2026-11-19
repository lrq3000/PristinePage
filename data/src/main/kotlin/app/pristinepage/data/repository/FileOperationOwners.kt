package app.pristinepage.data.repository

import app.pristinepage.data.model.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** App-private local-filesystem ownership protocol, not PID/deadline inference. Construct outside Room: canonical root resolution may perform IO. One shared-application-classloader channel/lock is intentionally retained per ownership root until actual VM termination; never close it at an operation boundary. All application writers must honor this advisory protocol. Legacy/missing/unsupported evidence stays UNKNOWN. */
class FileOperationOwners @JvmOverloads constructor(root: File, retainedEnded: Int = 1024, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : OperationOwners, OperationOwnerProof {
    private val pool: Pool

    init {
        require(retainedEnded in 1..65536)
        val canonical = root.canonicalFile
        val key = rootKey(canonical)
        pool = synchronized(pools) {
            pools.getOrPut(key) { Pool(canonical, retainedEnded) }.also { require(it.retainedEnded == retainedEnded) { "Ownership root retention configuration differs" } }
        }
    }

    override fun state(owner: String): LeaseOwnerState {
        val local = pool.local.state(owner)
        return if (local != LeaseOwnerState.UNKNOWN) local else if (pool.ended(owner)) LeaseOwnerState.ENDED else LeaseOwnerState.UNKNOWN
    }

    internal fun ownsCanonicalRoot(root: File): Boolean = rootKey(pool.root) == rootKey(root)

    /** Ordinary render-final participants only. Call outside Room on a file dispatcher. This lane excludes cooperating peers; it neither registers a requester nor proves a lease live. */
    internal suspend fun <T> withOrdinaryDerivedLane(jobId: JobId, block: suspend CoroutineScope.() -> T): T {
        currentCoroutineContext().ensureActive()
        if (!pool.ordinaryDerived.tryLock()) derivedBusy(jobId)
        try {
            val channel = try { pool.openOrdinaryDerivedMarker() }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { derivedLockFailed(jobId) }
            // Only the local holder opens the marker: closing a second channel on a live lock's file can release process-wide locks on some systems.
            val closeMarker = AutoCloseable {
                try { channel.close() } // The channel owns the native lock and releases it on close.
                catch (_: Exception) { derivedLockFailed(jobId) }
            }
            return closeMarker.use {
                val lock = try { channel.tryLock(0, 1, false) }
                catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) { derivedLockFailed(jobId) }
                if (lock == null) derivedBusy(jobId)
                // Do not close on a cancel flag: wait until the callback and all structured children/finally blocks have unwound.
                coroutineScope { ensureActive(); block() }
            }
        } finally {
            pool.ordinaryDerived.unlock()
        }
    }

    override suspend fun <T> run(block: suspend CoroutineScope.(owner: String) -> T): T = pool.local.run { owner ->
        withContext(dispatcher) {
            try { pool.register(owner) }
            catch (canceled: CancellationException) { throw canceled }
            catch (failure: RepositoryFailure) { throw failure }
            catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "owner_registration_failed", owner)) }
        }
        // The local scope waits for all children/finally cleanup before marking ENDED. No operation can reach job acquisition before its immutable process registration is closed/forced.
        block(owner)
    }

    override suspend fun refresh(owner: String): LeaseOwnerState {
        val known = state(owner)
        if (known != LeaseOwnerState.UNKNOWN) return known
        return withContext(dispatcher) {
            try {
                requireOpaqueId(owner)
                val proof = pool.probe(owner)
                // A foreign live process is a conservative takeover blocker, NOT a currently scoped requester in this VM. Only positive terminal evidence joins the bounded cache; local ALIVE comes solely from structured registration.
                if (proof == LeaseOwnerState.ENDED) pool.rememberEnded(owner)
                proof
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { LeaseOwnerState.UNKNOWN }
        }
    }

    private class Lifetime(val id: String, val channel: FileChannel, val lock: FileLock)

    private class Pool(val root: File, val retainedEnded: Int) {
        val local = LocalOperationOwners(retainedEnded)
        val ordinaryDerived = Mutex()
        private val terminal = LinkedHashMap<String, Unit>()
        private var lifetime: Lifetime? = null

        // Metadata participants must never wait on register's IO monitor. This distinct cache monitor protects only bounded in-memory operations; no native/filesystem work is performed while holding it.
        fun ended(owner: String) = synchronized(terminal) { terminal.containsKey(owner) }
        fun rememberEnded(owner: String): Unit = synchronized(terminal) {
            terminal[owner] = Unit
            if (terminal.size > retainedEnded) terminal.remove(terminal.keys.first())
        }

        fun openOrdinaryDerivedMarker(): FileChannel {
            val path = beneath(ORDINARY_DERIVED_MARKER).toPath()
            Files.createDirectories(path.parent)
            check(beneath(ORDINARY_DERIVED_MARKER).toPath() == path)
            val existing = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) { null }
            check(existing == null || existing.isRegularFile)
            // CREATE (not CREATE_NEW) permits a racing healthy process to open the same file, without ever replacing/truncating it.
            return FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS)
        }

        @Synchronized fun register(owner: String) {
            requireOpaqueId(owner)
            val process = lifetime ?: startLifetime().also { lifetime = it }
            check(process.channel.isOpen && process.lock.isValid) { "Process ownership channel lost" }
            val record = beneath("ownership/operations/$owner.owner")
            Files.createDirectories(checkNotNull(record.parentFile).toPath())
            writeNew(record, operationBytes(owner, process.id))
        }

        /** Lookup never creates/repairs an absent marker. Read foreign process contents only through the probing channel after acquiring its exclusive lock, never a second channel on a live own-process lock. */
        fun probe(owner: String): LeaseOwnerState {
            val record = beneath("ownership/operations/$owner.owner")
            if (!record.isFile) return LeaseOwnerState.UNKNOWN
            val bytes = FileChannel.open(record.toPath(), READ).use(::boundedRead)
            val parts = bytes.decodeToString(throwOnInvalidSequence = true).split('\n')
            if (parts.size != 4 || parts[0] != OPERATION_HEADER || parts[1] != owner || parts[3].isNotEmpty()) return LeaseOwnerState.UNKNOWN
            val processId = parts[2]; requireOpaqueId(processId)
            if (!bytes.contentEquals(operationBytes(owner, processId))) return LeaseOwnerState.UNKNOWN
            synchronized(this) { if (lifetime?.id == processId) return LeaseOwnerState.UNKNOWN }
            val file = beneath("ownership/processes/$processId.lock")
            if (!file.isFile) return LeaseOwnerState.UNKNOWN
            return FileChannel.open(file.toPath(), READ, WRITE).use { channel ->
                val proof = channel.tryLock(0, 1, false) ?: return@use LeaseOwnerState.ALIVE
                proof.use {
                    if (boundedRead(channel).contentEquals(processBytes(processId))) LeaseOwnerState.ENDED else LeaseOwnerState.UNKNOWN
                }
            }
        }

        private fun startLifetime(): Lifetime {
            val id = UUID.randomUUID().toString()
            val file = beneath("ownership/processes/$id.lock")
            Files.createDirectories(checkNotNull(file.parentFile).toPath())
            val channel = FileChannel.open(file.toPath(), CREATE_NEW, READ, WRITE)
            try {
                val lock = checkNotNull(channel.tryLock(0, 1, false)) { "New process marker unexpectedly locked" }
                try {
                    write(channel, processBytes(id)); channel.force(true)
                    // Deliberate process-lifetime strong ownership: a close()/finally API here would let recovery certify a still-running VM. Partial marker loss after power failure blocks proof instead of authorizing takeover.
                    return Lifetime(id, channel, lock)
                } catch (failure: Throwable) { lock.release(); throw failure }
            } catch (failure: Throwable) { channel.close(); throw failure }
        }

        private fun writeNew(file: File, bytes: ByteArray) {
            FileChannel.open(file.toPath(), CREATE_NEW, WRITE).use { channel -> write(channel, bytes); channel.force(true) }
        }

        private fun beneath(relative: String): File {
            val path = root.toPath().resolve(relative).normalize()
            require(path.startsWith(root.toPath()) && path != root.toPath())
            // Protocol paths are fixed plus validated opaque IDs. Refuse any extant redirected/symlinked component; no caller title, provider URI or broad directory scan enters this lookup.
            var current = root.toPath()
            require(!Files.isSymbolicLink(current))
            for (part in root.toPath().relativize(path)) {
                current = current.resolve(part)
                require(!Files.isSymbolicLink(current))
                if (Files.exists(current)) require(current.toFile().canonicalFile.toPath() == current)
            }
            return path.toFile()
        }
    }

    private companion object {
        const val OPERATION_HEADER = "pristinepage-operation-owner-v1"
        const val PROCESS_HEADER = "pristinepage-process-owner-v1"
        const val ORDINARY_DERIVED_MARKER = "ownership/ordinary-derived-v1.lock"
        val pools = HashMap<String, Pool>()
        fun rootKey(root: File): String =
            if (System.getProperty("os.name", "").orEmpty().startsWith("Windows", ignoreCase = true)) root.path.lowercase(Locale.ROOT) else root.path
        fun derivedBusy(jobId: JobId): Nothing =
            throw RepositoryFailure(OperationError(OperationErrorCode.REVISION_CONFLICT, true, "ordinary_derived_store_busy", jobId.value))
        fun derivedLockFailed(jobId: JobId): Nothing =
            throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "ordinary_derived_store_lock_failed", jobId.value))
        fun processBytes(id: String) = "$PROCESS_HEADER\n$id\n".toByteArray(Charsets.UTF_8)
        fun operationBytes(owner: String, process: String) = "$OPERATION_HEADER\n$owner\n$process\n".toByteArray(Charsets.UTF_8)
        fun write(channel: FileChannel, bytes: ByteArray) { val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) check(channel.write(buffer) > 0) }
        fun boundedRead(channel: FileChannel): ByteArray {
            val size = channel.size(); require(size in 1..512)
            val buffer = ByteBuffer.allocate(size.toInt()); channel.position(0)
            while (buffer.hasRemaining()) check(channel.read(buffer) > 0)
            require(channel.size() == size)
            return buffer.array()
        }
    }
}
