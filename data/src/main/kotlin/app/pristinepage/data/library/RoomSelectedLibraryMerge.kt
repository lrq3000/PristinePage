package app.pristinepage.data.library

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import app.pristinepage.data.model.requireOpaqueId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Detached selected-folder append qualification. No activation switch, retired source, or new write following an uncertain result. */
internal class RoomSelectedLibraryMerge(context: Context, private val scratchDirectory: File) {
    private val app = context.applicationContext
    private val maximum = RoomLibraryCatalogReader.MAX_PORTABLE_BYTES

    suspend fun append(operationId: String, destination: AndroidLibraryTreeRead.PairInput, incoming: AndroidLibraryTreeRead.PairInput,
        requireAuthority: () -> Unit,
        beforeDelivery: (suspend (LibraryGeneration) -> Nothing)? = null,
    ): LibraryGeneration = withContext(Dispatchers.IO) {
        val published = appendAndAdopt(operationId, destination, incoming, requireAuthority) { generation, _ -> generation }
        // A per-invocation instrumentation observation only. Never arm this from a chooser, disk state, or a recovered intent.
        beforeDelivery?.invoke(published)
        published
    }

    /** An active merge consumes the one live result under the exact lane. No cold inspection can enter this continuation. */
    suspend fun <T> appendAndAdopt(operationId: String, destination: AndroidLibraryTreeRead.PairInput, incoming: AndroidLibraryTreeRead.PairInput,
        requireAuthority: () -> Unit, adopt: suspend (LibraryGeneration, () -> Unit) -> T,
    ): T = withContext(Dispatchers.IO) {
        requireOpaqueId(operationId)
        if (destination.expected == null || incoming.expected == null) throw IOException("The selected write needs both exact input generations")
        val checkpoint = { coroutineContext.ensureActive() }
        val files = app.filesDir.toPath().toRealPath()
        checkpoint()
        requireAuthority()
        withSelectedLibraryWriteLane(files) { lane ->
            fun requireLane() {
                checkpoint()
                lane.requireCurrent(files)
                requireAuthority()
            }
            val published = appendOwned(operationId, destination, incoming, files, checkpoint, ::requireLane)
            requireLane()
            adopt(published) { checkpoint(); lane.requireCurrent(files) }
        }
    }

    private suspend fun appendOwned(operationId: String, destination: AndroidLibraryTreeRead.PairInput, incoming: AndroidLibraryTreeRead.PairInput,
        files: Path, checkpoint: () -> Unit, requireAuthority: () -> Unit,
    ): LibraryGeneration {
        val storage = files.resolve(STORAGE)
        val attempts = LocalSelectedMergeAttempts(storage) { journal(it, files) }
        attempts.requireUnused(operationId)
        val reader = AndroidLibraryTreeRead(app, scratchDirectory)
        val plan = reader.planMerge(operationId, destination, incoming, requireAuthority)
        if (files.toFile().usableSpace < 3 * maximum) throw IOException("Not enough private space to stage the merged library")
        requireAuthority()
        checkpoint()
        // A repeat is a fresh confirmed operation, not replay. Retained unresolved evidence still blocks admission.
        val (journal, attempt) = attempts.allocate(operationId, checkpoint, requireAuthority)
        val root = journal.rootPath
        val key = localLibraryRootKey(root)
        val attemptKey = localLibraryRootKey(attempt)
        fun requireOwner() {
            checkpoint()
            requireAuthority()
            if (localLibraryRootKey(root) != key || localLibraryRootKey(attempt) != attemptKey) {
                throw IOException("The private selected library attempt changed")
            }
            checkpoint()
        }
        val closed = attempt.resolve("catalog.sqlite")
        val file = closed.toFile()
        requireOwner()
        val schema = RoomLibraryCatalogStage(app).write(plan.candidate.catalog, file)
        val verifier = RoomLibraryCatalogReader(app, maximum)
        val proof = fingerprintClosedLibraryCatalog(file, ::requireOwner)
        verifier.verifyStagedProjection(file, plan.candidate.catalog, ::requireOwner)
        if (fingerprintClosedLibraryCatalog(file, ::requireOwner) != proof) throw IOException("The private selected library catalog changed")
        val baseline = checkNotNull(plan.destination.expected)
        val child = LibraryGeneration(baseline.libraryId, operationId, baseline.id, schema, proof)
        val published = reader.appendMerge(plan, child, attempt, journal, LocalLibraryMoveAuthorization(attempt.toFile()), ::requireOwner) {
            verifier.verifyStagedProjection(it, plan.candidate.catalog, ::requireOwner)
        }
        // The paired reader, writer, and scratch owners have all unwound. A late I/O failure or canceled result before this boundary leaves only intent.
        requireOwner()
        Files.newDirectoryStream(attempt, "library-catalog-*").use { verification ->
            if (verification.iterator().hasNext()) throw IOException("The selected library readback scratch was not retired")
        }
        published.completion.recordCompletion(published.generation, checkpoint, ::requireOwner)
        return published.generation
    }

    companion object {
        private const val STORAGE = "library-selected-merge-v1"

        private fun journal(root: Path, files: Path) = LocalLibraryMergeJournal(root.toFile(), files, nodeIdentity = { path, _ ->
            try { Os.lstat(path.toString()).let { it.st_dev to it.st_ino } }
            catch (failure: ErrnoException) { throw IOException("The private selected merge evidence cannot be identified", failure) }
        })

        /** Inspect one retained private attempt. A completion is historical, not proof the external folder is still accessible. */
        suspend fun inspect(context: Context, operationId: String): LocalLibraryMergeJournal.SelectedState? = withContext(Dispatchers.IO) {
            requireOpaqueId(operationId)
            val files = context.applicationContext.filesDir.toPath().toRealPath()
            val storage = files.resolve(STORAGE)
            try {
                Files.readAttributes(storage, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return@withContext null
            }
            val root = canonicalLibraryDirectory(storage.toFile(), files)
            val key = localLibraryRootKey(root)
            val state = journal(root, files).inspectSelected(operationId) { coroutineContext.ensureActive() }
            if (localLibraryRootKey(root) != key) throw IOException("The private selected merge journal changed")
            state
        }
    }
}
