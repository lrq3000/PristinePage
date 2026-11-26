package app.pristinepage.export

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pristinepage.R
import app.pristinepage.data.model.ExportFormat
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.image.export.AlbumJpegQuality
import app.pristinepage.library.errorText
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AlbumExportDialog(owner: AlbumExportViewModel) {
    val state by owner.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val publicationId = state.publicationId
    var create by remember(publicationId, registry) { mutableStateOf<androidx.activity.result.ActivityResultLauncher<android.content.Intent>?>(null) }
    DisposableEffect(publicationId, registry) {
        val launcher = publicationId?.let { id ->
            registry.register("pristinepage-create-export-$id", ActivityResultContracts.StartActivityForResult()) {
                owner.result(id, it.resultCode, it.data)
            }
        }
        create = launcher
        onDispose { launcher?.unregister() }
    }
    LaunchedEffect(state.launch, create) {
        val intent = state.launch ?: return@LaunchedEffect
        val isSaveAs = intent.action == android.content.Intent.ACTION_CREATE_DOCUMENT
        if (isSaveAs && create == null) return@LaunchedEffect
        if (!owner.consumeLaunch(intent)) return@LaunchedEffect
        try {
            if (isSaveAs) checkNotNull(create).launch(intent)
            else context.startActivity(intent)
        } catch (_: RuntimeException) { owner.launchFailed() }
    }
    val review = state.review ?: return
    val format = state.format ?: return
    val outputs = remember(review, state.pages, state.size) {
        try { exportOutputs(state.pages, state.size) } catch (_: IllegalArgumentException) { emptyList() }
    }
    val enabled = !state.busy && !state.waitingForTarget
    val formats = if (review.pages.size == 1) listOf(ExportFormat.JPEG, ExportFormat.PDF) else listOf(ExportFormat.IMAGE_ZIP, ExportFormat.PDF)
    val name = when (format) {
        ExportFormat.JPEG -> checkNotNull(review.singleJpegName)
        ExportFormat.PDF -> review.pdfName
        ExportFormat.IMAGE_ZIP -> review.zipName
        ExportFormat.PNG -> error("Unexpected export format")
    }
    AlertDialog(onDismissRequest = owner::dismiss, modifier = Modifier.testTag("album-export-review"),
        title = { Text(stringResource(R.string.album_export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.album_export_count, review.pages.size))
                if (state.filename == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in formats) FilterChip(option == format, { owner.format(option) },
                            label = { Text(stringResource(option.label())) }, enabled = enabled,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("album-export-format-${option.name.lowercase(Locale.ROOT)}"))
                    }
                    Text(stringResource(R.string.album_export_resolution))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in ExportSize.entries) FilterChip(choice == state.size, { owner.size(choice) }, {
                            Text(stringResource(when (choice) {
                                ExportSize.NORMAL -> R.string.album_size_normal
                                ExportSize.COMPACT -> R.string.album_size_compact
                                ExportSize.SMALL -> R.string.album_size_small
                            }))
                        }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    if (format == ExportFormat.PDF) {
                        Text(stringResource(R.string.album_pdf_capacity), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(R.string.album_export_quality))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (choice in AlbumJpegQuality.entries) FilterChip(choice == state.quality, { owner.quality(choice) },
                                label = { Text(stringResource(choice.label())) }, enabled = enabled,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("album-export-quality-${choice.name.lowercase(Locale.ROOT)}"))
                        }
                    }
                    outputs.forEachIndexed { index, output ->
                        Text(stringResource(R.string.album_export_page_size, state.pages[index].position, output.output.size.width, output.output.size.height))
                    }
                    if (enabled && state.pages.isNotEmpty() && outputs.isEmpty()) Text(errorText(app.pristinepage.data.model.OperationErrorCode.INVALID_INPUT))
                } else {
                    Text(stringResource(R.string.album_export_verified))
                    if (state.shared) Text(stringResource(R.string.album_export_share_retention), style = MaterialTheme.typography.bodySmall)
                }
                if (state.busy || state.waitingForTarget) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let {
                    val message = if (state.filename == null && it == OperationErrorCode.INTEGRITY_FAILED) {
                        stringResource(R.string.album_export_verify_failed)
                    } else errorText(it)
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
                state.notice?.let { notice -> Text(stringResource(when (notice) {
                    ExportNotice.SAVED -> R.string.album_export_saved
                    ExportNotice.SENT_WITHOUT_READBACK -> R.string.album_export_saved_unchecked
                    ExportNotice.NEEDS_VERIFICATION -> R.string.album_export_needs_verification
                    ExportNotice.PARTIAL_OUTPUT -> R.string.album_export_partial
                    ExportNotice.CHOOSER_OPEN_FAILED -> R.string.album_export_chooser_failed
                    ExportNotice.STOPPED_SHARING -> R.string.album_export_stopped
                })) }
            }
        },
        confirmButton = {
            if (state.filename == null) Button(owner::generate, enabled = enabled && format != ExportFormat.PDF && outputs.size == review.pages.size,
                modifier = Modifier.heightIn(min = 48.dp).testTag("album-export-generate")) { Text(stringResource(R.string.album_export_generate)) }
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(owner::saveAs, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("album-export-save-as")) {
                    Text(stringResource(R.string.album_export_save_as))
                }
                Button(owner::share, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("album-export-share")) {
                    Text(stringResource(R.string.album_export_share))
                }
            }
        },
        dismissButton = {
            FlowRow {
                if (state.shared) TextButton(owner::revokeShare, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.album_export_stop_sharing))
                }
                TextButton(onClick = if (state.busy && state.filename == null) owner::cancel else owner::dismiss,
                    enabled = enabled || state.busy && state.filename == null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.busy && state.filename == null) R.string.atelier_batch_cancel else R.string.atelier_close))
                }
            }
        },
    )
}

private fun ExportFormat.label() = when (this) {
    ExportFormat.JPEG -> R.string.album_export_jpeg
    ExportFormat.IMAGE_ZIP -> R.string.album_export_zip
    ExportFormat.PDF -> R.string.album_export_pdf
    ExportFormat.PNG -> error("Unexpected export format")
}

private fun AlbumJpegQuality.label() = when (this) {
    AlbumJpegQuality.MAXIMUM -> R.string.album_quality_maximum
    AlbumJpegQuality.BALANCED -> R.string.album_quality_balanced
    AlbumJpegQuality.SMALLER_FILE -> R.string.album_quality_smaller_file
}
