package app.pristinepage.data.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
        val root = canonicalLibraryDirectory(backup, privateFiles)
        val scratch = canonicalLibraryDirectory(scratchDirectory, privateFiles)
        val result = project(LocalLibraryDirectory(root.toFile()), scratch, checkpoint) { catalog ->
            val originals = LibraryPortableOriginals.read(catalog, checkpoint)
            LibraryBackupRead.Projection(Collections.unmodifiableMap(originals), originals.values)
        }
        Verified(result.generation, result.value)
    }

    fun <T> project(local: LocalLibraryDirectory, scratch: Path, checkpoint: () -> Unit,
        load: (SQLiteDatabase) -> LibraryBackupRead.Projection<T>,
    ): LibraryBackupRead.Verified<T> =
        LibraryBackupRead(local, scratch, RoomLibraryCatalogReader.MAX_PORTABLE_BYTES, checkpoint)
            .read(reader.schemaVersion) { _, catalog -> reader.read(catalog, load) }
}

internal fun canonicalLibraryDirectory(file: File, privateFiles: Path): Path {
    val path = file.absoluteFile.toPath()
    if (!path.startsWith(privateFiles) || !Files.isDirectory(path, NOFOLLOW_LINKS) || path.toRealPath() != path) {
        throw IOException("Library location is not a canonical app-private directory")
    }
    return path
}
