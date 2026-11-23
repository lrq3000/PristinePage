package app.pristinepage.ocr

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pristinepage.R

/** The request survives dialog disposal and Activity recreation, but never grants a newly started process write authority. */
@Composable internal fun OcrTextSaveHost(owner: OcrTextSaveViewModel, registry: ActivityResultRegistry) {
    val state by owner.state.collectAsStateWithLifecycle()
    val consent = state.consent
    var launcher by remember(consent, registry) { mutableStateOf<ActivityResultLauncher<Intent>?>(null) }
    DisposableEffect(consent, registry) {
        val registration = consent?.let { exactId ->
            registry.register("pristinepage-ocr-text-$exactId", ActivityResultContracts.StartActivityForResult()) {
                owner.accept(exactId, it.resultCode, it.data)
            }
        }
        launcher = registration
        onDispose { registration?.unregister() }
    }
    LaunchedEffect(consent, launcher, state.launchRequested) {
        val id = consent ?: return@LaunchedEffect
        val ready = launcher ?: return@LaunchedEffect
        if (!state.launchRequested) return@LaunchedEffect
        val intent = owner.consumeLaunch(id)
        if (intent == null) { owner.launchFailed(id); return@LaunchedEffect }
        try { ready.launch(intent) } catch (_: RuntimeException) { owner.launchFailed(id) }
    }
    if (!state.busy) {
        val notice = state.notice ?: R.string.ocr_save_text_interrupted.takeIf { state.pastWriteNeedsVerification }
        if (notice != null) AlertDialog(
            onDismissRequest = owner::dismissNotice,
            title = { Text(stringResource(R.string.ocr_save_text)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(notice))
                state.destination?.let { Text(it.label) }
            } },
            confirmButton = { TextButton(onClick = owner::dismissNotice, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.atelier_close))
            } },
            dismissButton = if (!state.mayRetry) null else {
                { TextButton(onClick = owner::retry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ocr_save_text_retry))
                } }
            },
        )
    }
}
