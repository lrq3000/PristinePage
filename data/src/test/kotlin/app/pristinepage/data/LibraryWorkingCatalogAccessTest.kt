package app.pristinepage.data

import app.pristinepage.data.library.LibraryProviderAttempt
import app.pristinepage.data.library.LibraryWorkingCatalogAccess
import app.pristinepage.data.library.LibraryTreeUnavailable
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class LibraryWorkingCatalogAccessTest {
    @Test fun aBusyProviderLaneDoesNotInvalidateTheCatalog() = runTest {
        val gate = LibraryWorkingCatalogAccess { LibraryProviderAttempt.run {} }
        LibraryProviderAttempt.run {
            assertFailsWith<IOException> { gate.read { error("The busy lane must refuse the read") } }
        }
        assertEquals("still selected", gate.read { "still selected" })
        gate.retire {}
    }

    @Test fun activatingAnotherRootWhilePreparingAFileRefusesBeforeLendingIt() = runTest {
        var current = true
        val refused = IOException("The selection changed")
        val gate = LibraryWorkingCatalogAccess { if (!current) throw refused }
        val result = assertFailsWith<IOException> {
            gate.read {
                current = false
                gate.requireLoanDelivery()
                error("File reached a callback after selection changed")
            }
        }
        assertEquals(refused, result)
        gate.retire {}
    }

    @Test fun definiteLoanLossCannotBeForgottenWhenTheExactPermissionReturns() = runTest {
        val gate = LibraryWorkingCatalogAccess()
        val refused = LibraryTreeUnavailable("The exact grant was lost during decode")
        assertEquals(refused, assertFailsWith<IOException> {
            gate.read { throw refused }
        })
        assertEquals(refused, assertFailsWith<IOException> { gate.read { error("A lost read scope revived") } })
        gate.retire {}
    }

    @Test fun anObservedLossPermanentlyInvalidatesTheWorkingScope() = runTest {
        var current = false
        val refused = IOException("The selection changed")
        val gate = LibraryWorkingCatalogAccess {
            if (!current) throw refused
        }
        assertEquals(refused, assertFailsWith<IOException> { gate.requireCurrent() })
        current = true
        assertEquals(refused, assertFailsWith<IOException> { gate.read { error("A lost scope must not revive") } })
        assertEquals(refused, assertFailsWith<IOException> { gate.requireCurrent() })
        gate.retire {}
    }

    @Test fun aSuccessorPublishedDuringAnAdmittedReadPreventsStaleRowsFromBeingReturned() = runTest {
        var current = true
        val gate = LibraryWorkingCatalogAccess {
            if (!current) throw IOException("The verified library generation changed")
        }
        assertEquals("first", gate.read { "first" })
        assertFailsWith<IOException> {
            gate.read {
                current = false
                "stale rows must not escape"
            }
        }
        assertFailsWith<IOException> { gate.read { error("The successor must refuse before Room reads again") } }
        gate.retire {}
    }

    @Test fun retiringRevokesAdmissionBeforeAnAlreadyAdmittedReadCanFinish() = runTest {
        val gate = LibraryWorkingCatalogAccess()
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        val escaped = async {
            assertFailsWith<IOException> {
                gate.read {
                    inside.complete(Unit)
                    release.await()
                    assertFalse(closed)
                    "unpublished"
                }
            }
        }
        inside.await()
        val retiring = async { gate.retire { closed = true } }
        yield()
        assertFailsWith<IOException> { gate.read { error("A retired catalog must refuse before running another read") } }
        assertFalse(closed)
        release.complete(Unit)
        escaped.await()
        retiring.await()
        assertEquals(true, closed)
        assertFailsWith<IOException> { gate.read { error("A closed catalog must never reopen") } }
    }
}
