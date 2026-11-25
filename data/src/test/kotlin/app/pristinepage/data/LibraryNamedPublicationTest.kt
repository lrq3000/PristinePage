package app.pristinepage.data

import app.pristinepage.data.library.LibraryNamedPublication
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryNamedPublicationTest {
    @get:Rule val roots = TemporaryFolder()
    private val original = byteArrayOf(10, 42, 63, 91)

    @Test fun anUncertainMoveCanBeAcknowledgedWithoutRepeatingTheMutation() {
        val store = fixture().apply { loseResponse = true }
        val expected = checkNotNull(store.readStage())
        assertFailsWith<IOException> { LibraryNamedPublication(store).commit(expected, reconcileOnly = false) }
        assertEquals(1, store.moves)
        assertFalse(Files.exists(store.stage))
        assertContentEquals(original, Files.readAllBytes(store.final))
        assertEquals(emptyList(), store.barriers)
        LibraryNamedPublication(store).commit(expected, reconcileOnly = true)
        assertEquals(1, store.moves)
        assertEquals(listOf("stage", "target"), store.barriers)
    }

    @Test fun aDifferentExistingFileWithIdenticalBytesCannotBeAdoptedOrOverwritten() {
        val store = fixture()
        Files.write(store.final, original)
        val expected = checkNotNull(store.readStage())
        for (reconcile in listOf(false, true)) {
            assertFailsWith<IOException> { LibraryNamedPublication(store).commit(expected, reconcileOnly = reconcile) }
        }
        assertEquals(0, store.moves)
        assertContentEquals(original, Files.readAllBytes(store.stage))
        assertContentEquals(original, Files.readAllBytes(store.final))
    }

    @Test fun failedDurabilityKeepsTheActualFinalFileButCannotAcknowledgeIt() {
        val store = fixture().apply { failFinalBarrier = true }
        val expected = checkNotNull(store.readStage())
        assertFailsWith<IOException> { LibraryNamedPublication(store).commit(expected, reconcileOnly = false) }
        assertEquals(1, store.moves)
        assertTrue(Files.exists(store.final))
        assertFailsWith<IOException> { LibraryNamedPublication(store).commit(expected, reconcileOnly = true) }
        store.failFinalBarrier = false
        LibraryNamedPublication(store).commit(expected, reconcileOnly = true)
        assertEquals(1, store.moves)
    }

    @Test fun aRecoveryOnlyReadCannotPublishAStillStagedPayload() {
        val store = fixture()
        assertFailsWith<IOException> {
            LibraryNamedPublication(store).commit(checkNotNull(store.readStage()), reconcileOnly = true)
        }
        assertEquals(0, store.moves)
        assertFalse(Files.exists(store.final))
    }

    @Test fun readbackAfterTheBarriersRejectsAReplacementWithIdenticalBytes() {
        val store = fixture().apply { replaceOnBarrier = true }
        val expected = checkNotNull(store.readStage())
        assertFailsWith<IOException> { LibraryNamedPublication(store).commit(expected, reconcileOnly = false) }
        assertEquals(1, store.moves)
        assertContentEquals(original, Files.readAllBytes(store.final))
    }

    @Test fun cancellationAfterStageObservationDoesNotStartTheFinalLookup() {
        val store = fixture()
        val expected = checkNotNull(store.readStage())
        var canceled = false
        store.afterStage = { canceled = true }
        assertFailsWith<CancellationException> {
            LibraryNamedPublication(store) { if (canceled) throw CancellationException() }.commit(expected, false)
        }
        assertEquals(0, store.finalReads)
        assertEquals(0, store.moves)
    }

    @Test fun aFailedMoveWithNoVisibleSideEffectStillConsumesTheSingleAuthorization() {
        val store = fixture().apply { failBeforeMove = true }
        val expected = checkNotNull(store.readStage())
        assertFailsWith<IOException> { LibraryNamedPublication(store).commit(expected, false) }
        assertFailsWith<IOException> { LibraryNamedPublication(store).commit(expected, false) }
        assertEquals(1, store.moves)
        assertFalse(Files.exists(store.final))
    }

    @Test fun reconciliationRejectsAnUnrelatedFinalEvenWhenTheStageNameIsAbsentAndBytesMatch() {
        val store = fixture()
        val expected = checkNotNull(store.readStage())
        Files.move(store.stage, store.stage.resolveSibling("retained"))
        Files.write(store.final, original)
        assertFailsWith<IOException> { LibraryNamedPublication(store).commit(expected, true) }
        assertEquals(emptyList(), store.barriers)
    }

    private fun fixture(): LocalStore {
        val root = roots.newFolder().toPath()
        val stage = Files.createDirectory(root.resolve("stage")).resolve("object")
        val final = Files.createDirectory(root.resolve("target")).resolve("object")
        Files.write(stage, original)
        val identityWitness = Files.createLink(root.resolve("identity-witness"), stage)
        return LocalStore(stage, final, identityWitness)
    }

    private class LocalStore(val stage: Path, val final: Path, private val identityWitness: Path) : LibraryNamedPublication.Store {
        var moves = 0
        var finalReads = 0
        var loseResponse = false
        var failBeforeMove = false
        var failFinalBarrier = false
        var replaceOnBarrier = false
        var afterStage: () -> Unit = {}
        val barriers = mutableListOf<String>()
        private var authorizationConsumed = false

        override fun readStage() = read(stage).also { afterStage() }
        override fun readFinal() = read(final).also { finalReads++ }
        override fun consumeMoveAuthorization() {
            if (authorizationConsumed) throw IOException("Synthetic attempt already consumed")
            authorizationConsumed = true
        }
        override fun moveOnce() {
            moves++
            if (failBeforeMove) throw IOException("Synthetic refusal before any visible change")
            Files.move(stage, final)
            if (loseResponse) throw IOException("Synthetic lost response")
        }

        override fun syncStageParent() { barriers += "stage" }
        override fun syncFinalParent() {
            barriers += "target"
            if (failFinalBarrier) throw IOException("Synthetic failed directory barrier")
            if (replaceOnBarrier) {
                val replacement = Files.write(final.resolveSibling("replacement"), Files.readAllBytes(final))
                Files.move(replacement, final, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun read(file: Path): LibraryNamedPublication.FileProof? {
            if (Files.notExists(file)) return null
            val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            // The host test proves that move and replacement differ by identity without assuming Windows exposes NIO file keys.
            val identity = if (Files.isSameFile(file, identityWitness)) "owned" else "unrelated"
            return LibraryNamedPublication.FileProof(identity, hash, Files.size(file))
        }
    }
}
