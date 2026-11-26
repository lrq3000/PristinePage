package app.pristinepage.data

import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.library.LibraryTreeUnavailable
import app.pristinepage.data.library.SelectedLibraryTipChanged
import app.pristinepage.data.library.reconnectRecordedRecipe
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class SelectedRecipeReconnectTest {
    private val issuing = LibraryActivation("a31e9cb6-a174-4eca-93f1-d16052180fd3", 9)
    private val successor = issuing.copy(number = 10)

    @Test fun exactRememberedTipReconnectsWithoutEnteringRecovery() = runTest {
        assertEquals(issuing, reconnectRecordedRecipe(issuing, verifyRemembered = {}, recoverVerified = {
            error("Normal Reconnect touched interrupted edit recovery")
        }))
    }

    @Test fun changedTipCanReconnectOnlyAtTheExactSuccessorActivation() = runTest {
        assertEquals(successor, reconnectRecordedRecipe(issuing, verifyRemembered = { throw SelectedLibraryTipChanged() },
            recoverVerified = { successor }))
        for (unproved in listOf(null, issuing, issuing.copy(number = 11), successor.copy(libraryId = "87a46b88-ee69-48b2-a879-153193554198"))) {
            assertFailsWith<LibraryTreeUnavailable> {
                reconnectRecordedRecipe(issuing, verifyRemembered = { throw SelectedLibraryTipChanged() }, recoverVerified = { unproved })
            }
        }
    }

    @Test fun unrelatedReadFailureNeverEntersRecoveryAndIncompleteProofIsNotRetried() = runTest {
        val readFailure = IOException("Provider could not open the remembered catalog")
        val read = assertFailsWith<IOException> {
            reconnectRecordedRecipe(issuing, verifyRemembered = { throw readFailure },
                recoverVerified = { error("Read failure attempted recovery") })
        }
        assertSame(readFailure, read)
        var attempts = 0
        assertFailsWith<IOException> {
            reconnectRecordedRecipe(issuing, verifyRemembered = { throw SelectedLibraryTipChanged() }, recoverVerified = {
                attempts++
                throw IOException("Private completion missing")
            })
        }
        assertEquals(1, attempts)
    }
}
