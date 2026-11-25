package app.pristinepage.data

import app.pristinepage.data.library.LibraryRootSeparation
import app.pristinepage.data.library.LibraryRootSeparation.Identity
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryRootSeparationTest {
    private val fs = Identity(11, 1)
    private val user = Identity(11, 2)
    private val first = Identity(11, 3)
    private val second = Identity(11, 4)

    @Test fun sharedAncestorsAreNormalButTheSameDirectoryAndEitherNestingDirectionRefuse() {
        val left = listOf(first, user, fs)
        val right = listOf(second, user, fs)
        LibraryRootSeparation.admit({ left }, { right }).requireCurrent()
        assertThrows(IOException::class.java) { LibraryRootSeparation.admit({ left }, { left }) }
        val nested = listOf(second, first, user, fs)
        assertThrows(IOException::class.java) { LibraryRootSeparation.admit({ left }, { nested }) }
        assertThrows(IOException::class.java) { LibraryRootSeparation.admit({ nested }, { left }) }
    }

    @Test fun aChangedAncestorInvalidatesProofEvenWhenTheHeldLeafIdentityDoesNotChange() {
        val left = listOf(first, user, fs)
        var right = listOf(second, user, fs)
        val proof = LibraryRootSeparation.admit({ left }, { right })
        right = listOf(second, Identity(11, 9), fs)
        assertThrows(IOException::class.java) { proof.requireCurrent() }
    }

    @Test fun missingOrCyclicAncestryDoesNotAuthorizeAWrite() {
        assertThrows(IOException::class.java) { LibraryRootSeparation.admit({ emptyList() }, { listOf(second, user, fs) }) }
        assertThrows(IOException::class.java) { LibraryRootSeparation.admit({ listOf(first, user, first, fs) }, { listOf(second, user, fs) }) }
        assertThrows(IOException::class.java) { LibraryRootSeparation.admit({ listOf(first, user, fs) }, { throw IOException("Observation unavailable") }) }
        assertThrows(IOException::class.java) {
            LibraryRootSeparation.admit({ listOf(first, user, fs) }, { listOf(second, Identity(12, 1)) })
        }
    }

    @Test fun aSharedAncestorMustHaveTheSameRemainingAncestryInBothObservations() {
        val left = listOf(first, user, fs)
        val right = listOf(second, user, Identity(11, 9), fs)
        assertThrows(IOException::class.java) { LibraryRootSeparation.admit({ left }, { right }) }
    }

    @Test fun siblingWitnessRequiresBothDirectEdgesToTheSelectedAnchorEvenAfterRebinding() {
        var right = listOf(second, user)
        val exact = LibraryRootSeparation.admit({ listOf(first, user) }, { right })
        exact.requireCurrentSiblings()
        right = listOf(second, fs, user)
        assertThrows(IOException::class.java) { exact.requireCurrentSiblings() }
        val commonAncestorOnly = LibraryRootSeparation.admit({ listOf(first, user) }, { right })
        assertThrows(IOException::class.java) { commonAncestorOnly.requireCurrentSiblings() }
    }
}
