package app.pristinepage

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import app.pristinepage.data.library.LibraryDescriptorAncestryWitness
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows

/** Test-only scope: query descriptors from the verified synthetic tree, without converting any URI to a filesystem path or creating a file. */
internal object LibraryProviderDirectoryProbe {
    private val namespaces = setOf("catalogs", "generations", "objects")

    fun verify(resolver: ContentResolver, tree: Uri) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = statAndSync(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), "selected root")
        val listed = managedChildren(resolver, tree, rootId)
        check("catalogs" in listed && "generations" in listed)
        val identities = hashSetOf(root.st_dev to root.st_ino)
        for ((name, id) in listed) {
            val directory = statAndSync(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), name)
            check(directory.st_dev == root.st_dev && identities.add(directory.st_dev to directory.st_ino)) {
                "Provider did not expose distinct managed directory identities"
            }
        }
    }

    private fun managedChildren(resolver: ContentResolver, tree: Uri, rootId: String): Map<String, String> =
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
        )?.use { rows ->
            check(rows.count <= 16) { "The synthetic probe has unexpected children" }
            val entries = HashMap<String, String>()
            while (rows.moveToNext()) {
                val name = rows.getString(1)
                if (name in namespaces) {
                    val id = rows.getString(0)
                    check(id.isNotBlank() && id != rootId && rows.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                    check(entries.put(name, id) == null)
                }
            }
            entries.toMap().also { check(it.values.toSet().size == it.size) }
        } ?: throw IOException("Provider could not enumerate the synthetic probe")

    /** The control uses provider-opened descriptors, not host paths or IDs interpreted as paths. */
    fun verifyAncestry(resolver: ContentResolver, tree: Uri) {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val children = managedChildren(resolver, tree, root)
        check(children.keys == namespaces)
        fun open(id: String): ParcelFileDescriptor =
            checkNotNull(resolver.openFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(tree, id), "r"))
        open(root).use { anchor ->
            open(children.getValue("catalogs")).use { a ->
                open(children.getValue("objects")).use { b ->
                    LibraryDescriptorAncestryWitness.requireDisjoint(anchor, a, b)
                    open(children.getValue("catalogs")).use { same ->
                        LibraryDescriptorAncestryWitness.requireSameIdentity(a, same)
                        assertEquals("Library folders overlap or have no proven shared storage anchor",
                            assertThrows(IOException::class.java) { LibraryDescriptorAncestryWitness.requireDisjoint(anchor, a, same) }.message)
                    }
                    assertEquals("Library folder ancestry is incomplete",
                        assertThrows(IOException::class.java) { LibraryDescriptorAncestryWitness.requireDisjoint(anchor, anchor, a) }.message)
                    check(managedChildren(resolver, tree, root) == children) { "The provider changed its managed child locators" }
                    for ((id, held) in listOf(root to anchor, children.getValue("catalogs") to a, children.getValue("objects") to b)) {
                        open(id).use { LibraryDescriptorAncestryWitness.requireSameIdentity(held, it) }
                    }
                }
            }
        }
    }

    suspend fun verifyIndependentRoots(resolver: ContentResolver, anchor: Uri, left: Uri, right: Uri, read: suspend () -> Unit) {
        fun open(tree: Uri) = openRoot(resolver, tree)
        open(anchor).use { parent ->
            open(left).use { a ->
                open(right).use { b ->
                    if (left == right) LibraryDescriptorAncestryWitness.requireSameIdentity(a, b)
                    LibraryDescriptorAncestryWitness.withRecheckedSiblings(parent, a, b, rebind = {
                        for ((tree, held) in listOf(anchor to parent, left to a, right to b)) {
                            open(tree).use { LibraryDescriptorAncestryWitness.requireSameIdentity(held, it) }
                        }
                    }, read = read)
                }
            }
        }
    }

    private fun openRoot(resolver: ContentResolver, tree: Uri): ParcelFileDescriptor {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.openFileDescriptor(document, "r") ?: throw IOException("Files returned no directory descriptor")
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
