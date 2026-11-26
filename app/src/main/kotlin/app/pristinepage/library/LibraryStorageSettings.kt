package app.pristinepage.library

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pristinepage.ApplicationServices
import app.pristinepage.R
import app.pristinepage.data.library.AndroidLibraryStartup
import app.pristinepage.data.library.LibraryActivation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One foreground selection and one confirmation. A picker result alone never changes the active pointer. */
internal class LibraryStorageViewModel(context: Context, private val services: ApplicationServices) : ViewModel() {
    private val app = context.applicationContext
    enum class Action { CHANGE, RECONNECT, IMPORT }
    data class Picker(val gate: LibraryBackupConfirmation, val ticket: LibraryBackupConfirmation.Ticket, val action: Action)
    sealed interface Prepared {
        data class ChangeFolder(val preview: AndroidLibraryStartup.FolderPreview) : Prepared
        data class ImportFolder(val preview: AndroidLibraryStartup.SelectedImportPreview) : Prepared
    }
    class Review(val issuing: LibraryActivation, val active: String?, val picked: String, val prepared: Prepared,
        private val grant: LibraryFolderGrantHandoff.PreviewGrant) : AutoCloseable {
        fun retainGrant() = grant.retain()
        fun dispose() = grant.dispose()
        override fun close() = grant.close()
    }
    data class Change(val old: LibraryActivation, val current: LibraryActivation)
    data class State(val location: AndroidLibraryStartup.Location? = null, val picker: Picker? = null,
        val review: Review? = null, val returnReview: Boolean = false, val busy: Boolean = false, val error: Boolean = false,
        val change: Change? = null)
    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()

    fun refresh() {
        if (current.value.busy) return
        viewModelScope.launch {
            try { current.value = current.value.copy(location = services.storageLocation()) }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { current.value = current.value.copy(error = true) }
        }
    }

    fun pick(action: Action) {
        val state = current.value
        val location = state.location ?: return
        if (state.busy || state.review != null || state.picker != null) return
        val gate = LibraryBackupConfirmation()
        if (action == Action.IMPORT && (location.folderLabel == null || location.needsReconnect)) return
        current.value = state.copy(picker = Picker(gate, checkNotNull(gate.confirm(location.activation)), action), error = false)
    }

    fun pickerUnavailable(picker: Picker) {
        if (current.value.picker !== picker) return
        picker.gate.cancel(picker.ticket)
        current.value = current.value.copy(picker = null, error = true)
    }

    fun accept(delivery: LibraryBackupPickerRegistration.Delivery) {
        val picker = current.value.picker
        if (picker?.ticket !== delivery.ticket || !delivery.isPending()) { delivery.cancel(); return }
        val picked = delivery.selection as? LibraryFolderPickerContract.Picked
        current.value = current.value.copy(picker = null, busy = picked != null, error = picked == null &&
            delivery.selection != LibraryFolderPickerContract.Canceled)
        if (picked == null) { delivery.cancel(); return }
        viewModelScope.launch {
            var grant: LibraryFolderGrantHandoff.PreviewGrant? = null
            try {
                val location = services.storageLocation()
                if (!delivery.consume(location.activation)) return@launch
                val destination = checkNotNull(AndroidLibraryTreeReadLabel.forUri(picked.uri))
                // Register the provisional grant inside IO before dispatch can lose its cleanup owner.
                withContext(Dispatchers.IO) {
                    grant = LibraryFolderGrantHandoff(app).previewGrant(picked, writable = picker.action != Action.IMPORT)
                }
                val acquired = checkNotNull(grant)
                if (picker.action == Action.RECONNECT) {
                    val verified = services.reconnectLibrary(location.activation, acquired.tree)
                    acquired.retain()
                    current.value = current.value.copy(location = services.storageLocation(), change = Change(location.activation, verified))
                } else {
                    val preview = when (picker.action) {
                        Action.IMPORT -> Prepared.ImportFolder(services.inspectSelectedImport(location.activation, acquired.tree))
                        else -> Prepared.ChangeFolder(services.inspectLibraryFolder(location.activation, acquired.tree))
                    }
                    current.value = current.value.copy(review = Review(location.activation, location.folderLabel, destination, preview, acquired))
                    grant = null
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { current.value = current.value.copy(error = true) }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { grant?.close() }
                    catch (_: Exception) { /* Retain uncertain permission; the pointer was not changed during preview. */ }
                }
                delivery.cancel()
                current.value = current.value.copy(busy = false)
            }
        }
    }

