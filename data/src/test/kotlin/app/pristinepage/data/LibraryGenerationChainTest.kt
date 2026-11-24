package app.pristinepage.data

import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryGenerationChain
import app.pristinepage.data.library.LibraryObjectTransfer
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryGenerationChainTest {
    private val genesis = generation(1)
    private val second = generation(2, genesis.id)
    private val third = generation(3, second.id)

    @Test fun selectsTheOneTipRegardlessOfDirectoryOrder() {
        assertEquals(third, LibraryGenerationChain.tip(listOf(second, third, genesis)))
        assertEquals(genesis, LibraryGenerationChain.tip(listOf(genesis)))
    }

    @Test fun forksAndDisconnectedOrMissingPredecessorsNeverSelectATip() {
        for (invalid in listOf(
            listOf(genesis, second, generation(4, genesis.id)),
            listOf(genesis, third),
            listOf(second, third),
            listOf(genesis, genesis),
            listOf(genesis, second, third.copy(libraryId = generationKey(50))),
        )) assertThrows(IOException::class.java) { LibraryGenerationChain.tip(invalid) }
    }

    @Test fun emptyInventoriesAndDisconnectedCyclesAreNotAnEmptyLibrary() {
        assertThrows(IOException::class.java) { LibraryGenerationChain.tip(emptyList()) }
        val cycle = listOf(generation(4, generationKey(5)), generation(5, generationKey(4)))
        assertThrows(IOException::class.java) { LibraryGenerationChain.tip(listOf(genesis, second) + cycle) }
    }

    private fun generation(index: Int, predecessor: String? = null) = LibraryGeneration(generationKey(42),
        generationKey(index), predecessor, 8, LibraryObjectTransfer.ObjectId("a".repeat(64), 4096))

    private fun generationKey(index: Int) = "aaaaaaaa-aaaa-4aaa-8aaa-${index.toString().padStart(12, '0')}"
}
