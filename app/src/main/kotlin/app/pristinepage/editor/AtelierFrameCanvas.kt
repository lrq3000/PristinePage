package app.pristinepage.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.pristinepage.R
import app.pristinepage.core.geometry.AtelierEdgeDetector
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.ContourSide
import app.pristinepage.image.preview.OriginalPreviewBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The frame always edits the verified oriented source, not the rotated output or a rounded/padded viewport. */
@Composable internal fun AtelierFrameCanvas(
    sourceId: String,
    geometry: GeometryRecipe,
    enabled: Boolean,
    loadOriginal: suspend (String) -> OriginalPreviewBitmap,
    session: EditorSession,
    cubicControl: BezierControl? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val load by rememberUpdatedState(loadOriginal)
    var original by remember(sourceId) { mutableStateOf<OriginalPreviewBitmap?>(null) }
    var failed by remember(sourceId) { mutableStateOf(false) }
    var attempt by remember(sourceId) { mutableIntStateOf(0) }
    var finding by remember(sourceId) { mutableStateOf(false) }
    var feedback by remember(sourceId) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sourceId, lifecycle, attempt) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var resource: OriginalPreviewBitmap? = null
            try {
                failed = false
                resource = load(sourceId)
                currentCoroutineContext().ensureActive()
                check(resource.sourceId == sourceId)
                original = resource
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { failed = true }
            finally { original = null; resource?.close() }
        }
    }
    val image = original
    if (image == null) {
        if (failed) {
            Text(stringResource(R.string.atelier_thumbnail_failed))
            Button(onClick = { attempt++ }) { Text(stringResource(R.string.editor_retry_preview)) }
        } else Text(stringResource(R.string.atelier_frame_loading))
        return
    }
    val current by rememberUpdatedState(geometry)
    val chosen by rememberUpdatedState(cubicControl)
    val w = image.output.size.width
    val h = image.output.size.height
    val points = EditorShapes.points(geometry)
    Box(Modifier.fillMaxWidth().height(260.dp).testTag("atelier-frame-canvas")) {
        Image(image.bitmap.asImageBitmap(), stringResource(R.string.editor_original_description), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize().pointerInput(w, h, enabled, session) {
            if (!enabled) return@pointerInput
            val imageRect = fitted(size.width.toFloat(), size.height.toFloat(), w, h)
            val radius = 26.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val cubic = current as? GeometryRecipe.Bezier
                val control = chosen
                val handle = if (cubic != null && control != null) control.read(cubic.contour) else null
                val nearest = EditorShapes.points(current).minByOrNull { (_, p) ->
                    (project(p, imageRect) - down.position).getDistance()
                }
                val anchor = handle ?: nearest?.value ?: return@awaitEachGesture
                val anchorOffset = project(anchor, imageRect) - down.position
                // A gesture beginning away from a corner still belongs to the surrounding scroll container.
                if (anchorOffset.getDistance() > radius) return@awaitEachGesture
                val first = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    ?: return@awaitEachGesture
                val cubicDrag = if (control != null && handle != null) session.beginBezierDrag(control) else null
                if (handle != null && cubicDrag == null) return@awaitEachGesture
                if (cubicDrag == null && (nearest == null || !session.beginFrameDrag(nearest.key))) return@awaitEachGesture
                fun move(position: Offset) {
                    val target = position + anchorOffset
                    val x = ((target.x - imageRect.left) / imageRect.width).toDouble().coerceIn(0.0, 1.0)
                    val y = ((target.y - imageRect.top) / imageRect.height).toDouble().coerceIn(0.0, 1.0)
                    if (cubicDrag != null) session.moveBezier(cubicDrag.control, PointPx(x, y), cubicDrag)
                    else session.dragFrameCorner(checkNotNull(nearest).key.name, x, y)
                }
                var ended = false
                try {
                    move(first.position)
                    ended = drag(first.id) { change -> move(change.position); change.consume() }
                } finally {
                    if (cubicDrag != null) session.endBezierDrag(cubicDrag, cancel = !ended)
                    else session.endFrameDrag(cancel = !ended)
                }
            }
        }) {
            val rect = fitted(size.width, size.height, w, h)
            val cubic = (geometry as? GeometryRecipe.Bezier)?.contour
            val anchors = (cubic?.quad?.points() ?: points.values).map { project(it, rect) }
            val contour = Path().apply {
                moveTo(anchors.first().x, anchors.first().y)
                if (cubic == null) anchors.drop(1).forEach { lineTo(it.x, it.y) }
                else for (side in ContourSide.entries) {
                    val edge = cubic.edge(side)
                    val reverse = side == ContourSide.BOTTOM || side == ContourSide.LEFT
                    for (piece in if (reverse) edge.segments.asReversed() else edge.segments) {
                        val end = project(if (reverse) piece.start else piece.end, rect)
                        if (edge.chordOnly) lineTo(end.x, end.y)
                        else {
                            val a = project(if (reverse) piece.incoming else piece.outgoing, rect)
                            val b = project(if (reverse) piece.outgoing else piece.incoming, rect)
                            cubicTo(a.x, a.y, b.x, b.y, end.x, end.y)
                        }
                    }
                }
                close()
            }
            drawPath(Path().apply { fillType = PathFillType.EvenOdd; addRect(rect); addPath(contour) }, Color(0x94142B27))
            drawPath(contour, Color(0xFF152E28), style = Stroke(4.dp.toPx()))
            drawPath(contour, Color(0xFFB9FFD2), style = Stroke(2.dp.toPx()))
            anchors.forEach { anchor ->
                drawCircle(Color(0xFF152E28), 8.dp.toPx(), anchor)
                drawCircle(Color(0xFFF6FFF1), 6.dp.toPx(), anchor)
            }
            if (cubic != null && cubicControl != null) {
                val piece = cubic.edge(cubicControl.side).segments.getOrNull(cubicControl.segment)
                if (piece != null) {
                    val a = project(piece.start, rect)
                    val b = project(piece.end, rect)
                    val out = project(piece.outgoing, rect)
                    val inside = project(piece.incoming, rect)
                    drawLine(Color(0xFFB9FFD2), a, out, 2.dp.toPx())
                    drawLine(Color(0xFFB9FFD2), b, inside, 2.dp.toPx())
                    listOf(a, b, out, inside).forEach { drawCircle(Color(0xFFF6FFF1), 4.dp.toPx(), it) }
                    cubicControl.read(cubic)?.let { drawCircle(Color(0xFFB9FFD2), 10.dp.toPx(), project(it, rect), style = Stroke(2.dp.toPx())) }
                }
            }
        }
    }
    if (geometry !is GeometryRecipe.Perspective) return
    Button(enabled = enabled && !finding, onClick = {
        val bitmap = image.bitmap
        val expected = geometry
        finding = true
        feedback = null
        scope.launch {
            try {
                val result = detectAtelierFrame(bitmap)
                feedback = when {
                    result == null -> R.string.atelier_frame_no_edges
                    original === image && session.useDetectedFrame(expected, result) -> null
                    else -> R.string.atelier_frame_not_replaced
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { feedback = R.string.atelier_frame_no_edges }
            finally { finding = false }
        }
    }, modifier = Modifier.heightIn(min = 48.dp).testTag("atelier-detect-frame")) {
        Text(stringResource(if (finding) R.string.atelier_finding_edges else R.string.atelier_find_edges))
    }
    feedback?.let { Text(stringResource(it)) }
}

internal suspend fun detectAtelierFrame(bitmap: android.graphics.Bitmap): app.pristinepage.core.pipeline.QuadPx? = withContext(Dispatchers.Default) {
    val w = bitmap.width; val h = bitmap.height
    val scale = 384.0 / maxOf(w, h)
    val dw = minOf(w, (w * scale).roundToInt())
    val dh = minOf(h, (h * scale).roundToInt())
    if (dw < 2 || dh < 2) return@withContext null
    AtelierEdgeDetector.detect(dw, dh) { x, y ->
        val rgb = bitmap.getPixel((x.toDouble() * (w - 1) / (dw - 1)).roundToInt(),
            (y.toDouble() * (h - 1) / (dh - 1)).roundToInt())
        (77 * (rgb shr 16 and 255) + 150 * (rgb shr 8 and 255) + 29 * (rgb and 255) + 128) shr 8
    }
}

private fun fitted(width: Float, height: Float, imageWidth: Int, imageHeight: Int): Rect {
    val scale = minOf(width / imageWidth, height / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    return Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
}

private fun project(point: PointPx, rect: Rect) =
    Offset(rect.left + (point.x * rect.width).toFloat(), rect.top + (point.y * rect.height).toFloat())
