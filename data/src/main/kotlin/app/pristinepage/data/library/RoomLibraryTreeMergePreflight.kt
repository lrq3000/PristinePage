package app.pristinepage.data.library

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Stage and independently reopen one app-private candidate. Both selected folders remain read-only. */
internal class RoomLibraryTreeMergePreflight(context: Context, private val scratchDirectory: File) {
    private val app = context.applicationContext

    data class Observation(val plan: AndroidLibraryTreeRead.MergePlan, val closedCatalog: LibraryObjectTransfer.ObjectId,
        val estimate: LibraryMergePayloads.Estimate)

    suspend fun verify(operationId: String, destination: AndroidLibraryTreeRead.PairInput, incoming: AndroidLibraryTreeRead.PairInput,
        requireAuthority: () -> Unit,
    ): Observation = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        val reader = AndroidLibraryTreeRead(app, scratchDirectory)
        val plan = reader.planMerge(operationId, destination, incoming, requireAuthority)
        val root = canonicalLibraryDirectory(scratchDirectory, app.filesDir.toPath().toRealPath())
        val maximum = RoomLibraryCatalogReader.MAX_PORTABLE_BYTES
        if (root.toFile().usableSpace < 3 * maximum) throw IOException("Not enough private space to stage the merged catalog")
        lateinit var requireAttempt: () -> Unit
        val closed = LibraryPairScratch.use(root, bind = { attempt ->
            val original = localLibraryRootKey(attempt)
            requireAttempt = { if (localLibraryRootKey(attempt) != original) throw IOException("The private merge stage changed") }
            requireAttempt
        }) { attempt ->
            val path = attempt.resolve("candidate.sqlite")
            val file = path.toFile()
            var admitted: ClosedStage? = null
            // There is no cancellation checkpoint in cleanup. Only a positively acknowledged close can install this owner.
            AutoCloseable { admitted?.close() }.use {
                requireAttempt()
                RoomLibraryCatalogStage(app).write(plan.candidate.catalog, file) {
                    requireAttempt()
                    if (!Files.notExists(path, NOFOLLOW_LINKS)) admitted = ClosedStage.open(path, requireAttempt)
                }
                val owned = admitted ?: throw IOException("The private merge catalog did not close")
                requireAuthority()
                checkpoint()
                owned.requireCurrent()
                val fingerprint = fingerprintClosedLibraryCatalog(file, checkpoint)
                RoomLibraryCatalogReader(app, maximum).verifyStagedProjection(file, plan.candidate.catalog, checkpoint)
                owned.requireCurrent()
                if (fingerprintClosedLibraryCatalog(file, checkpoint) != fingerprint) throw IOException("The staged merge catalog changed")
                fingerprint
            }
        }
        // Charge only missing originals, the closed catalog, and bounded publication metadata. This is an estimate, not a reservation.
        val metadata = closed.byteCount + 16L * 1024 * (plan.candidate.catalog.originals.size + 8L)
        val estimate = reader.estimateMerge(plan, metadata, requireAuthority)
        checkpoint()
        Observation(plan, closed, estimate)
    }

    private class ClosedStage private constructor(private val path: Path, private val owner: ParcelFileDescriptor,
        private val requireAttempt: () -> Unit,
    ) : AutoCloseable {
        fun requireCurrent() {
            requireAttempt()
            try {
                val admitted = Os.fstat(owner.fileDescriptor)
                val actual = Os.lstat(path.toString())
                if (path.toRealPath() != path || !OsConstants.S_ISREG(actual.st_mode) || admitted.st_ino <= 0 ||
                    admitted.st_dev != actual.st_dev || admitted.st_ino != actual.st_ino || actual.st_nlink != 1L
                ) throw IOException("The closed merge catalog changed")
            } catch (_: ErrnoException) { throw IOException("The closed merge catalog identity is unavailable") }
        }

        override fun close() {
            owner.use {
                requireCurrent()
                Files.newDirectoryStream(path.parent).use { files ->
                    if (files.asSequence().take(2).toList() != listOf(path)) {
                        throw IOException("The private merge stage contains unresolved evidence")
                    }
                }
                requireCurrent()
                Files.delete(path)
            }
        }

        companion object {
            fun open(path: Path, requireAttempt: () -> Unit): ClosedStage {
                val descriptor = ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    return ClosedStage(path, descriptor, requireAttempt).also { it.requireCurrent() }
                } catch (failure: Throwable) {
                    try { descriptor.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
                    throw failure
                }
            }
        }
    }
}
