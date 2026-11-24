package app.pristinepage.core

import app.pristinepage.core.library.LibraryScanName
import org.junit.Assert.*
import org.junit.Test

class LibraryScanNameTest {
    private val pageId = "16085bae-ae25-4fa4-8c69-9a8982ef6c13"

    @Test fun validPickedDisplayNameIsPreservedAsALabel() {
        assertEquals("École 01.JPG", LibraryScanName.forIngest("  École 01.JPG  ", pageId, "image/jpeg", emptyList()))
    }

    @Test fun captureAndUnsafeOrMissingNamesUseTheSameOpaqueIdentityFallback() {
        for (label in listOf(null, ".", "../report.png", "directory\\report.png", "\u0000file.png", "a".repeat(161))) {
            assertEquals("Scan 16085bae-ae25-4fa4-8c69-9a8982ef6c13.png",
                LibraryScanName.forIngest(label, pageId, "image/png", emptyList()))
        }
    }

    @Test fun collisionRenamingFollowsTheSameComparisonAsTheMergePlanner() {
        assertEquals("ς (3).jpg", LibraryScanName.forIngest("ς.jpg", pageId, "image/jpeg", listOf("Σ.JPG", "Σ (2).jpg")))
    }

    @Test fun repeatedOriginalsHaveDistinctSlotsWithoutSplittingSurrogates() {
        val original = "a".repeat(151) + "😀" + "bbb.jpg"
        val name = LibraryScanName.forIngest(original, pageId, "image/jpeg", listOf(original))
        assertEquals("a".repeat(151) + " (2).jpg", name)
        assertEquals(159, name.length)
    }

    @Test fun onlyTheAdmittedEncodedMimeCanChooseAnExtensionForTheFallback() {
        assertThrows(IllegalArgumentException::class.java) { LibraryScanName.forIngest(null, pageId, "application/pdf", emptyList()) }
    }
}
