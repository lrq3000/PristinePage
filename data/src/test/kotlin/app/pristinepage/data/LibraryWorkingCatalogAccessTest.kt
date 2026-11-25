package app.pristinepage.data

import app.pristinepage.data.library.LibraryWorkingCatalogAccess
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
