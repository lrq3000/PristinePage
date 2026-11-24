package app.pristinepage.data.library

import android.content.Context
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import app.pristinepage.data.model.TransactionId
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** App-private portable-folder copy only. The private lane serializes cooperating copy callers; active stores and provider folders must not use this entry point. */
internal class RoomLibraryLocalMerge(context: Context, private val sync: DirectorySync = PlatformDirectorySync) {
    private val app = context.applicationContext
    private val reader = RoomLibraryCatalogReader(app, MAX_PORTABLE_BYTES)

    class Prepared internal constructor(
        internal val id: String,
        internal val destination: Path,
        internal val incoming: Path,
        internal val destinationKey: String,
        internal val incomingKey: String,
        val preview: RoomLibraryLocalMergePreflight.Observation,
    )

    /** Private staging only. This observation binds the physical roots and exact input generations before confirmation. */
    suspend fun prepare(operationId: String, destination: File, incoming: File): Prepared = withLane { privateFiles ->
        TransactionId(operationId)
        val target = root(destination, privateFiles)
        val backup = root(incoming, privateFiles)
        if (overlaps(target, backup)) throw IOException("Library merge locations overlap")
        LocalLibraryDirectory(target.toFile()).generationInventory().requireVacantChildId(operationId)
        val targetKey = localLibraryRootKey(target)
        val backupKey = localLibraryRootKey(backup)
        val scratch = privateFiles.resolve(PRIVATE_STAGES)
        if (Files.notExists(scratch, NOFOLLOW_LINKS)) Files.createDirectory(scratch)
        val owned = canonicalLibraryDirectory(scratch.toFile(), privateFiles)
        val preview = RoomLibraryLocalMergePreflight(app, owned.toFile()).verify(operationId, target.toFile(), backup.toFile())
        if (localLibraryRootKey(target) != targetKey || localLibraryRootKey(backup) != backupKey) throw IOException("Library merge location changed during preview")
        Prepared(operationId, target, backup, targetKey, backupKey, preview)
    }

    /** Resend the same prepared action after an uncertain result. No caller chooses the private journal namespace. */
    suspend fun copy(confirmed: Prepared): LibraryGeneration =
        execute(confirmed.id, confirmed.destination.toFile(), confirmed.incoming.toFile(), confirmed)

    /** Recover a known operation after process loss. Without the durable journal this path performs no transfer and never creates a new stage. */
    suspend fun resume(operationId: String, destination: File, incoming: File): LibraryGeneration =
        execute(operationId, destination, incoming, confirmed = null)

