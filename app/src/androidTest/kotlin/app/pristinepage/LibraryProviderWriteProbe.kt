package app.pristinepage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.FileNotFoundException

/** Test-only qualification. All provider mutations are inside one newly created subdirectory of the synthetic fixture. */
internal object LibraryProviderWriteProbe {
    private const val PROBE = "isolated-write-probe"

    fun verify(resolver: ContentResolver, tree: Uri) {
        val held = { resolver.persistedUriPermissions.singleOrNull { it.uri.toString() == tree.toString() } }
        check(held()?.let { it.isReadPermission && !it.isWritePermission } == true)
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(tree, write)
        try {
            check(held()?.let { it.isReadPermission && it.isWritePermission } == true)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val probe = create(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, PROBE)
            check(displayName(resolver, probe) == PROBE)
            val sentinel = create(resolver, probe, "application/octet-stream", "keep.bin")
            check(displayName(resolver, sentinel) == "keep.bin")
            val sentinelBytes = byteArrayOf(7, 19, 71, 42)
            val sentinelIdentity = writeAndSync(resolver, sentinel, sentinelBytes)
            syncDirectory(resolver, probe)

            // Stage under the final basename in a separate directory. API 28's rename calls can throw after changing the name.
            val stageRoot = create(resolver, probe, DocumentsContract.Document.MIME_TYPE_DIR, "stage")
            check(displayName(resolver, stageRoot) == "stage")
            val staged = create(resolver, stageRoot, "application/octet-stream", "published.bin")
            check(displayName(resolver, staged) == "published.bin")
            val bytes = byteArrayOf(11, 73, 123, 1, 100)
            val stagedIdentity = writeAndSync(resolver, staged, bytes)
            check(read(resolver, staged, bytes.size).contentEquals(bytes))
            syncDirectory(resolver, stageRoot)
            check(children(resolver, probe) == setOf("keep.bin", "stage"))
            val published = requireChild(probe, checkNotNull(DocumentsContract.moveDocument(resolver, staged, stageRoot, probe)))
            check(displayName(resolver, published) == "published.bin")
            syncDirectory(resolver, stageRoot)
            syncDirectory(resolver, probe)
            check(children(resolver, stageRoot).isEmpty())
            check(children(resolver, probe) == setOf("keep.bin", "stage", "published.bin"))
            check(identity(resolver, published) == stagedIdentity)
            check(read(resolver, published, bytes.size).contentEquals(bytes))

            val colliding = create(resolver, stageRoot, "application/octet-stream", "keep.bin")
            check(displayName(resolver, colliding) == "keep.bin")
            val conflictIdentity = writeAndSync(resolver, colliding, bytes)
            val conflictRefused = try { DocumentsContract.moveDocument(resolver, colliding, stageRoot, probe) == null }
                catch (_: FileNotFoundException) { true }
                catch (_: IllegalStateException) { true }
            check(conflictRefused)
            syncDirectory(resolver, stageRoot)
            syncDirectory(resolver, probe)
            syncDirectory(resolver, root)
            check(children(resolver, stageRoot) == setOf("keep.bin"))
            check(children(resolver, probe) == setOf("keep.bin", "stage", "published.bin"))
            check(identity(resolver, colliding) == conflictIdentity)
            check(read(resolver, colliding, bytes.size).contentEquals(bytes))
            check(identity(resolver, sentinel) == sentinelIdentity)
            check(read(resolver, sentinel, sentinelBytes.size).contentEquals(sentinelBytes))
            check(identity(resolver, published) == stagedIdentity)
            check(read(resolver, published, bytes.size).contentEquals(bytes))
        } finally {
            if (held()?.isWritePermission == true) resolver.releasePersistableUriPermission(tree, write)
        }
        check(held()?.let { it.isReadPermission && !it.isWritePermission } == true)
    }

    private fun create(resolver: ContentResolver, parent: Uri, mime: String, name: String): Uri =
        requireChild(parent, checkNotNull(DocumentsContract.createDocument(resolver, parent, mime, name)))

    private fun requireChild(parent: Uri, child: Uri): Uri {
        check(DocumentsContract.isTreeUri(child) && child.authority == parent.authority &&
            DocumentsContract.getTreeDocumentId(child) == DocumentsContract.getTreeDocumentId(parent))
        val id = DocumentsContract.getDocumentId(child)
        val prefix = "${DocumentsContract.getDocumentId(parent)}/"
        val leaf = id.removePrefix(prefix)
        check(id.startsWith(prefix) && leaf.isNotEmpty() && '/' !in leaf && leaf != "." && leaf != "..")
        return child
    }

    private fun displayName(resolver: ContentResolver, document: Uri): String =
        checkNotNull(resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            check(it.count == 1 && it.moveToFirst())
            it.getString(0)
        })

    private fun children(resolver: ContentResolver, parent: Uri): Set<String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        return checkNotNull(resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            check(it.count <= 4)
            val names = mutableSetOf<String>()
            while (it.moveToNext()) check(names.add(it.getString(0)))
            names
        })
    }

    private fun writeAndSync(resolver: ContentResolver, document: Uri, contents: ByteArray): Pair<Long, Long> =
        ParcelFileDescriptor.AutoCloseOutputStream(checkNotNull(resolver.openFileDescriptor(document, "w"))).use { stream ->
            stream.write(contents)
            stream.flush()
            Os.fsync(stream.fd)
            Os.fstat(stream.fd).let {
                check(OsConstants.S_ISREG(it.st_mode) && it.st_size == contents.size.toLong())
                it.st_dev to it.st_ino
            }
        }

    private fun identity(resolver: ContentResolver, document: Uri): Pair<Long, Long> =
        checkNotNull(resolver.openFileDescriptor(document, "r")).use { Os.fstat(it.fileDescriptor).let { stat -> stat.st_dev to stat.st_ino } }

    private fun read(resolver: ContentResolver, document: Uri, expectedSize: Int): ByteArray =
        checkNotNull(resolver.openInputStream(document)).use { stream ->
            val result = ByteArray(expectedSize)
            DataInputStream(stream).readFully(result)
            check(stream.read() == -1)
            result
        }

    private fun syncDirectory(resolver: ContentResolver, document: Uri) =
        checkNotNull(resolver.openFileDescriptor(document, "r")).use {
            check(OsConstants.S_ISDIR(Os.fstat(it.fileDescriptor).st_mode))
            Os.fsync(it.fileDescriptor)
        }
}
