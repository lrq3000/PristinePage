package app.pristinepage.ocr

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pristinepage.R
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrError
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.BinaryRecipe
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.SavedOcrWork.PagePhase
import app.pristinepage.design.OcrLanguage
import app.pristinepage.design.label
import app.pristinepage.ocr.SavedOcrScheduling.RerunOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Read only the chosen private panel. The view never combines a status and a separate result as if they were one snapshot. */
class SavedPageOcrReader internal constructor(
    private val open: suspend () -> SavedOcrWork,
    internal val rerunInFlight: StateFlow<Boolean>,
    internal val requestRerun: suspend (SavedOcrWork.PageStatus, Boolean) -> RerunOutcome,
    internal val requestSave: ((SavedOcrWork.TextExportObservation) -> Boolean)? = null,
) {
    internal suspend fun status(page: EditablePage) = open().readStatus(page.documentId, page.pageId, page.recipeRevision)
    internal suspend fun text(page: EditablePage) = open().readText(page.documentId, page.pageId, page.recipeRevision)
}

internal enum class SavedPageOcrPanel(val title: Int) { PROCESSING(R.string.ocr_processing_details), TEXT(R.string.ocr_text) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SavedPageOcrDetails(panel: SavedPageOcrPanel, page: EditablePage?, reader: SavedPageOcrReader, showDiagnostics: Boolean, dismiss: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ui = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var submitting by remember(panel, page, reader) { mutableStateOf(false) }
    var notice by remember(panel, page, reader) { mutableStateOf<Int?>(null) }
    val processBusy by reader.rerunInFlight.collectAsStateWithLifecycle()
    val planned = remember(panel, page, showDiagnostics) {
        ocrPlannedDiagnostics(showDiagnostics && panel == SavedPageOcrPanel.PROCESSING, page?.recipe)
    }
    var result by remember(panel, page, reader, lifecycle, refresh) {
        mutableStateOf<Reading>(if (page == null) Reading.Unavailable else Reading.Loading)
    }
    LaunchedEffect(panel, page, reader, lifecycle, refresh) {
        if (page == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            result = Reading.Loading
            try {
                val snapshot = try {
                    when (panel) {
                        SavedPageOcrPanel.PROCESSING -> Reading.Status(reader.status(page))
                        SavedPageOcrPanel.TEXT -> Reading.TextValue(reader.text(page))
                    }
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: Exception) {
                    Reading.Unavailable
                }
                ensureActive()
                result = snapshot
                awaitCancellation()
            } finally {
                // Privacy and navigation: do not retain requested text after the panel leaves its started lifetime.
                result = Reading.Loading
            }
        }
    }
    fun submit(status: SavedOcrWork.PageStatus, defaults: Boolean) {
        if (submitting || reader.rerunInFlight.value || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        submitting = true
        notice = R.string.ocr_rerun_submitting
        ui.launch {
            try {
                val outcome = reader.requestRerun(status, defaults)
                ensureActive()
                notice = ocrRerunLabel(outcome)
                if (outcome == RerunOutcome.ENROLLED || outcome == RerunOutcome.WAKE_DEFERRED) {
                    result = Reading.Loading
                    refresh++
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                ensureActive()
                notice = R.string.ocr_rerun_refused
                result = Reading.Loading
                refresh++
            } finally {
                submitting = false
            }
        }
    }
    // Read the snapshot in the host composition, not only in the dialog's separate window composition.
    val displayed = result
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(panel.title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (notice ?: R.string.ocr_rerun_busy.takeIf { panel == SavedPageOcrPanel.PROCESSING && processBusy })?.let { label ->
                    val focus = remember { BringIntoViewRequester() }
                    LaunchedEffect(label) { focus.bringIntoView() }
                    Text(stringResource(label), modifier = Modifier.bringIntoViewRequester(focus).semantics { liveRegion = LiveRegionMode.Polite })
                }
                when (val shown = displayed) {
                    Reading.Loading -> Text(stringResource(R.string.ocr_details_loading))
                    Reading.Unavailable -> Text(stringResource(R.string.ocr_details_unavailable))
                    is Reading.Status -> {
                        val status = shown.value
                        Text(stringResource(ocrPhaseLabel(status?.phase, status?.lastError)))
                        if (status != null) Text(stringResource(R.string.ocr_details_attempts, status.attemptsStarted))
                        Text(stringResource(ocrErrorLabel(status?.lastError)))
                        if (status != null) {
                            OcrSettingsSummary(status.configuration)
                            planned?.let { plan ->
                                plan.input?.let { Text(stringResource(R.string.ocr_details_planned_input, it.width, it.height)) }
                                Text(stringResource(R.string.ocr_details_planned_appearance, stringResource(plan.appearance)))
                                for (stage in plan.finishing) Text(stringResource(R.string.ocr_details_planned_finishing, stringResource(stage)))
                            }
                            TextButton(onClick = { submit(status, defaults = true) }, enabled = !submitting && !processBusy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.ocr_rerun_defaults))
                            }
                            TextButton(onClick = { submit(status, defaults = false) }, enabled = !submitting && !processBusy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.ocr_rerun_saved))
                            }
                        }
                    }
                    is Reading.TextValue -> {
                        val item = shown.value
                        val text = presentOcrText(item?.text, item?.previousGeneration == true)
                        text.message?.let { Text(stringResource(it)) }
                        item?.configuration?.let { OcrSettingsSummary(it) }
                        text.verbatim?.let { original ->
                            // Do not pass private OCR to platform text classification or clipboard via selectable text.
                            Text(original, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                        }
                        val save = reader.requestSave
                        if (item != null && save != null) TextButton(
                            onClick = {
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && save(item.exportObservation)) dismiss()
                            },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("ocr-text-save-as"),
                        ) { Text(stringResource(R.string.ocr_save_text)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.atelier_close)) } },
        dismissButton = {
            TextButton(onClick = { notice = null; refresh++ }, enabled = !submitting && page != null && displayed != Reading.Loading,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ocr_details_refresh))
            }
        },
    )
}

