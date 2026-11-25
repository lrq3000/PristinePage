package app.pristinepage.data

import app.pristinepage.data.library.LibraryFolderGrantRecord
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryFolderGrantRecordTest {
    @Test fun retainsExactUriAndReturnedModesWithoutGrantingWrite() {
        val id = "34adcfe8-fc9e-46d0-bc74-da5355276c24"
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FR%C3%A9sum%C3%A9s"
        val binding = LibraryFolderGrantRecord(id, tree, returnedModes = 3)
        val bytes = binding.encode()
        assertEquals(binding, LibraryFolderGrantRecord.decode(bytes, id))
        assertEquals(tree, LibraryFolderGrantRecord.decode(bytes, id).exactUri)
        assertEquals(3, LibraryFolderGrantRecord.decode(bytes, id).returnedModes)
        assertContentEquals(bytes, LibraryFolderGrantRecord.decode(bytes, id).encode())
    }

    @Test fun refusesARecordMovedToAnotherGrantIdOrChangedInPlace() {
        val id = "34adcfe8-fc9e-46d0-bc74-da5355276c24"
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FScans"
        val bytes = LibraryFolderGrantRecord(id, tree, returnedModes = 1).encode()
        val pastedBindingId = "d286658a-2e61-4452-b118-23febea75585"
        assertFailsWith<IOException> { LibraryFolderGrantRecord.decode(bytes, pastedBindingId) }
        val corrupted = bytes.toString(Charsets.US_ASCII).replace("%2FScans", "%2FFiles").encodeToByteArray()
        assertFailsWith<IOException> { LibraryFolderGrantRecord.decode(corrupted, id) }
        assertFailsWith<IOException> { LibraryFolderGrantRecord.decode(bytes + byteArrayOf(10), id) }
    }

    @Test fun willNotPersistRawFilePathsMalformedFieldsOrUnsupportedGrantModes() {
        val id = "34adcfe8-fc9e-46d0-bc74-da5355276c24"
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FScans"
        assertFailsWith<IllegalArgumentException> { LibraryFolderGrantRecord(id, "file:///storage/emulated/0/test", 1) }
        assertFailsWith<IllegalArgumentException> { LibraryFolderGrantRecord(id, "$tree\ninjected", 1) }
        assertFailsWith<IllegalArgumentException> { LibraryFolderGrantRecord(id, tree, 2) }
        assertFailsWith<IllegalArgumentException> { LibraryFolderGrantRecord(id, tree, 9) }
    }
}
