package app.pristinepage

import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.PageId
import app.pristinepage.editor.SelectedEditorSaveDelivery
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedEditorSaveDeliveryTest {
    private val before = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000001", 2)
    private val after = before.copy(number = 3)
    private val page = PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002")

    @Test fun refusalBeforePublicationIsNotAnUnconfirmedWrite() = runTest {
        val missing = IOException("Synthetic capacity refusal")
        val observed = mutableListOf<LibraryActivation?>()
        val delivery = SelectedEditorSaveDelivery(before, page) { observed.add(it) }
        val failure = runCatching { delivery.run { throw missing } }.exceptionOrNull()
        assertSame(missing, failure)
        assertTrue(observed.isEmpty())
    }

    @Test fun failureAfterPublicationBeginsReportsUnconfirmedWithoutClaimingPointerAdvance() = runTest {
        val observed = mutableListOf<LibraryActivation?>()
        val delivery = SelectedEditorSaveDelivery(before, page) { observed.add(it) }
        val failure = runCatching {
            delivery.run {
                publicationStarted()
                throw IOException("Synthetic publication readback failure")
            }
        }.exceptionOrNull() as SelectedEditorSaveDelivery.Unconfirmed
        assertEquals(listOf<LibraryActivation?>(null), observed)
        assertEquals("editor_save_delivery_unconfirmed", failure.error.safeMessageKey)
    }

    @Test fun canceledDeliveryStillReportsTheVerifiedSuccessorWithoutReturningASave() = runTest {
        val observed = mutableListOf<LibraryActivation?>()
        val delivery = SelectedEditorSaveDelivery(before, page) { observed.add(it) }
        val ready = CompletableDeferred<Unit>()
        val caller = launch {
            delivery.run {
                publicationStarted()
                pointerVerified(after)
                ready.complete(Unit)
                awaitCancellation()
            }
        }
        ready.await()
        caller.cancelAndJoin()
        assertTrue(caller.isCancelled)
        assertEquals(listOf(after), observed)
    }

    @Test fun normalDeliveryAfterVerificationDoesNotReportAnInterruption() = runTest {
        val observed = mutableListOf<LibraryActivation?>()
        val delivery = SelectedEditorSaveDelivery(before, page) { observed.add(it) }
        val returned = delivery.run {
            publicationStarted()
            pointerVerified(after)
            "caller verified the fresh page"
        }
        assertEquals("caller verified the fresh page", returned)
        assertTrue(observed.isEmpty())
    }
}
