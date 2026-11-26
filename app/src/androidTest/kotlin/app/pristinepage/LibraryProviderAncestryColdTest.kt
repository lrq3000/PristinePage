package app.pristinepage

import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.library.AndroidLibraryTreeRead
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** No Activity or chooser: reuse only the synthetic read grant retained by the preceding instrumentation. */
@RunWith(AndroidJUnit4::class)
class LibraryProviderAncestryColdTest {
    @Test fun twoIndependentFilesRootsRequireDisjointPhysicalAncestry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val folder = requireNotNull(InstrumentationRegistry.getArguments().getString("libraryFixtureFolder"))
        val selection = LibraryIndependentRootGrants.reopen(app, folder)
        val resolver = app.contentResolver
        try {
            selection.requireEffectiveReadOnly(app)
            val (left, right, anchor) = selection.trees
            val scratch = File(app.filesDir.canonicalFile, "tree-read-${UUID.randomUUID()}").apply { check(mkdir()) }
            val reader = AndroidLibraryTreeRead(app, scratch)
            runBlocking {
                LibraryProviderDirectoryProbe.verifyIndependentRoots(resolver, anchor, left, right) {
                    // Both checks occur while the explicit provider descriptors stay held.
                    for (tree in listOf(left, right)) {
                        selection.requireEffectiveReadOnly(app)
                        val catalog = reader.verify(tree)
                        assertEquals("9e940d7b-76c7-45ea-b5e4-0ad86f26ea13", catalog.libraryId)
                        assertEquals("25c49d9b-02d8-418f-b982-154980d89cfb", catalog.generationId)
                        assertEquals(1, catalog.albums)
                        assertEquals(1, catalog.scans)
                    }
                }
            }
            val alias = assertThrows(IOException::class.java) { runBlocking {
                LibraryProviderDirectoryProbe.verifyIndependentRoots(resolver, anchor, left, left) {
                    error("An alias reached the catalog callback")
                }
            } }
            assertEquals("Library folders overlap or have no proven shared storage anchor", alias.message)
            selection.requireEffectiveReadOnly(app)
        } finally {
            selection.releaseOwnReads(app)
        }
    }

    @Test fun provesManagedAncestryWithoutEffectiveWrite() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val folder = requireNotNull(InstrumentationRegistry.getArguments().getString("libraryFixtureFolder"))
        check(folder.matches(Regex("PristinePage-tree-[0-9a-f]{12}")))
        val authority = "com.android.externalstorage.documents"
        val spellings = listOf("home:$folder", "primary:Documents/$folder").map {
            DocumentsContract.buildTreeDocumentUri(authority, it)
        }
        val resolver = app.contentResolver
        val (selected, baseline) = LibraryAncestryGrantWitness.read(app, folder)
        val uri = spellings.single { DocumentsContract.getTreeDocumentId(it).substringBefore(':') == selected }
        fun grants() = resolver.persistedUriPermissions.associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) }
        fun requireInventory(held: Boolean) {
            val current = grants()
            check(current.filterKeys { key -> spellings.any { it.toString() == key } } ==
                if (held) mapOf(uri.toString() to (true to false)) else emptyMap())
            assertEquals("Unrelated persisted URI grants changed", baseline,
                LibraryAncestryGrantWitness.fingerprint(current - uri.toString()))
        }
        requireInventory(held = true)
        try {
            fun requireReadOnly() {
                requireInventory(held = true)
                assertEquals(PackageManager.PERMISSION_GRANTED,
                    app.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
                for (tree in spellings) {
                    assertEquals("Files retained effective write access in the cold read-only probe", PackageManager.PERMISSION_DENIED,
                        app.checkUriPermission(tree, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                }
            }
            requireReadOnly()
            val scratch = File(app.filesDir.canonicalFile, "tree-read-${UUID.randomUUID()}").apply { check(mkdir()) }
            val closed = runBlocking { AndroidLibraryTreeRead(app, scratch).verify(uri) }
            assertEquals("9e940d7b-76c7-45ea-b5e4-0ad86f26ea13", closed.libraryId)
            assertEquals("25c49d9b-02d8-418f-b982-154980d89cfb", closed.generationId)
            assertEquals(1, closed.scans)
            LibraryProviderDirectoryProbe.verify(resolver, uri)
            try {
                LibraryProviderDirectoryProbe.verifyAncestry(resolver, uri)
            } catch (failure: IOException) {
                throw IOException("Read-only descriptor control passed, but managed ancestry was refused", failure)
            }
            requireReadOnly()
        } finally {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            requireInventory(held = false)
        }
    }
}

/** Persist only the fixture namespace and a fingerprint of the untouched grants, never their URIs. */
internal object LibraryAncestryGrantWitness {
    private fun file(app: Context, folder: String): File {
        check(folder.matches(Regex("PristinePage-tree-[0-9a-f]{12}")))
        return File(app.filesDir.canonicalFile, "ancestry-grant-$folder")
    }

    fun fingerprint(grants: Map<String, Pair<Boolean, Boolean>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((key, modes) in grants.toSortedMap()) {
            digest.update("${key.length}:$key:${modes.first}:${modes.second}\n".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun save(app: Context, folder: String, tree: Uri, before: Map<String, Pair<Boolean, Boolean>>) {
        val target = file(app, folder)
        check(target.createNewFile())
        val namespace = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
        check(namespace == "home" || namespace == "primary")
        FileOutputStream(target).use {
            it.write("$namespace\n${fingerprint(before)}\n".toByteArray(Charsets.US_ASCII))
            it.fd.sync()
        }
    }

    fun read(app: Context, folder: String): Pair<String, String> {
        val target = file(app, folder)
        check(target.length() in 70L..73L)
        val fields = Regex("(home|primary)\n([a-f0-9]{64})\n").matchEntire(target.readText(Charsets.US_ASCII))
        checkNotNull(fields)
        return fields.groupValues[1] to fields.groupValues[2]
    }
}
