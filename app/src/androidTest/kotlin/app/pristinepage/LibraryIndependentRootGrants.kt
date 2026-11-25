package app.pristinepage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals

/** Bounded test handoff; the stored record does not grant access or prove ancestry. */
internal class LibraryIndependentRootGrants private constructor(
    val trees: List<Uri>,
    private val baseline: String,
    private val spellings: Set<Uri>,
) {
    private val held = trees.associate { it.toString() to (true to false) }

    private fun requireInventory(app: Context, expected: Map<String, Pair<Boolean, Boolean>>) {
        val current = inventory(app)
        val names = spellings.map(Uri::toString).toSet()
        assertEquals(expected, current.filterKeys { it in names })
        assertEquals(baseline, LibraryAncestryGrantWitness.fingerprint(current.filterKeys { it !in names }))
    }

    fun requireEffectiveReadOnly(app: Context) {
        requireInventory(app, held)
        refuseEffectiveWrite(app, spellings)
        for (tree in trees) {
            assertEquals(PackageManager.PERMISSION_GRANTED,
                app.checkUriPermission(tree, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    fun releaseOwnReads(app: Context) {
        releaseReadDelta(app, trees) { requireInventory(app, emptyMap()) }
    }

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        private val readMode = true to false

        fun paths(folder: String): List<String> {
            check(folder.matches(Regex("PristinePage-tree-[0-9a-f]{12}")))
            val nonce = folder.substringAfterLast('-')
            return listOf("$folder/Library-Left-$nonce", "$folder/Library-Right-$nonce", folder)
        }

        fun spellings(path: String): Set<Uri> = setOf("primary:Documents/$path", "home:$path")
            .mapTo(linkedSetOf()) { DocumentsContract.buildTreeDocumentUri(AUTHORITY, it) }

        fun inventory(app: Context) = app.contentResolver.persistedUriPermissions
            .associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) }

        /** An unrelated grant or single failing URI must not strand the other provably acquired read modes. */
        fun releaseReadDelta(app: Context, acquired: List<Uri>, verify: () -> Unit) {
            val failures = ArrayList<Throwable>()
            for (tree in acquired.asReversed()) {
                runCatching {
                    // Only a read-only grant absent before this exact Files selection belongs to the witness.
                    if (inventory(app)[tree.toString()] == readMode) {
                        app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }.exceptionOrNull()?.let(failures::add)
            }
            runCatching(verify).exceptionOrNull()?.let(failures::add)
            if (failures.isNotEmpty()) throw AssertionError("Synthetic Files read-grant cleanup is unresolved").apply {
                failures.forEach(::addSuppressed)
            }
        }

        fun refuseEffectiveWrite(app: Context, trees: Iterable<Uri>) = refuseEffectiveMode(app, trees, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        fun refuseParentAccess(app: Context, folder: String) {
            paths(folder)
            val coveringTrees = listOf("primary:", "primary:Documents", "home:").map {
                DocumentsContract.buildTreeDocumentUri(AUTHORITY, it)
            }
            val parentUris = spellings(folder).flatMap { tree ->
                val id = DocumentsContract.getTreeDocumentId(tree)
                listOf(tree, DocumentsContract.buildDocumentUri(AUTHORITY, id),
                    DocumentsContract.buildDocumentUriUsingTree(tree, id)) +
                    coveringTrees.map { DocumentsContract.buildDocumentUriUsingTree(it, id) }
            }
            for (mode in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                for (uri in coveringTrees + parentUris) refuseUriMode(app, uri, mode)
            }
        }

        private fun refuseEffectiveMode(app: Context, trees: Iterable<Uri>, mode: Int) {
            for (tree in trees) {
                val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                for (uri in listOf(tree, document)) refuseUriMode(app, uri, mode)
            }
        }

        private fun refuseUriMode(app: Context, uri: Uri, mode: Int) {
            assertEquals("Unexpected effective permission for the synthetic witness or its parent", PackageManager.PERMISSION_DENIED,
                app.checkUriPermission(uri, Process.myPid(), Process.myUid(), mode))
        }

        private fun record(app: Context, folder: String): File {
            paths(folder)
            return File(app.filesDir.canonicalFile, "independent-ancestry-grants-$folder")
        }

        fun requireNewRecord(app: Context, folder: String) = check(!record(app, folder).exists())

        fun save(app: Context, folder: String, trees: List<Uri>, before: Map<String, Pair<Boolean, Boolean>>) {
            requireExact(trees, folder)
            check(inventory(app) == before + trees.associate { it.toString() to readMode })
            val target = record(app, folder)
            check(target.createNewFile())
            val payload = (listOf(Process.myPid().toString()) + trees.map(Uri::toString) +
                LibraryAncestryGrantWitness.fingerprint(before)).joinToString("\n", postfix = "\n")
            FileOutputStream(target).use {
                it.write(payload.toByteArray(Charsets.US_ASCII))
                it.fd.sync()
            }
        }

        fun reopen(app: Context, folder: String): LibraryIndependentRootGrants {
            val target = record(app, folder)
            check(target.length() in 1L..1_024L)
            val lines = target.readText(Charsets.US_ASCII).split('\n')
            check(lines.size == 6 && lines.last().isEmpty() && lines[4].matches(Regex("[a-f0-9]{64}")))
            check(lines[0].matches(Regex("[1-9][0-9]{0,8}")) && lines[0].toInt() != Process.myPid())
            val trees = lines.subList(1, 4).map(Uri::parse)
            requireExact(trees, folder)
            return LibraryIndependentRootGrants(trees, lines[4], paths(folder).flatMap(::spellings).toSet())
        }

        private fun requireExact(trees: List<Uri>, folder: String) {
            val paths = paths(folder)
            check(trees.size == paths.size && trees.zip(paths).all { (tree, path) -> tree in spellings(path) })
        }
    }
}
