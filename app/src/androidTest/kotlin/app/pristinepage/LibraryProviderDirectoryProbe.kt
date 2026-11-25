package app.pristinepage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.IOException

/** Test-only scope: query descriptors from the verified synthetic tree, without converting any URI to a filesystem path or creating a file. */
internal object LibraryProviderDirectoryProbe {
    fun verify(resolver: ContentResolver, tree: Uri) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = statAndSync(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), "selected root")
        val listed = resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
        )?.use { rows ->
            check(rows.count <= 16) { "The synthetic probe has unexpected children" }
            val entries = HashMap<String, String>()
            while (rows.moveToNext()) {
                val name = rows.getString(1)
                if (name in setOf("catalogs", "generations", "objects")) {
                    check(rows.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                    check(entries.put(name, rows.getString(0)) == null)
                }
            }
            entries.toMap()
        } ?: throw IOException("Provider could not enumerate the synthetic probe")
        check("catalogs" in listed && "generations" in listed)
        val identities = hashSetOf(root.st_dev to root.st_ino)
        for ((name, id) in listed) {
            val directory = statAndSync(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), name)
            check(directory.st_dev == root.st_dev && identities.add(directory.st_dev to directory.st_ino)) {
                "Provider did not expose distinct managed directory identities"
            }
        }
    }

    private fun statAndSync(resolver: ContentResolver, document: Uri, kind: String): StructStat {
        val descriptor = try {
            resolver.openFileDescriptor(document, "r")
        } catch (failure: Exception) {
            throw IOException("Provider directory probe: $kind descriptor refused (${failure.javaClass.simpleName})")
        } ?: throw IOException("Provider directory probe: $kind descriptor absent")
        return descriptor.use { owner ->
            val fd = owner.fileDescriptor
            val identity = try { Os.fstat(fd) }
            catch (failure: Exception) { throw IOException("Provider directory probe: $kind fstat refused (${failure.javaClass.simpleName})") }
            if (!OsConstants.S_ISDIR(identity.st_mode) || identity.st_ino <= 0) {
                throw IOException("Provider directory probe: $kind is not an identifiable directory")
            }
            try {
                val bytes = Os.fstatvfs(fd)
                check(bytes.f_frsize > 0 && bytes.f_bavail >= 0)
                Math.multiplyExact(bytes.f_frsize, bytes.f_bavail)
            } catch (failure: Exception) {
                throw IOException("Provider directory probe: $kind capacity unavailable (${failure.javaClass.simpleName})")
            }
            try { Os.fsync(fd) }
            catch (failure: Exception) { throw IOException("Provider directory probe: $kind sync refused (${failure.javaClass.simpleName})") }
            identity
        }
    }
}
