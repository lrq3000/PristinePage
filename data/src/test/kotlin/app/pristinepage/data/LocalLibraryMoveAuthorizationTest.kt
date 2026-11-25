package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.LibraryNamedPublication
import app.pristinepage.data.library.LocalLibraryMoveAuthorization
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LocalLibraryMoveAuthorizationTest {
    @get:Rule val roots = TemporaryFolder()
    private val leaf = "a".repeat(64)
    private val proof = LibraryNamedPublication.FileProof("observation-24", "1".repeat(64), 21)
    private val parents = "b".repeat(64)
    private val sync = DirectorySync { true }

    @Test fun aNewJournalOwnerCannotSpendTheSameLeafTwiceOrRebindIt() {
        val root = roots.newFolder().canonicalFile
        val first = LocalLibraryMoveAuthorization(root, sync)
        assertFalse(first.isConsumed(leaf, parents, proof))
        first.consume(leaf, parents, proof)
        val fresh = LocalLibraryMoveAuthorization(root, sync)
        assertTrue(fresh.isConsumed(leaf, parents, proof))
        assertFailsWith<IOException> { fresh.consume(leaf, parents, proof) }
        assertFailsWith<IOException> { fresh.consume(leaf, parents, proof.copy(identity = "new-observation")) }
        assertFailsWith<IOException> { fresh.isConsumed(leaf, "c".repeat(64), proof) }
        assertFalse(fresh.isConsumed("d".repeat(64), parents, proof))
    }

    @Test fun aVisibleSpendWithoutDirectoryAcknowledgmentCannotAuthorizeAMoveOrBeReissued() {
        val root = roots.newFolder().canonicalFile
        val failing = DirectorySync { directory -> !File(directory, "$leaf.spent").exists() }
        assertFailsWith<IOException> { LocalLibraryMoveAuthorization(root, failing).consume(leaf, parents, proof) }
        assertFailsWith<IOException> { LocalLibraryMoveAuthorization(root, failing).isConsumed(leaf, parents, proof) }
        val recovered = LocalLibraryMoveAuthorization(root, sync)
        assertTrue(recovered.isConsumed(leaf, parents, proof))
        assertFailsWith<IOException> { recovered.consume(leaf, parents, proof) }
    }

    @Test fun aMalformedSpendCannotBeReplacedByAnotherAuthorization() {
        val root = roots.newFolder().canonicalFile
        val journal = LocalLibraryMoveAuthorization(root, sync)
        val record = File(root, "$leaf.spent")
        journal.consume(leaf, parents, proof)
        val valid = record.readBytes()
        val altered = valid.copyOf().apply { this[0] = 'X'.code.toByte() }
        for (bad in listOf(byteArrayOf(), valid.copyOf(valid.size - 1), altered, valid + 0.toByte())) {
            record.writeBytes(bad)
            assertFailsWith<IOException> { journal.isConsumed(leaf, parents, proof) }
            assertFailsWith<IOException> { journal.consume(leaf, parents, proof) }
            assertContentEquals(bad, record.readBytes())
        }
    }

    @Test fun aFailedBarrierBeforeCreationDoesNotCreateASpend() {
        val root = roots.newFolder().canonicalFile
        assertFailsWith<IOException> { LocalLibraryMoveAuthorization(root, DirectorySync { false }).consume(leaf, parents, proof) }
        assertTrue(root.list().orEmpty().isEmpty())
    }
}