    fun cancelReview() {
        val review = current.value.review ?: return
        current.value = current.value.copy(review = null, busy = true)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withContext(NonCancellable + Dispatchers.IO) { review.close() } }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { current.value = current.value.copy(error = true) }
            finally { current.value = current.value.copy(busy = false) }
        }
    }

    fun confirm() {
        val review = current.value.review ?: return
        current.value = current.value.copy(review = null, busy = true, error = false)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val next = when (val prepared = review.prepared) {
                    // The data writer retains the exact grant only at the pointer replacement boundary.
                    is Prepared.ChangeFolder -> services.selectLibraryFolder(review.issuing, prepared.preview, review::retainGrant)
                    is Prepared.ImportFolder -> services.importIntoSelected(prepared.preview)
                }
                current.value = current.value.copy(location = services.storageLocation(), change = Change(review.issuing, next))
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) {
                val observed = try { services.storageLocation() } catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) { null }
                current.value = current.value.copy(location = observed ?: current.value.location, error = true,
                    change = observed?.activation?.takeUnless { it == review.issuing }?.let { Change(review.issuing, it) })
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { review.close() } catch (_: Exception) { /* Preserve all pre-existing grants. */ }
                }
                current.value = current.value.copy(busy = false)
            }
        }
    }

    fun acknowledge(change: Change) {
        if (current.value.change == change) current.value = current.value.copy(change = null)
    }

    fun reviewDefault() {
        val state = current.value
        if (state.location?.folderLabel != null && !state.busy && state.review == null && state.picker == null) {
            current.value = state.copy(returnReview = true)
        }
    }

    fun cancelDefaultReview() { current.value = current.value.copy(returnReview = false) }

    fun returnToDefault() {
        val state = current.value
        val before = state.location ?: return
        if (!state.returnReview || before.folderLabel == null || state.busy) return
        current.value = state.copy(returnReview = false, busy = true, error = false)
        viewModelScope.launch {
            try {
                val next = services.returnToDefaultLibrary(before.activation)
                current.value = current.value.copy(location = services.storageLocation(), change = Change(before.activation, next))
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { current.value = current.value.copy(error = true) }
            finally { current.value = current.value.copy(busy = false) }
        }
    }

    override fun onCleared() {
        current.value.picker?.let { it.gate.cancel(it.ticket) }
        current.value.review?.dispose()
    }
}

private object AndroidLibraryTreeReadLabel {
    fun forUri(uri: android.net.Uri) = app.pristinepage.data.library.AndroidLibraryTreeRead.localTreeDocumentId(uri)?.substringAfter(':')
}

@Composable internal fun LibraryStoragePicker(owner: LibraryStorageViewModel, registry: ActivityResultRegistry, allowLaunch: Boolean) {
    val state by owner.state.collectAsStateWithLifecycle()
    val picker = state.picker
    var registration by remember(picker, registry) { mutableStateOf<LibraryBackupPickerRegistration?>(null) }
    DisposableEffect(picker, registry) {
        val binding = picker?.let { LibraryBackupPickerRegistration(registry, it.gate, it.ticket, owner::accept,
            writable = it.action != LibraryStorageViewModel.Action.IMPORT) }
        registration = binding
        onDispose { binding?.close() }
    }
    LaunchedEffect(picker, registration, allowLaunch) {
        if (allowLaunch && picker != null && registration != null && picker.gate.awaitsLaunch(picker.ticket)) {
            try { registration?.launch(state.location?.activation) }
            catch (_: RuntimeException) { owner.pickerUnavailable(picker) }
        }
    }
}