/** Planned fields are scalars and app-owned labels only. Never retain a recipe, a result, or the paths/provenance a recipe contains. */
internal data class OcrPlannedDiagnostics(val input: PixelSize?, val appearance: Int, val finishing: List<Int>)

internal fun ocrPlannedDiagnostics(enabled: Boolean, accepted: Recipe?): OcrPlannedDiagnostics? {
    if (!enabled || accepted == null) return null
    val input = try { GeometrySizing.ocr(accepted.geometry).size } catch (_: IllegalArgumentException) { null }
    val appearance = when (accepted.appearance) {
        AppearanceRecipe.NoneV1 -> R.string.editor_look_none
        is AppearanceRecipe.PaperGainV1 -> R.string.editor_look_paper
        is AppearanceRecipe.NaturalLV1 -> R.string.editor_look_natural
        is AppearanceRecipe.DocumentColorV1 -> R.string.editor_look_document_color
        is AppearanceRecipe.PaperShadowV1 -> R.string.editor_look_paper_shadow
        is AppearanceRecipe.LocalMaxGainV1 -> R.string.ocr_details_local_gain
        is AppearanceRecipe.ProviderAppearanceV1 -> R.string.ocr_details_model_appearance
    }
    val finishing = buildList {
        if (accepted.denoise != null) add(R.string.ocr_details_noise_reduction)
        if (accepted.detail != null) add(R.string.ocr_details_sharpening)
        when (accepted.binary) {
            null -> Unit
            BinaryRecipe.GrayscaleV1 -> add(R.string.editor_look_gray)
            is BinaryRecipe.SauvolaV1 -> add(R.string.editor_look_binary)
        }
    }
    return OcrPlannedDiagnostics(input, appearance, finishing)
}

private sealed interface Reading {
    data object Loading : Reading
    data object Unavailable : Reading
    class Status(val value: SavedOcrWork.PageStatus?) : Reading
    class TextValue(val value: SavedOcrWork.ReadText?) : Reading
}

@Composable
private fun OcrSettingsSummary(configuration: OcrConfiguration) {
    val engine = if (configuration.engineId == "tesseract-android") stringResource(R.string.ocr_engine_tesseract) else configuration.engineId
    Text(stringResource(R.string.ocr_details_engine, engine, configuration.engineVersion))
    Text(stringResource(R.string.ocr_details_layout, stringResource(configuration.layout.label())))
    val ordered = configuration.languagePacks?.ordered.orEmpty()
    if (ordered.isNotEmpty()) {
        val languages = ordered.map { pack ->
            OcrLanguage.entries.firstOrNull { it.packCode == pack.code }?.let { stringResource(it.label) } ?: pack.code
        }
        Text(stringResource(R.string.ocr_details_languages, languages.joinToString(", ")))
    }
}

/** No data-class toString: recognized text is not incidental diagnostic output. */
internal class OcrTextPresentation(val message: Int?, val verbatim: String?)

internal fun presentOcrText(text: OcrText?, previousGeneration: Boolean): OcrTextPresentation = when {
    text == null -> OcrTextPresentation(R.string.ocr_text_not_available, null)
    text.value.isEmpty() -> OcrTextPresentation(if (previousGeneration) R.string.ocr_text_previous_blank else R.string.ocr_text_blank, null)
    else -> OcrTextPresentation(R.string.ocr_text_previous.takeIf { previousGeneration }, text.value)
}

internal fun ocrPhaseLabel(phase: PagePhase?, error: OcrError? = null) = when (phase) {
    null -> R.string.ocr_details_no_status
    PagePhase.QUEUED -> R.string.ocr_details_queued
    PagePhase.RUNNING -> R.string.ocr_details_running
    PagePhase.READY -> R.string.ocr_details_ready
    PagePhase.FAILED -> if (error == OcrError.PREPARATION_RESOURCE_LIMIT) R.string.ocr_details_preparation_blocked else R.string.ocr_details_failed
    PagePhase.CANCELED -> R.string.ocr_details_canceled
}

internal fun ocrErrorLabel(error: OcrError?) = when (error) {
    null -> R.string.ocr_details_no_errors
    OcrError.ENGINE_FAILED -> R.string.ocr_details_engine_failed
    OcrError.RESOURCE_LIMIT -> R.string.ocr_details_resource_limit
    OcrError.PREPARATION_RESOURCE_LIMIT -> R.string.ocr_details_input_too_large
    OcrError.INVALID_OUTPUT -> R.string.ocr_details_invalid_output
    OcrError.INTERRUPTED -> R.string.ocr_details_interrupted
}

internal fun ocrRerunLabel(outcome: RerunOutcome) = when (outcome) {
    RerunOutcome.UNAVAILABLE -> R.string.ocr_rerun_unavailable
    RerunOutcome.ENROLLED -> R.string.ocr_rerun_enrolled
    RerunOutcome.WAKE_DEFERRED -> R.string.ocr_rerun_deferred
    RerunOutcome.BUSY -> R.string.ocr_rerun_busy
}
