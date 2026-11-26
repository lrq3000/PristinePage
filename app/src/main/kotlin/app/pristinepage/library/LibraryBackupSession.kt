package app.pristinepage.library

import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.library.LibraryBackupCapacityRefused
import app.pristinepage.data.library.LibraryFolderExportReceipt
import app.pristinepage.data.library.LibraryFolderExportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Main-confined Settings state. A recovered receipt is never a live confirmation or picker identity. */
internal class LibraryBackupSession(private val port: Port, private val scope: CoroutineScope) {
    interface Port {
        suspend fun activation(): LibraryActivation?
        suspend fun discover(issuedAt: LibraryActivation): History
        suspend fun write(delivery: LibraryBackupPickerRegistration.Delivery): LibraryFolderExportReceipt?
        suspend fun inspect(ticket: LibraryBackupConfirmation.Ticket): LibraryFolderExportState
        suspend fun acknowledge(ticket: LibraryBackupConfirmation.Ticket, receipt: LibraryFolderExportReceipt)
        suspend fun archive(ticket: LibraryBackupConfirmation.Ticket): Boolean
    }

    data class History(val receipts: List<LibraryFolderExportState.Confirmed>, val unresolvedCount: Int)
    class Picker(val authorization: LibraryBackupConfirmation, val ticket: LibraryBackupConfirmation.Ticket)
    enum class Notice { PICKER_UNAVAILABLE, DESTINATION_REFUSED, NOT_CREATED, CAPACITY, NEEDS_VERIFICATION, RETENTION_INCOMPLETE }
    data class State(
        val issuedAt: LibraryActivation? = null,
        val history: History? = null,
        val historyUnavailable: Boolean = false,
        val reviewing: Boolean = false,
        val picker: Picker? = null,
        val busy: Boolean = false,
        val completed: LibraryFolderExportReceipt? = null,
        val notice: Notice? = null,
    ) {
        val canReview: Boolean get() = issuedAt != null && history != null && !historyUnavailable && !busy && !reviewing && picker == null
    }

    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()
    private var work: Job? = null
    private var epoch = 0L

    fun show(issuedAt: LibraryActivation?) {
        val state = current.value
        if (state.issuedAt == issuedAt) return
        ++epoch
        state.picker?.let { it.authorization.cancel(it.ticket) }
        work?.cancel()
        current.value = State(issuedAt = issuedAt)
        refresh()
    }

    fun refresh() {
        val state = current.value
        if (state.picker != null || state.busy || state.reviewing) return
        run { issuedAt, token -> loadHistory(issuedAt, token) }
    }

    fun review() {
        if (current.value.canReview) current.value = current.value.copy(reviewing = true, notice = null, completed = null)
    }

    fun cancelReview() {
        if (current.value.reviewing) current.value = current.value.copy(reviewing = false)
    }

    fun leaveSettings() {
        val state = current.value
        val unlaunched = state.picker?.takeIf { it.authorization.awaitsLaunch(it.ticket) }
        unlaunched?.authorization?.cancel(unlaunched.ticket)
        current.value = state.copy(reviewing = false, picker = state.picker.takeUnless { it === unlaunched })
    }

    fun confirm() {
        val state = current.value
        val issuing = state.issuedAt ?: return
        if (!state.reviewing) return
        val gate = LibraryBackupConfirmation()
        val ticket = checkNotNull(gate.confirm(issuing))
        current.value = state.copy(reviewing = false, picker = Picker(gate, ticket))
    }

    /** Recheck durable selection without starting Room solely to launch the chooser. */
    suspend fun mayLaunch(picker: Picker): Boolean {
        if (current.value.picker !== picker || !picker.authorization.awaitsLaunch(picker.ticket)) return false
        val verified = try { port.activation() }
        catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        if (current.value.picker !== picker) return false
        if (verified == picker.ticket.issuedAt) return true
        launchFailed(picker)
        return false
    }

    fun launchFailed(picker: Picker) {
        if (current.value.picker !== picker) return
        picker.authorization.cancel(picker.ticket)
        current.value = current.value.copy(picker = null, notice = Notice.PICKER_UNAVAILABLE)
    }

    fun accept(delivery: LibraryBackupPickerRegistration.Delivery) {
        val state = current.value
        val picker = state.picker
        if (picker?.ticket !== delivery.ticket || !delivery.isPending()) {
            delivery.cancel()
            return
        }
        current.value = state.copy(picker = null)
        if (delivery.selection !is LibraryFolderPickerContract.Picked) {
            delivery.cancel()
            if (delivery.selection != LibraryFolderPickerContract.Canceled) {
                current.value = current.value.copy(notice = Notice.DESTINATION_REFUSED)
            }
            return
        }
        run { issuedAt, token ->
            try {
                val receipt = try { port.write(delivery) }
                catch (canceled: CancellationException) { throw canceled }
                catch (_: LibraryBackupCapacityRefused) {
                    if (epoch == token) current.value = current.value.copy(notice = Notice.CAPACITY)
                    loadHistory(issuedAt, token)
                    return@run
                }
                catch (_: Exception) { null }
                currentCoroutineContext().ensureActive()
                if (epoch != token) return@run
                if (receipt != null && receipt.operationId == delivery.ticket.operationId && port.activation() == issuedAt) {
                    if (epoch == token) current.value = current.value.copy(completed = receipt)
                } else recover(delivery.ticket, token)
                loadHistory(issuedAt, token)
            } finally { delivery.cancel() }
        }
    }

    fun acknowledge(confirmed: LibraryFolderExportState.Confirmed) {
        val state = current.value
        val issuedAt = state.issuedAt ?: return
        if (state.busy || state.picker != null || state.reviewing || confirmed !in state.history?.receipts.orEmpty()) return
        val receipt = confirmed.receipt
        val ticket = LibraryBackupConfirmation.Ticket(receipt.operationId, issuedAt)
        run { issuing, token ->
            val archived = try {
                if (confirmed is LibraryFolderExportState.Completed) port.acknowledge(ticket, receipt)
                port.archive(ticket)
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { false }
            if (epoch == token) current.value = current.value.copy(
                completed = current.value.completed?.takeUnless { it == receipt },
                notice = Notice.RETENTION_INCOMPLETE.takeUnless { archived },
            )
            loadHistory(issuing, token)
        }
    }

    private suspend fun recover(ticket: LibraryBackupConfirmation.Ticket, token: Long) {
        val observed = try { port.inspect(ticket) }
        catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) { LibraryFolderExportState.NeedsVerification }
        if (epoch != token) return
        current.value = when (observed) {
            LibraryFolderExportState.NotStarted -> current.value.copy(notice = Notice.NOT_CREATED)
            LibraryFolderExportState.NeedsVerification -> current.value.copy(notice = Notice.NEEDS_VERIFICATION)
            is LibraryFolderExportState.Confirmed -> current.value.copy(completed = observed.receipt)
        }
    }

    private suspend fun loadHistory(issuedAt: LibraryActivation, token: Long) {
        val observed = try { port.discover(issuedAt) }
        catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        if (epoch == token) current.value = current.value.copy(
            history = observed ?: current.value.history, historyUnavailable = observed == null,
        )
    }

    private fun run(operation: suspend (LibraryActivation, Long) -> Unit) {
        val issuedAt = current.value.issuedAt ?: return
        val token = epoch
        current.value = current.value.copy(busy = true)
        work = scope.launch {
            try { operation(issuedAt, token) }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { if (epoch == token) current.value = current.value.copy(notice = Notice.NEEDS_VERIFICATION) }
            finally { if (epoch == token) current.value = current.value.copy(busy = false) }
        }
    }
}
