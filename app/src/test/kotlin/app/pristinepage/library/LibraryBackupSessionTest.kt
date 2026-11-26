package app.pristinepage.library

import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.library.LibraryFolderExportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryBackupSessionTest {
    private val first = LibraryActivation("00000000-0000-4000-8000-000000000001", 1)
    private val next = first.copy(number = 2)

    @Test fun openingSettingsAndCancelingReviewNeverCreateAChooserRegistration() = runTest {
        val port = Port()
        val session = LibraryBackupSession(port, this)
        session.show(first)
        advanceUntilIdle()
        assertNull(session.state.value.picker)
        session.confirm()
        assertNull(session.state.value.picker)
        session.review()
        assertTrue(session.state.value.reviewing)
        assertNull(session.state.value.picker)
        session.cancelReview()
        session.confirm()
        assertNull(session.state.value.picker)
        assertEquals(listOf(first), port.reads)
    }

    @Test fun aNewActivationCannotLaunchTheOldConfirmationOrConsumeItsRegistryKey() = runTest {
        val port = Port()
        val session = LibraryBackupSession(port, this)
        session.show(first)
        advanceUntilIdle()
        session.review()
        session.confirm()
        val original = checkNotNull(session.state.value.picker)
        session.confirm()
        assertSame(original, session.state.value.picker)
        session.show(next)
        advanceUntilIdle()
        assertNull(session.state.value.picker)
        assertFalse(original.authorization.claimLaunch(original.ticket, first))
        session.review()
        session.confirm()
        val replacement = checkNotNull(session.state.value.picker)
        assertEquals(next, replacement.ticket.issuedAt)
        assertNotEquals(original.ticket.registryKey, replacement.ticket.registryKey)
    }

    @Test fun coldDiscoveryCanShowUncertainEvidenceWithoutLaunchingOrPublishing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = Port().apply {
            find = { activation ->
                if (activation == first) gate.await()
                LibraryBackupSession.History(emptyList(), if (activation == first) 1 else 2)
            }
        }
        val session = LibraryBackupSession(port, this)
        session.show(first)
        runCurrent()
        session.show(next)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(next, session.state.value.issuedAt)
        assertEquals(2, session.state.value.history?.unresolvedCount)
        assertNull(session.state.value.picker)
        assertFalse(session.state.value.reviewing)
    }

    @Test fun leavingSettingsWhileLaunchVerificationIsSuspendedRevokesTheUnlaunchedChoice() = runTest {
        val verified = CompletableDeferred<LibraryActivation?>()
        val port = Port().apply { readActivation = { verified.await() } }
        val session = LibraryBackupSession(port, this)
        session.show(first)
        advanceUntilIdle()
        session.review()
        session.confirm()
        val picker = checkNotNull(session.state.value.picker)
        val launch = async { session.mayLaunch(picker) }
        runCurrent()
        assertFalse(launch.isCompleted)
        session.leaveSettings()
        verified.complete(first)
        assertFalse(launch.await())
        assertNull(session.state.value.picker)
        assertFalse(picker.authorization.claimLaunch(picker.ticket, first))
    }

    @Test fun leavingSettingsDoesNotUnregisterAnAlreadyLaunchedFilesChooser() = runTest {
        val session = LibraryBackupSession(Port(), this)
        session.show(first)
        advanceUntilIdle()
        session.review()
        session.confirm()
        val picker = checkNotNull(session.state.value.picker)
        assertTrue(picker.authorization.claimLaunch(picker.ticket, first))
        session.leaveSettings()
        assertSame(picker, session.state.value.picker)
        assertTrue(picker.authorization.bindResult(picker.ticket))
    }

    private class Port : LibraryBackupSession.Port {
        val reads = mutableListOf<LibraryActivation>()
        var find: suspend (LibraryActivation) -> LibraryBackupSession.History = { LibraryBackupSession.History(emptyList(), 0) }
        var readActivation: suspend () -> LibraryActivation? = { null }
        override suspend fun activation(): LibraryActivation? = readActivation()
        override suspend fun discover(issuedAt: LibraryActivation): LibraryBackupSession.History {
            reads += issuedAt
            return find(issuedAt)
        }
        override suspend fun write(delivery: LibraryBackupPickerRegistration.Delivery) = error("No Files result was authorized in this test")
        override suspend fun inspect(ticket: LibraryBackupConfirmation.Ticket): LibraryFolderExportState = error("Discovery must not replay an operation")
        override suspend fun acknowledge(ticket: LibraryBackupConfirmation.Ticket, receipt: app.pristinepage.data.library.LibraryFolderExportReceipt) =
            error("No receipt was acknowledged")
        override suspend fun archive(ticket: LibraryBackupConfirmation.Ticket): Boolean = error("No receipt was archived")
    }
}
