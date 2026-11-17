package app.pristinepage.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pristinepage.R
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.DocumentId
import app.pristinepage.image.preview.OriginalPreviewBitmap

/** Library original previews remain explicitly separate from the editor's rendered recipe. Navigation passes only retained document/page identities, never borrowed pixels or temporary provider references. */
@Composable fun LibraryScreen(session: LibrarySession, loadOriginal: (suspend (String) -> OriginalPreviewBitmap)? = null, onEdit: ((DocumentId, PageId) -> Unit)? = null) {
    val state by session.state.collectAsStateWithLifecycle()
    val newTitle = stringResource(R.string.new_document)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.library_notice))
        Button(onClick = { session.accept(LibraryEvent.NewDocument(newTitle)) }, enabled = !state.loading, modifier = actionModifier().testTag("create-document")) { Text(newTitle) }
        Button(onClick = {
            if (state.selectedId == null) session.accept(LibraryEvent.NewDocument(newTitle, importAfterCreate = true)) else session.accept(LibraryEvent.BeginImport)
        }, enabled = !state.loading && !state.preparingImport && state.request == null, modifier = actionModifier().testTag("import-photos")) { Text(stringResource(R.string.import_photos)) }
        Button(onClick = {}, enabled = false, modifier = actionModifier()) { Text(stringResource(R.string.take_photo)) }
        if (state.loading) CircularProgressIndicator()
        state.error?.let { Text(errorText(it.code), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { session.accept(LibraryEvent.Refresh) }, enabled = !state.loading, modifier = actionModifier()) { Text(stringResource(R.string.refresh_library)) }
        if ((state.recovery?.blocked ?: 0) > 0) Text(stringResource(R.string.recovery_attention))
        state.request?.let {
            Text(stringResource(when (state.phase) {
                ImportPhase.COPYING -> R.string.import_copying
                ImportPhase.CANCELING -> R.string.import_canceling
                ImportPhase.INTERRUPTED -> R.string.import_interrupted
                else -> R.string.import_waiting
            }))
            Button(onClick = { session.accept(LibraryEvent.CancelImport) }, enabled = state.phase != ImportPhase.CANCELING, modifier = actionModifier()) { Text(stringResource(R.string.cancel_import)) }
        }
        if (state.phase == ImportPhase.CANCELED) Text(stringResource(R.string.import_canceled))
        if (state.results.isNotEmpty()) Text(stringResource(R.string.import_results))
        state.results.forEachIndexed { index, result ->
            val message = when (result) {
                is IngestResult.Committed -> stringResource(R.string.item_saved, index + 1)
                is IngestResult.Pending -> stringResource(R.string.item_pending, index + 1)
                is IngestResult.Rejected -> stringResource(R.string.item_rejected, index + 1, errorText(result.error.code))
            }
            Text(message)
        }
        state.document?.let { overview ->
            var selectedPage by remember(session, overview.document.documentId) { mutableStateOf<PageId?>(null) }
            val selected = overview.pages.firstOrNull { it.pageId == selectedPage } ?: overview.pages.firstOrNull()
            Column(Modifier.fillMaxWidth().testTag("document-overview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(overview.document.title, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.saved_page_count, overview.document.pageCount))
                if (overview.pages.isEmpty()) Text(stringResource(R.string.no_saved_pages))
                overview.pages.forEach { page ->
                    val isSelected = page.pageId == selected?.pageId
                    Button(onClick = { selectedPage = page.pageId }, modifier = actionModifier().testTag("original-page-${page.pageId.value}").semantics { this.selected = isSelected }) { Text(stringResource(if (isSelected) R.string.page_number_selected else R.string.page_number, page.ordinal + 1)) }
                }
                if (selected != null && onEdit != null) Button(onClick = { onEdit(overview.document.documentId, selected.pageId) }, enabled = !state.loading && state.request == null, modifier = actionModifier().testTag("edit-selected-page")) { Text(stringResource(R.string.editor_open_selected, selected.ordinal + 1)) }
                if (selected != null && loadOriginal != null) {
                    // Page identity is part of the lifetime even when two pages share a source. Changing document/page/source immediately detaches the old draw state; no bitmap or source lease enters saved state.
                    key(session, overview.document.documentId, selected.pageId, selected.sourceId) { OriginalPagePreview(selected, loadOriginal) }
                } else if (selected != null) Text(stringResource(R.string.preview_not_available))
            }
        }
        Text(stringResource(R.string.library_heading), style = MaterialTheme.typography.titleLarge)
        if (state.documents.isEmpty() && !state.loading && state.error == null) Text(stringResource(R.string.empty_library))
        state.documents.forEach { document ->
            Button(onClick = { session.accept(LibraryEvent.Open(document.documentId)) }, modifier = actionModifier()) {
                Text(stringResource(R.string.document_summary, document.title, document.pageCount))
            }
        }
        if (state.hasMore) Button(onClick = { session.accept(LibraryEvent.More) }, enabled = !state.loading, modifier = actionModifier()) { Text(stringResource(R.string.more_documents)) }
    }
}

private fun actionModifier() = Modifier.fillMaxWidth().heightIn(min = 48.dp)

@Composable internal fun errorText(code: OperationErrorCode): String = stringResource(when (code) {
    OperationErrorCode.CANCELED -> R.string.error_canceled
    OperationErrorCode.INVALID_INPUT -> R.string.error_invalid_input
    OperationErrorCode.UNSUPPORTED_FORMAT -> R.string.error_unsupported_format
    OperationErrorCode.UNSUPPORTED_VERSION -> R.string.error_unsupported_version
    OperationErrorCode.PERMISSION_LOST -> R.string.error_permission_lost
    OperationErrorCode.SOURCE_UNAVAILABLE -> R.string.error_source_unavailable
    OperationErrorCode.SESSION_EXPIRED -> R.string.error_session_expired
    OperationErrorCode.REVISION_CONFLICT -> R.string.error_revision_conflict
    OperationErrorCode.PROVIDER_UNAVAILABLE -> R.string.error_provider_unavailable
    OperationErrorCode.INVALID_MAP, OperationErrorCode.PROCESSING_FAILED -> R.string.error_processing
    OperationErrorCode.RESOURCE_LIMIT -> R.string.error_resource_limit
    OperationErrorCode.STORAGE_FAILED -> R.string.error_storage_failed
    OperationErrorCode.INTEGRITY_FAILED -> R.string.error_integrity_failed
})
