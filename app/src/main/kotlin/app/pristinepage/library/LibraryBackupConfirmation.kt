package app.pristinepage.library

import app.pristinepage.data.library.LibraryActivation
import java.util.UUID

/** One live confirmation, distinct from the read-only lookup identity that can survive process recreation. */
internal class LibraryBackupConfirmation(private val newId: () -> String, recovered: Ticket?) {
    constructor(newId: () -> String = { UUID.randomUUID().toString() }) : this(newId, null)

    data class Ticket(val operationId: String, val issuedAt: LibraryActivation) {
        init { require(UUID.fromString(operationId).toString() == operationId) }
        val registryKey: String get() = "pristinepage.backup.v1:${issuedAt.libraryId}:${issuedAt.number}:$operationId"
    }

    private data class Pending(val ticket: Ticket, val launched: Boolean, val resultBound: Boolean = false)
    private var pending: Pending? = null
    private var spent: Ticket? = recovered

    val recovery: Ticket?
        @Synchronized get() = spent

    @Synchronized fun confirm(issuedAt: LibraryActivation): Ticket? {
        if (pending != null || spent != null) return null
        return Ticket(newId(), issuedAt).also { pending = Pending(it, launched = false) }
    }

    @Synchronized fun claimLaunch(ticket: Ticket, current: LibraryActivation?): Boolean {
        val active = pending ?: return false
        if (active.ticket !== ticket || active.launched || ticket.issuedAt != current) return false
        pending = active.copy(launched = true)
        return true
    }

    @Synchronized fun consumeResult(ticket: Ticket, current: LibraryActivation?): Boolean {
        val active = pending ?: return false
        if (active.ticket !== ticket || !active.resultBound) return false
        pending = null
        if (ticket.issuedAt != current) return false
        spent = ticket
        return true
    }

    /** Abandon a canceled launch or callback only while it has never authorized provider work. */
    @Synchronized fun cancel(ticket: Ticket) {
        if (pending?.ticket === ticket) pending = null
    }

    /** Bind the first delivered callback synchronously, before asynchronous activation verification or grant handoff. */
    @Synchronized fun bindResult(ticket: Ticket): Boolean {
        val active = pending ?: return false
        if (active.ticket !== ticket || !active.launched || active.resultBound) return false
        pending = active.copy(resultBound = true)
        return true
    }

    @Synchronized fun hasBoundResult(ticket: Ticket): Boolean = pending?.let { it.ticket === ticket && it.resultBound } == true
}
