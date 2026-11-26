package app.pristinepage.data

import app.pristinepage.data.library.LibraryTreeAncestryHint
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryTreeAncestryHintTest {
    @Test fun countsOnlyParentHopsAndUnderstandsTheTwoAospDocumentsSpellings() {
        assertEquals(1 to 1, LibraryTreeAncestryHint.parentHops("home:Example/A", "primary:Documents/Example/B"))
        assertEquals(1 to 2, LibraryTreeAncestryHint.parentHops("primary:Documents/A", "home:Group/B"))
        assertEquals(2 to 2, LibraryTreeAncestryHint.parentHops("home:A", "primary:DCIM/B"))
        assertEquals(2 to 2, LibraryTreeAncestryHint.parentHops("primary:Documents/A", "primary:documents/B"))
    }

    @Test fun rejectsUnusableHintsBeforeOpeningDescriptors() {
        for ((left, right) in listOf("home:A" to "primary:Documents/A/B", "home:A" to "primary:Documents/A",
            "home:A//B" to "home:C", "primary:" to "home:A", "0702-ABCD:A" to "home:A",
            "home:A" to "home:../B", "primary:${List(128) { "x" }.joinToString("/")}" to "home:A",
        )) assertThrows(IOException::class.java) { LibraryTreeAncestryHint.parentHops(left, right) }
    }
}
