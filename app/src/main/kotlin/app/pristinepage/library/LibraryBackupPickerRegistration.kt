package app.pristinepage.library

import androidx.activity.result.ActivityResultRegistry
import app.pristinepage.data.library.LibraryActivation

/** Activity recreation may re-register a waiting owner, but only an exact fresh confirmation may launch the chooser. */
internal class LibraryBackupPickerRegistration(
    registry: ActivityResultRegistry,
    private val confirmation: LibraryBackupConfirmation,
    private val ticket: LibraryBackupConfirmation.Ticket,
    deliver: (Delivery) -> Unit,
    writable: Boolean = true,
) : AutoCloseable {
    sealed interface Delivery {
        val ticket: LibraryBackupConfirmation.Ticket
        val selection: LibraryFolderPickerContract.Selection
        fun isPending(): Boolean
        fun consume(activation: LibraryActivation?): Boolean
        fun cancel()
    }

    private class RegisteredDelivery(
        private val owner: LibraryBackupConfirmation,
        override val ticket: LibraryBackupConfirmation.Ticket,
        override val selection: LibraryFolderPickerContract.Selection,
    ) : Delivery {
        override fun isPending() = owner.hasBoundResult(ticket)
        override fun consume(activation: LibraryActivation?) = owner.consumeResult(ticket, activation)
        override fun cancel() = owner.cancel(ticket)
    }

    private val launcher = registry.register(ticket.registryKey, LibraryFolderPickerContract(writable = writable)) { selection ->
        if (confirmation.bindResult(ticket)) deliver(RegisteredDelivery(confirmation, ticket, selection))
    }

    /** The caller supplies the currently verified activation, not a restored identity or a picker-returned value. */
    fun launch(current: LibraryActivation?): Boolean {
        if (!confirmation.claimLaunch(ticket, current)) return false
        try { launcher.launch(Unit) }
        catch (failure: RuntimeException) {
            confirmation.cancel(ticket)
            throw failure
        }
        return true
    }

    override fun close() = launcher.unregister()
}
