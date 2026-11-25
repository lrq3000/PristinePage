package app.pristinepage.data

import android.database.MatrixCursor
import android.os.Bundle
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.library.LibraryTreeReadSource
import app.pristinepage.data.library.LibraryTreeProviderRows
import app.pristinepage.data.library.withLibraryProviderSignal
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryTreeProviderRowsTest {
    private fun cursor() = MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE))

    @Test fun loadingAndErrorCursorsDoNotBecomeAnEmptyOrPartialFolder() {
        cursor().use {
            it.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            assertThrows(IOException::class.java) { LibraryTreeProviderRows.read(it, maximum = 2) {} }
        }
        cursor().use {
            it.addRow(arrayOf("opaque", "objects", Document.MIME_TYPE_DIR))
            it.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "not complete") }
            assertThrows(IOException::class.java) { LibraryTreeProviderRows.read(it, maximum = 2) {} }
        }
    }

    @Test fun keepsOpaqueIdsAndDistinguishesEmptyFromUnavailable() {
        assertThrows(IOException::class.java) { LibraryTreeProviderRows.read(null, maximum = 2) {} }
        cursor().use {
            assertEquals(emptyList<LibraryTreeReadSource.Entry>(), LibraryTreeProviderRows.read(it, maximum = 2) {})
        }
        cursor().use {
            it.addRow(arrayOf("opaque:abc?123", "generations", Document.MIME_TYPE_DIR))
            it.addRow(arrayOf("file-45", "scans.jpg", "image/jpeg"))
            assertEquals(listOf(
                LibraryTreeReadSource.Entry("opaque:abc?123", "generations", true),
                LibraryTreeReadSource.Entry("file-45", "scans.jpg", false),
            ), LibraryTreeProviderRows.read(it, maximum = 2) {})
        }
    }

    @Test fun oversizedAndMalformedDirectoryRowsAreRefusedBeforeReturningAnInventory() {
        cursor().use {
            it.addRow(arrayOf("ok", "objects", Document.MIME_TYPE_DIR))
            it.addRow(arrayOf("file", "name", "application/octet-stream"))
            assertThrows(IOException::class.java) { LibraryTreeProviderRows.read(it, maximum = 1) {} }
        }
        cursor().use {
            it.addRow(arrayOf("id", null, Document.MIME_TYPE_DIR))
            assertThrows(IOException::class.java) { LibraryTreeProviderRows.read(it, maximum = 2) {} }
        }
    }

    @Test fun providerSignalIsDeliveredWhileTheResolverCallIsStillBlocked() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val signaled = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val task = launch(Dispatchers.IO) {
            withLibraryProviderSignal { signal ->
                signal.setOnCancelListener { signaled.countDown() }
                entered.countDown()
                try {
                    assertTrue("Provider did not receive Android cancellation", signaled.await(5, TimeUnit.SECONDS))
                    signal.throwIfCanceled()
                } catch (_: OperationCanceledException) {
                    throw IOException("Provider reported query cancellation")
                } finally { ended.countDown() }
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        task.cancelAndJoin()
        assertEquals(0L, signaled.count)
        assertEquals(0L, ended.count)
    }
}