@Composable internal fun LibraryStorageSettings(owner: LibraryStorageViewModel, backup: LibraryBackupSession) {
    val state by owner.state.collectAsStateWithLifecycle()
    val backupState by backup.state.collectAsStateWithLifecycle()
    val location = state.location
    val fallback = stringResource(R.string.library_selected_folder)
    val default = stringResource(R.string.library_default_location)
    val label = location?.folderLabel?.ifBlank { fallback } ?: default
    val selectable = location != null && !state.busy && state.picker == null && state.review == null && !state.returnReview &&
        !backupState.busy && !backupState.reviewing && backupState.picker == null
    LaunchedEffect(owner) { owner.refresh() }
    Column {
        if (location?.folderLabel != null) Text(label, style = MaterialTheme.typography.titleMedium)
        Button(onClick = { owner.pick(if (location?.needsReconnect == true) LibraryStorageViewModel.Action.RECONNECT
            else LibraryStorageViewModel.Action.CHANGE) }, enabled = selectable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("library-storage-folder")) {
            Text(stringResource(if (location?.needsReconnect == true) R.string.library_reconnect else R.string.library_change_folder))
        }
        if (location?.folderLabel != null) {
            Button(onClick = { owner.pick(LibraryStorageViewModel.Action.IMPORT) }, enabled = selectable && !location.needsReconnect,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("library-storage-import")) {
                Text(stringResource(R.string.library_import))
            }
            TextButton(onClick = owner::reviewDefault, enabled = selectable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("library-storage-use-default")) {
                Text(stringResource(R.string.library_use_default))
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error) Text(stringResource(R.string.library_folder_unavailable))
        LibraryBackupSettings(backup, allowExport = !state.busy && state.picker == null && state.review == null)
    }
    if (state.returnReview) AlertDialog(
        onDismissRequest = owner::cancelDefaultReview,
        title = { Text(stringResource(R.string.library_use_default)) },
        text = { Text(stringResource(R.string.library_open_folder_confirm, label, default)) },
        confirmButton = {
            TextButton(onClick = owner::returnToDefault, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.library_use_default)) }
        },
        dismissButton = {
            TextButton(onClick = owner::cancelDefaultReview, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.library_backup_cancel)) }
        },
    )
    val review = state.review
    if (review != null) {
        val isImport = review.prepared is LibraryStorageViewModel.Prepared.ImportFolder
        val existing = (review.prepared as? LibraryStorageViewModel.Prepared.ChangeFolder)?.preview is AndroidLibraryStartup.FolderPreview.Existing
        val import = review.prepared as? LibraryStorageViewModel.Prepared.ImportFolder
        val action = when {
            import?.preview?.hasAlbums == true -> R.string.library_copy_and_merge
            isImport -> R.string.library_copy
            existing -> R.string.library_open_folder
            else -> R.string.library_empty_start
        }
        val message = when {
            isImport -> R.string.library_copy_confirm
            existing -> R.string.library_open_folder_confirm
            else -> R.string.library_empty_folder_confirm
        }
        AlertDialog(
            onDismissRequest = owner::cancelReview,
            title = { Text(stringResource(if (isImport) R.string.library_import else R.string.library_change_folder)) },
            text = { Text(stringResource(message, review.active?.ifBlank { fallback } ?: default, review.picked)) },
            confirmButton = {
                TextButton(onClick = owner::confirm, modifier = Modifier.heightIn(min = 48.dp).testTag("library-storage-open")) {
                    Text(stringResource(action))
                }
            },
            dismissButton = {
                TextButton(onClick = owner::cancelReview, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.library_backup_cancel)) }
            },
        )
    }
}
