package app.pristinepage.data

import app.pristinepage.data.library.LibraryOriginalScratch
import java.io.IOException
import java.io.FilterOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LibraryOriginalScratchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun repeatedOutputOpenFailuresDoNotSpendTheRetainedScratchBudget() = runTest {
        val root = temporary.newFolder().canonicalFile.toPath()
        repeat(5) {
            assertFailsWith<IOException> {
                LibraryOriginalScratch(root, openOutput = { throw IOException("Synthetic failed open") })
                    .use({}, { error("Failed open reached the writer") }, { error("Failed open reached the decoder") })
            }
        }
        var lends = 0
        LibraryOriginalScratch(root).use({}, { it.write(byteArrayOf(17, 91)) }, {
            lends++
            assertContentEquals(byteArrayOf(17, 91), it.readBytes())
        })
        assertEquals(1, lends)
        assertEquals(emptyList(), Files.newDirectoryStream(root).use { it.toList() })
    }

    @Test fun anUncertainCloseLeavesOnlyItsOwnedFileAndDoesNotLendIt() = runTest {
        val root = temporary.newFolder().canonicalFile.toPath()
        val failing = LibraryOriginalScratch(root, openOutput = { path ->
            object : FilterOutputStream(Files.newOutputStream(path)) {
                override fun close() {
                    super.close()
                    throw IOException("Synthetic unacknowledged close")
                }
            }
        })
        assertFailsWith<IOException> {
            failing.use({}, { it.write(73) }, { error("A file with an uncertain close was lent") })
        }
        val owned = Files.newDirectoryStream(root).use { it.single() }
        assertContentEquals(byteArrayOf(73), Files.readAllBytes(owned.resolve("original.bin")))
    }
}
