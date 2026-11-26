package app.pristinepage.data

import app.pristinepage.data.library.LibraryPairScratch
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LibraryPairScratchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unresolvedCatalogsRefuseSuccessAndConsumeTheBoundedScratchBudget() {
        val root = temporary.newFolder().canonicalFile.toPath()
        repeat(4) { index ->
            assertFailsWith<IOException> {
                LibraryPairScratch.use(root) { path ->
                    Files.writeString(path.resolve("catalog.sqlite"), "unresolved-$index")
                    "must not be delivered"
                }
            }
        }
        assertFailsWith<IOException> { LibraryPairScratch.use(root) { error("The scratch budget was exceeded") } }
        Files.list(root).use { entries ->
            assertEquals(4, entries.filter { Files.isRegularFile(it.resolve("catalog.sqlite")) }.count().toInt())
        }
    }

    @Test fun anEmptyAttemptClosesBeforeResultDeliveryAndLeavesUnrelatedEvidence() {
        val root = temporary.newFolder().canonicalFile.toPath()
        val unrelated = Files.writeString(root.resolve("retained.txt"), "preserved")
        val observed = LibraryPairScratch.use(root) { path ->
            assertTrue(Files.isDirectory(path))
            path
        }
        assertTrue(Files.notExists(observed))
        assertEquals("preserved", Files.readString(unrelated))
    }
}
