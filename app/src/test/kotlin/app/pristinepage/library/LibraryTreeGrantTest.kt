package app.pristinepage.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LibraryTreeGrantTest {
    @Test fun readOnlyHandoffDoesNotPersistExtraReturnedWriteModeOrRetakeAnExactExistingGrant() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FScans"
        val existingChild = "$tree%2FExisting"
        val modes = listOf(LibraryTreeGrant.Held(existingChild, read = true, write = true))
        val candidate = LibraryTreeGrant.inspect(tree, returnedRead = true, returnedWrite = true, persistable = true)
        assertTrue(candidate.returnedWrite)
        assertTrue(candidate.needsReadGrant(modes))
        assertFalse(candidate.needsReadGrant(modes + LibraryTreeGrant.Held(tree, read = true, write = false)))
        assertTrue(candidate.needsReadGrant(modes + LibraryTreeGrant.Held(tree, read = false, write = true)))
        assertEquals(tree, candidate.exactUri)
    }

    @Test fun refusesTransientOrWriteOnlyPickerResults() {
        assertThrows(IOException::class.java) { LibraryTreeGrant.inspect("tree", true, false, false) }
        assertThrows(IOException::class.java) { LibraryTreeGrant.inspect("tree", false, true, true) }
    }

    @Test fun exactPermissionCannotBeReplacedByAnAncestorOrAnEquivalentSpelling() {
        val exact = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FScans"
        val candidate = LibraryTreeGrant.inspect(exact, true, false, true)
        val other = listOf("content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            exact.replace("%2F", "%2f"), "$exact/document/primary%3ADocuments%2FScans")
        for (uri in other) {
            val held = listOf(LibraryTreeGrant.Held(uri, true, true))
            assertThrows(IOException::class.java) { candidate.requirePersistedRead(held) }
        }
        candidate.requirePersistedRead(listOf(LibraryTreeGrant.Held(exact, true, false)))
    }

    @Test fun explicitWritePersistsOnlyMissingModesForTheExactReturnedTree() {
        val home = "content://com.android.externalstorage.documents/tree/home%3AScans"
        val alias = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FScans"
        val offered = LibraryTreeGrant.inspect(home, true, true, true)
        val other = LibraryTreeGrant.Held(alias, read = true, write = true)
        val readOnly = LibraryTreeGrant.Held(home, read = true, write = false)
        assertEquals(3, offered.missingWriteModes(listOf(other)))
        assertEquals(2, offered.missingWriteModes(listOf(other, readOnly)))
        assertEquals(0, offered.missingWriteModes(listOf(readOnly.copy(write = true))))
        assertThrows(IOException::class.java) { offered.requirePersistedWrite(listOf(other, readOnly)) }
        assertThrows(IOException::class.java) {
            LibraryTreeGrant.inspect(home, true, false, true).missingWriteModes(listOf(readOnly.copy(write = true)))
        }
    }
}