    private suspend fun <T> withLane(block: suspend (Path) -> T): T = withContext(Dispatchers.IO) {
        val privateFiles = app.filesDir.toPath().toRealPath()
        if (!copyLane.tryLock()) throw IOException("Another local library copy owns the storage lane")
        try {
            // Do not open a second native lock channel while this process owns it. The lock file is outside both libraries.
            FileChannel.open(privateFiles.resolve("library-copy-v1.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = channel.tryLock() ?: throw IOException("Another process owns the local library copy lane")
                lock.use { block(privateFiles) }
            }
        } finally {
            copyLane.unlock()
        }
    }

    private suspend fun execute(operationId: String, targetFile: File, backupFile: File, confirmed: Prepared?): LibraryGeneration =
        withLane { privateFiles ->
            TransactionId(operationId)
            val context = currentCoroutineContext()
            val target = root(targetFile, privateFiles)
            val backup = root(backupFile, privateFiles)
            if (overlaps(target, backup)) throw IOException("Library merge locations overlap")
            val scratch = canonicalLibraryDirectory(privateFiles.resolve(PRIVATE_STAGES).toFile(), privateFiles)
            copyOwned(operationId, target, backup, scratch, privateFiles, confirmed) { context.ensureActive() }
        }

    private suspend fun copyOwned(operationId: String, target: Path, backup: Path, scratch: Path, privateFiles: Path,
        confirmed: Prepared?, checkpoint: () -> Unit,
    ): LibraryGeneration {
        checkpoint()
        val journal = LocalLibraryMergeJournal(scratch.toFile(), privateFiles, sync)
        val existing = journal.reopen(operationId, checkpoint)
        val baseline = confirmed?.preview?.destination ?: existing?.destination ?: throw IOException("Private merge intent is missing")
        val incoming = confirmed?.preview?.incoming ?: checkNotNull(existing).incoming
        val targetKey = confirmed?.destinationKey ?: checkNotNull(existing).destinationRootKey
        val backupKey = confirmed?.incomingKey ?: checkNotNull(existing).incomingRootKey
        val destination = LocalLibraryDirectory(target.toFile(), sync)
        val source = LocalLibraryDirectory(backup.toFile())
        val scratchKey = localLibraryRootKey(scratch)
        fun checkLocations() {
            if (localLibraryRootKey(target) != targetKey || localLibraryRootKey(backup) != backupKey || localLibraryRootKey(scratch) != scratchKey) {
                throw IOException("Library merge location changed")
            }
        }
        checkLocations()
        val schema = reader.schemaVersion
        val inventory = destination.generationInventory(checkpoint)
        val targetTip = inventory.tip
        val sourceTip = source.readTip(checkpoint)
        if (baseline.catalogSchema != schema || incoming.catalogSchema != schema || sourceTip != incoming) {
            throw IOException("Confirmed library inputs are no longer available")
        }
        if (existing == null) {
            if (targetTip != baseline) throw IOException("Confirmed destination generation changed")
            inventory.requireVacantChildId(operationId)
        } else {
            if (existing.destination != baseline || existing.incoming != incoming) throw IOException("Confirmation differs from the journaled copy")
            existing.resume(targetKey, backupKey, targetTip, sourceTip, schema)
        }

        val current = readSnapshot(destination, scratch, baseline, targetTip, schema, checkpoint)
        val imported = readSnapshot(source, scratch, incoming, incoming, schema, checkpoint)
        // Reconstruct the expected projection from the exact original inputs, never from the already merged tip. Retry cannot create another stage or nominate a new child.
        val candidate = LibraryCatalogMerge.plan(operationId, current, imported, checkpoint)
        if (existing == null) {
            if (scratch.toFile().usableSpace < 3 * MAX_PORTABLE_BYTES) throw IOException("Not enough private space to stage the library copy")
            val stage = createStage(scratch, operationId)
            val version = RoomLibraryCatalogStage(app).write(candidate.catalog, stage)
            reader.verifyStagedProjection(stage, candidate.catalog, checkpoint)
            val child = LibraryGeneration(baseline.libraryId, operationId, baseline.id, version, fingerprintClosedLibraryCatalog(stage, checkpoint))
            inventory.requireAppendable(child)
            checkLocations()
            if (destination.readTip(checkpoint) != baseline || source.readTip(checkpoint) != incoming) {
                throw IOException("Library input changed before intent publication")
            }
            journal.publish(LibraryMergeIntent(targetKey, backupKey, baseline, incoming, child), checkpoint)
        }
        checkLocations()
        val child = LocalLibraryMergeCommit(destination, source, journal, checkpoint)
            .commit(operationId, targetKey, backupKey, schema, candidate, current.originals, imported.originals) { file ->
                reader.verifyStagedProjection(file, candidate.catalog, checkpoint)
                checkLocations()
            }
        checkLocations()
        return child
    }

    private fun readSnapshot(store: LocalLibraryDirectory, scratch: Path, generation: LibraryGeneration,
        tip: LibraryGeneration, schema: Int, checkpoint: () -> Unit,
    ): RoomLibrarySavedCatalog.Snapshot =
        LibraryBackupRead(store, scratch, MAX_PORTABLE_BYTES, checkpoint).read(schema, generation, tip) { _, file ->
            reader.read(file) { sql ->
                val catalog = LibraryPortableCatalogProjection(sql, checkpoint = checkpoint).snapshot()
                LibraryBackupRead.Projection(catalog, catalog.originals.map { LibraryObjectTransfer.ObjectId(it.byteSha256, it.byteCount) })
            }
        }.value

    private fun createStage(scratch: Path, operationId: String): File {
        val stages = scratch.resolve("merge-stages")
        if (Files.notExists(stages, NOFOLLOW_LINKS)) Files.createDirectory(stages)
        if (!Files.isDirectory(stages, NOFOLLOW_LINKS) || stages.toRealPath() != stages) {
            throw IOException("Invalid private merge stages directory")
        }
        Files.newDirectoryStream(stages).use {
            if (it.asSequence().take(4).count() == 4) throw IOException("Retained private merge stages need attention")
        }
        val attempt = Files.createDirectory(stages.resolve(operationId))
        return attempt.resolve("catalog.sqlite").toFile()
    }

    private fun overlaps(a: Path, b: Path) = a.startsWith(b) || b.startsWith(a) || Files.isSameFile(a, b)

    private fun root(file: File, privateFiles: Path): Path {
        val result = canonicalLibraryDirectory(file, privateFiles)
        val privateStages = privateFiles.resolve(PRIVATE_STAGES)
        if (result.startsWith(privateStages) || privateStages.startsWith(result)) throw IOException("Library location overlaps the operation journal")
        return result
    }

    private companion object {
        const val PRIVATE_STAGES = "library-copy-private-v1"
        val copyLane = Mutex()
    }
}
