package app.pristinepage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.library.LibraryFolderPickerContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryFolderPickerContractTest {
    @Test fun retainsTheActualEncodedTreeAndBothReturnedModes() {
        val contract = LibraryFolderPickerContract()
        val exact = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FScans")
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persist = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val result = contract.parseResult(Activity.RESULT_OK, Intent().setData(exact).setFlags(read or write or persist)) as LibraryFolderPickerContract.Picked
        assertEquals(exact.toString(), result.uri.toString())
        assertTrue(result.grant.returnedWrite)
        val readOnly = contract.parseResult(Activity.RESULT_OK, Intent().setData(exact).setFlags(read or persist)) as LibraryFolderPickerContract.Picked
        assertFalse(readOnly.grant.returnedWrite)
    }

    @Test fun rejectsDocumentUrisAndCancelledOrNonpersistentSelections() {
        val contract = LibraryFolderPickerContract()
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FScans")
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val persist = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(LibraryFolderPickerContract.Canceled, contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(tree).setFlags(read or persist)))
        assertEquals(LibraryFolderPickerContract.Rejected.MISSING_PERSISTENT_READ, contract.parseResult(Activity.RESULT_OK, Intent().setData(tree).setFlags(read)))
        assertEquals(LibraryFolderPickerContract.Rejected.UNSUPPORTED_TREE,
            contract.parseResult(Activity.RESULT_OK, Intent().setData(tree.buildUpon().appendPath("document").appendPath("other").build()).setFlags(read or persist)))
    }
}
