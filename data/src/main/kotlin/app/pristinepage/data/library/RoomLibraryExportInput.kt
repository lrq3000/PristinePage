package app.pristinepage.data.library

import android.content.Context
import app.pristinepage.data.files.SourceFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.util.UUID

/** One closed, projection-checked catalog and exact lazy original locators shared by both folder export adapters. */
internal class RoomLibraryExportInput private constructor(
    val generation: LibraryGeneration, private val closed: File, private val snapshot: RoomLibrarySavedCatalog.Snapshot,
    val requirements: List<LibraryObjectTransfer.Requirement>, private val reader: RoomLibraryCatalogReader,
    private val checkpoint: () -> Unit,
) {
    fun openCatalog() = readRegular(closed)

    fun verifyCatalog(file: File) = reader.verifyStagedProjection(file, snapshot, checkpoint)

    fun requiredSpace(scratchCopies: Int, maximumOriginalBytes: Long = Long.MAX_VALUE): Long {
        val unique = HashMap<String, Long>()
        for ((id, _) in requirements) {
            checkpoint()
            val previous = unique.putIfAbsent(id.sha256, id.byteCount)
            if (previous != null && previous != id.byteCount) throw IOException("Conflicting original lengths")
        }
        val originals = unique.values.sum()
        if (originals > maximumOriginalBytes) throw IOException("The library backup would exceed the folder reader's verification limit")
        return metadataBytes(scratchCopies) + originals
    }

    fun metadataBytes(scratchCopies: Int): Long =
        (1L + scratchCopies) * generation.catalog.byteCount + 16L * 1024 * (requirements.size + 8L)

    companion object {
        suspend fun prepare(
            application: Context, snapshot: RoomLibrarySavedCatalog.Snapshot, originals: File,
            closed: File, checkpoint: () -> Unit,
        ): RoomLibraryExportInput {
            val source = SourceFiles(originals)
            val reader = RoomLibraryCatalogReader(application, RoomLibraryCatalogReader.MAX_PORTABLE_BYTES)
            val version = RoomLibraryCatalogStage(application).write(snapshot, closed)
            reader.verifyStagedProjection(closed, snapshot, checkpoint)
            val generation = LibraryGeneration(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, version,
                fingerprintClosedLibraryCatalog(closed, checkpoint))
            val requirements = snapshot.originals.map { blob ->
                LibraryObjectTransfer.Requirement(LibraryObjectTransfer.ObjectId(blob.byteSha256, blob.byteCount)) {
                    readRegular(source.finalFile(blob.sourceBlobId))
                }
            }
            return RoomLibraryExportInput(generation, closed, snapshot, requirements, reader, checkpoint)
        }

        private fun readRegular(file: File): java.io.InputStream {
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) throw IOException("Library input is not a regular file")
            return Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS)
        }
    }
}
