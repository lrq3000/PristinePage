package app.pristinepage.data.library

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads a local app-private backup whose caller retains stable, cooperative ownership for the entire read. A matching reread is not a lock or an import/activation capability. */
internal class RoomLibraryLocalBackup(context: Context, private val scratchDirectory: File) {
    private val application = context.applicationContext
    private val reader = RoomLibraryCatalogReader(application, RoomLibraryCatalogReader.MAX_PORTABLE_BYTES)

    data class Verified(val generation: LibraryGeneration, val originals: Map<String, LibraryObjectTransfer.ObjectId>)

    suspend fun read(backup: File): Verified = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive() }
        val privateFiles = application.filesDir.toPath().toRealPath()
        val root = privateDirectory(backup, privateFiles)
        val scratch = privateDirectory(scratchDirectory, privateFiles)
        val local = LocalLibraryDirectory(root.toFile())
        val result = LibraryBackupRead(local, scratch, RoomLibraryCatalogReader.MAX_PORTABLE_BYTES, checkpoint)
            .read(reader.schemaVersion) { _, catalog ->
                val originals = reader.read(catalog) { LibraryPortableOriginals.read(it, checkpoint) }
                LibraryBackupRead.Projection(Collections.unmodifiableMap(originals), originals.values)
            }
        Verified(result.generation, result.value)
    }

    private fun privateDirectory(file: File, privateFiles: Path): Path {
        val path = file.absoluteFile.toPath()
        if (!path.startsWith(privateFiles) || !Files.isDirectory(path, NOFOLLOW_LINKS) || path.toRealPath() != path) {
            throw IOException("Library location is not a canonical app-private directory")
        }
        return path
    }
}
