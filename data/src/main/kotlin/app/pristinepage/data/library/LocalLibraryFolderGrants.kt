package app.pristinepage.data.library

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** Private immutable locator storage. The caller owns the durable settings root; Android still has to prove the grant and catalog. */
internal class LocalLibraryFolderGrants(root: File, sync: DirectorySync = PlatformDirectorySync) {
    private val store = LocalLibraryDirectory(root, sync)

    fun readVerified(grantId: String): LibraryFolderGrantRecord? {
        val first = store.readFolderGrant(grantId) ?: return null
        store.forceFolderGrants(grantId)
        if (store.readFolderGrant(grantId) != first) throw IOException("The private folder binding changed")
        return first
    }

    fun writeVerified(record: LibraryFolderGrantRecord): LibraryFolderGrantRecord {
        if (store.readFolderGrant(record.grantId) == null) {
            try { store.createFolderGrant(record) }
            catch (_: FileAlreadyExistsException) { /* A concurrent caller can only win with the identical immutable binding. */ }
        }
        return readVerified(record.grantId)?.takeIf { it == record }
            ?: throw IOException("The private folder binding conflicts with this selection")
    }
}
