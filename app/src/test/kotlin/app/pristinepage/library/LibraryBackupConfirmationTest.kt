package app.pristinepage.library

import app.pristinepage.data.library.LibraryActivation
import org.junit.Assert.*
import org.junit.Test

class LibraryBackupConfirmationTest {
    private val activation = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000001", 7)
    private val next = activation.copy(number = 8)
    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-000000000002"

    @Test fun aResultCanBeSpentOnlyAfterTheExactConfirmationHasLaunched() {
        val gate = LibraryBackupConfirmation { id }
        val ticket = checkNotNull(gate.confirm(activation))
        assertNull(gate.confirm(activation))
        assertFalse(gate.bindResult(ticket))
        assertFalse(gate.consumeResult(ticket, activation))
        assertFalse(gate.claimLaunch(ticket, next))
        assertFalse(gate.claimLaunch(ticket.copy(), activation))
        assertTrue(gate.claimLaunch(ticket, activation))
        assertFalse(gate.claimLaunch(ticket, activation))
        assertFalse(gate.consumeResult(ticket, activation))
        assertTrue(gate.bindResult(ticket))
        assertFalse(gate.bindResult(ticket))
        assertFalse(gate.consumeResult(ticket.copy(), activation))
        assertFalse(gate.consumeResult(ticket.copy(issuedAt = next), activation))
        assertTrue(gate.consumeResult(ticket, activation))
        assertFalse(gate.consumeResult(ticket, activation))
        assertEquals(ticket, gate.recovery)
    }

    @Test fun aCallbackUnderAnotherActivationCannotBeResumedWhenTheLibraryComesBack() {
        val gate = LibraryBackupConfirmation { id }
        val ticket = checkNotNull(gate.confirm(activation))
        assertTrue(gate.claimLaunch(ticket, activation))
        assertTrue(gate.bindResult(ticket))
        assertFalse(gate.consumeResult(ticket, next))
        assertFalse(gate.consumeResult(ticket, activation))
        assertNull(gate.recovery)
    }

    @Test fun aCanceledLaunchCannotConsumeALateResult() {
        val gate = LibraryBackupConfirmation { id }
        val canceled = checkNotNull(gate.confirm(activation))
        assertTrue(gate.claimLaunch(canceled, activation))
        gate.cancel(canceled)
        val fresh = checkNotNull(gate.confirm(activation))
        assertFalse(gate.bindResult(canceled))
        assertTrue(gate.claimLaunch(fresh, activation))
        assertTrue(gate.bindResult(fresh))
        assertTrue(gate.consumeResult(fresh, activation))
    }

    @Test fun processRecoveryReadsAnExactReceiptButCannotRelaunchOrSpendItsTicket() {
        val previous = LibraryBackupConfirmation { id }
        val ticket = checkNotNull(previous.confirm(activation))
        assertTrue(previous.claimLaunch(ticket, activation))
        assertTrue(previous.bindResult(ticket))
        assertTrue(previous.consumeResult(ticket, activation))
        val recovered = LibraryBackupConfirmation({ error("Recovery cannot allocate a confirmation") }, previous.recovery)
        assertEquals(ticket, recovered.recovery)
        assertFalse(recovered.claimLaunch(ticket, activation))
        assertFalse(recovered.bindResult(ticket))
        assertFalse(recovered.consumeResult(ticket, activation))
    }
}
