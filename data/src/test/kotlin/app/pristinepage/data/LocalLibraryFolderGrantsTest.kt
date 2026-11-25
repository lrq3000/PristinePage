package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryFolderGrantRecord
import app.pristinepage.data.library.LocalLibraryFolderGrants
import java.io.IOException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocalLibraryFolderGrantsTest {
    @get:Rule val directories = TemporaryFolder()
    private val record = LibraryFolderGrantRecord(
        "34adcfe8-fc9e-46d0-bc74-da5355276c24", "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FScans", 3,
    )
    private val succeeds = DirectorySync { true }

    @Test fun sameIdCannotBeOverwrittenOrRedirectedToAnotherExactUri() {
        val root = directories.newFolder().canonicalFile
        val writer = LocalLibraryFolderGrants(root, succeeds)
        assertNull(writer.readVerified(record.grantId))
        assertEquals(record, writer.writeVerified(record))
        assertEquals(record, writer.writeVerified(record))
        val different = record.copy(exactUri = record.exactUri.replace("Scans", "Other"))
        assertFailsWith<IOException> { writer.writeVerified(different) }
        assertEquals(record, LocalLibraryFolderGrants(root, succeeds).readVerified(record.grantId))
    }

    @Test fun aVisibleBindingAfterSyncFailureIsNotAcknowledgedUntilVerifiedOnReopen() {
        val root = directories.newFolder().canonicalFile
        val directory = root.resolve("folder-grants")
        val final = directory.resolve(record.grantId)
        val failing = LocalLibraryFolderGrants(root, DirectorySync { !final.exists() })
        assertFailsWith<IOException> { failing.writeVerified(record) }
        assertFailsWith<IOException> { failing.readVerified(record.grantId) }
        assertEquals(record, LocalLibraryFolderGrants(root, succeeds).readVerified(record.grantId))
    }

    @Test fun oversizedPrivateLocatorCannotBeTreatedAsGrantAbsence() {
        val root = directories.newFolder().canonicalFile
        val directory = root.resolve("folder-grants").apply { check(mkdir()) }
        directory.resolve(record.grantId).writeBytes(ByteArray(4_353))
        assertFailsWith<IOException> { LocalLibraryFolderGrants(root, succeeds).readVerified(record.grantId) }
    }
}
