package app.pristinepage.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.pristinepage.R
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.preview.OriginalPreviewBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The keyed composition owns one original display lease, never the retained ViewModel. STARTED bounds both loading and drawing; uninterruptible old work must return privately and close before it can publish. */
@Composable internal fun OriginalPagePreview(page: DocumentPage, loadOriginal: suspend (String) -> OriginalPreviewBitmap) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val load by rememberUpdatedState(loadOriginal)
    var ready by remember { mutableStateOf<OriginalPreviewBitmap?>(null) }
    var failure by remember { mutableStateOf<OperationErrorCode?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(lifecycle, page.sourceId, attempt) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var owned: OriginalPreviewBitmap? = null
            try {
                failure = null
                owned = load(page.sourceId.value)
                // Check after suspension, not only before it: a noninterruptible decoder/adapter can return after this page's composition or Activity already ended.
                currentCoroutineContext().ensureActive()
                check(owned.sourceId == page.sourceId.value && owned.output.purpose == RenderPurpose.PREVIEW)
                check(owned.bitmap.width == owned.output.size.width && owned.bitmap.height == owned.output.size.height)
                ready = owned
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: RepositoryFailure) { failure = error.error.code }
            catch (_: Exception) { failure = OperationErrorCode.PROCESSING_FAILED }
            finally {
                ready = null
                owned?.close()
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("original-preview-region")) {
        Text(stringResource(R.string.original_preview_description, page.ordinal + 1), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.original_preview_notice))
        val image = ready
        if (image != null) {
            // A small raster can have an extreme aspect. Bound the containing viewport before fitting either axis; fill-width/aspectRatio in a vertical scroll can otherwise request an unrepresentable hundreds-of-thousands-pixel height after decode succeeds.
            val maximumHeight = maxOf(48.dp, LocalConfiguration.current.screenHeightDp.dp * 0.5f)
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = maximumHeight)) {
                val ratio = image.output.size.width.toFloat() / image.output.size.height
                val width = minOf(maxWidth, maxHeight * ratio)
                val height = minOf(maxHeight, maxWidth / ratio)
                Image(image.bitmap.asImageBitmap(), stringResource(R.string.original_preview_description, page.ordinal + 1), Modifier.width(width).height(height).align(Alignment.Center).testTag("original-preview"), contentScale = ContentScale.Fit)
            }
        } else if (failure == null) {
            val description = stringResource(R.string.original_preview_loading, page.ordinal + 1)
            CircularProgressIndicator(Modifier.testTag("original-preview-loading").semantics { contentDescription = description })
        }
        failure?.let { code ->
            Text(errorText(code), color = MaterialTheme.colorScheme.error)
            Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth().testTag("retry-original-preview")) { Text(stringResource(R.string.retry_original_preview, page.ordinal + 1)) }
        }
    }
}
