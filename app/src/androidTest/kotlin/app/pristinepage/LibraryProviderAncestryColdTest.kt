package app.pristinepage

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.library.AndroidLibraryTreeRead
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** No Activity or chooser: reuse only the synthetic read grant retained by the preceding instrumentation. */
@RunWith(AndroidJUnit4::class)
class LibraryProviderAncestryColdTest {
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
        val grant = resolver.persistedUriPermissions.single().also {
            check(it.isReadPermission && !it.isWritePermission && it.uri in spellings)
        }
        val uri = grant.uri
        try {
            fun requireReadOnly() {
                val current = resolver.persistedUriPermissions.single()
                check(current.uri == uri && current.isReadPermission && !current.isWritePermission)
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
            check(resolver.persistedUriPermissions.isEmpty())
        }
    }
}
