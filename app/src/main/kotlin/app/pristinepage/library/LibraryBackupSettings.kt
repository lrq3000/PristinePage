package app.pristinepage.library

import androidx.activity.result.ActivityResultRegistry
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pristinepage.ApplicationServices
import app.pristinepage.R
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.library.LibraryFolderExportReceipt
import app.pristinepage.data.library.LibraryFolderExportState

internal class LibraryBackupViewModel(services: ApplicationServices) : ViewModel() {
    val session = LibraryBackupSession(object : LibraryBackupSession.Port {
        override suspend fun activation() = services.recordedDefaultActivation()
        override suspend fun discover(issuedAt: LibraryActivation) =
            services.discoverDefaultBackups(issuedAt).let { LibraryBackupSession.History(it.receipts, it.unresolvedCount) }
        override suspend fun write(delivery: LibraryBackupPickerRegistration.Delivery) = services.exportConfirmedDefaultLibrary(delivery)
        override suspend fun inspect(ticket: LibraryBackupConfirmation.Ticket) = services.inspectConfirmedDefaultBackup(ticket)
        override suspend fun acknowledge(ticket: LibraryBackupConfirmation.Ticket, receipt: LibraryFolderExportReceipt) =
            services.acknowledgeConfirmedDefaultBackup(ticket, receipt)
        override suspend fun archive(ticket: LibraryBackupConfirmation.Ticket) = services.archiveAcknowledgedDefaultBackup(ticket)
    }, viewModelScope)
}

/** Keep the native callback available while leaving the Settings screen or recreating the Activity. Registration never recreates consent. */
@Composable
internal fun LibraryBackupPickerHost(session: LibraryBackupSession, registry: ActivityResultRegistry, allowLaunch: Boolean) {
    val state by session.state.collectAsStateWithLifecycle()
    val picker = state.picker
    var registration by remember(picker, registry) { mutableStateOf<LibraryBackupPickerRegistration?>(null) }
    DisposableEffect(picker, registry) {
        val binding = picker?.let { LibraryBackupPickerRegistration(registry, it.authorization, it.ticket, session::accept) }
        registration = binding
        onDispose { binding?.close() }
    }
    LaunchedEffect(picker, registration, allowLaunch) {
        if (allowLaunch && picker != null && registration != null && session.mayLaunch(picker)) {
            try { registration?.launch(picker.ticket.issuedAt) }
            catch (_: RuntimeException) { session.launchFailed(picker) }
        }
    }
}

@Composable
internal fun LibraryBackupSettings(session: LibraryBackupSession) {
    val state by session.state.collectAsStateWithLifecycle()
    if (state.issuedAt == null) {
        Text(stringResource(R.string.library_backup_unavailable))
        return
    }
    Text(stringResource(R.string.library_default_location), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.library_backup_description))
    Button(onClick = session::review, enabled = state.canReview,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("library-backup-export")) {
        Text(stringResource(R.string.library_backup_export))
    }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.historyUnavailable) {
        Text(stringResource(R.string.library_backup_history_unavailable))
        TextButton(onClick = session::refresh, enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.library_backup_check)) }
    }
    state.history?.let { history ->
        if (history.unresolvedCount > 0) {
            Text(pluralStringResource(R.plurals.library_backup_unresolved, history.unresolvedCount, history.unresolvedCount))
        }
        history.receipts.forEach { confirmed ->
            Column {
                val acknowledged = confirmed is LibraryFolderExportState.Acknowledged
                Text(stringResource(R.string.library_backup_record, confirmed.receipt.folderName))
                TextButton(onClick = { session.acknowledge(confirmed) }, enabled = state.canReview,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (acknowledged) R.string.library_backup_clear_scratch else R.string.library_backup_acknowledge))
                }
            }
        }
    }
    state.completed?.let {
        Text(stringResource(R.string.library_backup_completed, it.folderName), modifier = Modifier.testTag("library-backup-completed"))
    }
    state.notice?.let {
        val label = when (it) {
            LibraryBackupSession.Notice.PICKER_UNAVAILABLE -> R.string.library_backup_picker_unavailable
            LibraryBackupSession.Notice.DESTINATION_REFUSED -> R.string.library_backup_destination_refused
            LibraryBackupSession.Notice.NOT_CREATED -> R.string.library_backup_not_created
            LibraryBackupSession.Notice.CAPACITY -> R.string.library_backup_no_space
            LibraryBackupSession.Notice.NEEDS_VERIFICATION -> R.string.library_backup_needs_verification
            LibraryBackupSession.Notice.RETENTION_INCOMPLETE -> R.string.library_backup_retention_incomplete
        }
        Text(stringResource(label))
    }
    if (state.reviewing) AlertDialog(
        onDismissRequest = session::cancelReview,
        title = { Text(stringResource(R.string.library_backup_export)) },
        text = { Text(stringResource(R.string.library_backup_confirmation)) },
        confirmButton = {
            TextButton(onClick = session::confirm, modifier = Modifier.heightIn(min = 48.dp).testTag("library-backup-choose-folder")) {
                Text(stringResource(R.string.library_backup_choose_folder))
            }
        },
        dismissButton = {
            TextButton(onClick = session::cancelReview, modifier = Modifier.heightIn(min = 48.dp).testTag("library-backup-cancel")) {
                Text(stringResource(R.string.library_backup_cancel))
            }
        },
    )
}
