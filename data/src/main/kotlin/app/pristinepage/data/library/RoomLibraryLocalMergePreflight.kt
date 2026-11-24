package app.pristinepage.data.library

import android.content.Context
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import app.pristinepage.data.model.TransactionId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Dry run for two existing local portable libraries under stable cooperative ownership. No writes to either library and no publication. */
internal class RoomLibraryLocalMergePreflight(context: Context, private val scratchDirectory: File) {
    private val application = context.applicationContext
    private val reader = RoomLibraryCatalogReader(application, MAX_PORTABLE_BYTES)

    data class Observation(val destination: LibraryGeneration, val incoming: LibraryGeneration,
        val catalog: LibraryObjectTransfer.ObjectId, val payloads: LibraryMergePayloads.Estimate)

    suspend fun verify(operationId: String, destination: File, incoming: File): Observation = withContext(Dispatchers.IO) {
        TransactionId(operationId)
        val checkpoint = { coroutineContext.ensureActive() }
        val privateFiles = application.filesDir.toPath().toRealPath()
        val targetRoot = canonicalLibraryDirectory(destination, privateFiles)
        val sourceRoot = canonicalLibraryDirectory(incoming, privateFiles)
        val scratch = canonicalLibraryDirectory(scratchDirectory, privateFiles)
        if (overlaps(targetRoot, sourceRoot) || overlaps(targetRoot, scratch) || overlaps(sourceRoot, scratch)) {
            throw IOException("Merge locations overlap")
        }
        val target = LocalLibraryDirectory(targetRoot.toFile())
        val source = LocalLibraryDirectory(sourceRoot.toFile())
        val backup = RoomLibraryLocalBackup(application, scratchDirectory)
        fun snapshot(directory: LocalLibraryDirectory) = backup.project(directory, scratch, checkpoint) { sql ->
            val result = LibraryPortableCatalogProjection(sql, checkpoint = checkpoint).snapshot()
            LibraryBackupRead.Projection(result, result.originals.map { LibraryObjectTransfer.ObjectId(it.byteSha256, it.byteCount) })
        }

        val baseline = snapshot(target)
        val imported = snapshot(source)
        val candidate = LibraryCatalogMerge.plan(operationId, baseline.value, imported.value, checkpoint)
        if (scratchDirectory.usableSpace < 3 * MAX_PORTABLE_BYTES) throw IOException("Not enough private space to stage the merged catalog")
        val attempt = createStage(scratch)
        val stage = attempt.resolve("catalog.pending.sqlite").toFile()
        try {
            RoomLibraryCatalogStage(application).write(candidate.catalog, stage)
            reader.verifyStagedProjection(stage, candidate.catalog, checkpoint)
            val catalog = fingerprintClosedLibraryCatalog(stage, checkpoint)
            // Cover the outstanding catalog and private readback copy while this exact stage still occupies disk.
            val metadata = 2 * catalog.byteCount + 16L * 1024 * (candidate.catalog.originals.size + 8L)
            val estimate = LibraryMergePayloads.check(candidate, baseline.value.originals, imported.value.originals,
                target, source, metadata, minOf(destination.usableSpace, scratchDirectory.usableSpace), checkpoint)
            verifyGeneration(target, baseline.generation, checkpoint)
            verifyGeneration(source, imported.generation, checkpoint)
            if (fingerprintClosedLibraryCatalog(stage, checkpoint) != catalog) throw IOException("The staged merge catalog changed")
            Observation(baseline.generation, imported.generation, catalog, estimate)
        } finally {
            // Remove only this attempt's private scratch leaf. Any sidecar or cleanup uncertainty remains for bounded attention.
            try {
                Files.deleteIfExists(stage.toPath())
                Files.deleteIfExists(attempt)
            } catch (_: IOException) { /* Never reach either selected library through cleanup. */ }
        }
    }

    private fun verifyGeneration(store: LocalLibraryDirectory, expected: LibraryGeneration, checkpoint: () -> Unit) {
        store.openCatalog(expected)?.use { expected.verifyCatalog(it, checkpoint) } ?: throw IOException("Merge input catalog disappeared")
        if (store.readTip(checkpoint) != expected) throw IOException("Merge input generation changed")
    }

    private fun overlaps(a: Path, b: Path) = a.startsWith(b) || b.startsWith(a) || Files.isSameFile(a, b)

    private fun createStage(scratch: Path): Path = synchronized(STAGE_ADMISSION) {
        Files.newDirectoryStream(scratch, "library-merge-stage-*").use {
            if (it.asSequence().take(4).count() == 4) throw IOException("Retained merge staging needs attention")
        }
        Files.createTempDirectory(scratch, "library-merge-stage-")
    }

    private companion object { val STAGE_ADMISSION = Any() }
}
