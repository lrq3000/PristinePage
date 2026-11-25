package app.pristinepage

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.library.AndroidLibraryBackupHistory
import app.pristinepage.data.library.AndroidLibraryStartup
import app.pristinepage.data.library.LibraryFolderExportState
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Headless cold lookup: do not start the ordinary Activity, writable Room graph, or Android Files. */
@RunWith(AndroidJUnit4::class)
class LibraryBackupDeathRecoveryTest {
    @Test fun coldProcessInspectsInterruptedDeliveryWithoutReplay() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val arguments = InstrumentationRegistry.getArguments()
        fun uuid(key: String): String = checkNotNull(arguments.getString(key)).also { require(UUID.fromString(it).toString() == it) }
        val operation = uuid("libraryBackupDeathOperation")
        val generation = uuid("libraryBackupDeathGeneration")
        val library = uuid("libraryBackupDeathLibrary")
        val deadPid = checkNotNull(arguments.getString("libraryBackupDeathPreviousPid")?.toIntOrNull())
        val parent = checkNotNull(arguments.getString("libraryFixtureFolder")).also { require(it.matches(Regex("PristinePage-tree-[a-f0-9]{12}"))) }
        assertTrue(deadPid > 0 && Process.myPid() != deadPid)
        fun assertNoWriteGrant() {
            assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
            for (id in listOf("primary:Documents/$parent", "home:$parent")) {
                val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", id)
                assertEquals(PackageManager.PERMISSION_DENIED,
                    app.checkUriPermission(tree, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }
        }
        assertNoWriteGrant()
        val files = File(app.filesDir.canonicalFile, "library-export-source-$operation/files")
        assertTrue(files.isDirectory && files.canonicalFile == files)
        val cold = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = error("Cold receipt inspection opened Room")
            override fun getContentResolver(): ContentResolver = error("Cold receipt inspection accessed the provider")
        }
        val activation = checkNotNull(AndroidLibraryStartup(cold).recordedDefaultActivation())
        val history = AndroidLibraryBackupHistory(cold)
        val discovery = history.discoverActive(activation)
        assertEquals(0, discovery.unresolvedCount)
        val recovered = discovery.receipts.single() as LibraryFolderExportState.Completed
        assertEquals(operation, recovered.receipt.operationId)
        assertEquals(library, recovered.receipt.libraryId)
        assertEquals(generation, recovered.receipt.generationId)
        assertEquals("PristinePage-backup-$generation", recovered.receipt.folderName)
        assertEquals(recovered, history.inspect(operation, activation))
        val refusal = runCatching { history.preflightNew(operation, activation) }.exceptionOrNull()
        assertTrue(refusal is IOException)
        assertEquals("This backup operation cannot be repeated", refusal?.message)
        assertNoWriteGrant()
    }
}
