package app.pristinepage.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.pristinepage.R
import app.pristinepage.core.geometry.AtelierEdgeDetector
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.ContourSide
import app.pristinepage.core.recipe.BezierContour
import app.pristinepage.core.recipe.BezierSegment
import app.pristinepage.design.AtelierStyle
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
    onSelectBezier: (BezierControl) -> Unit = {},
    onSourceReady: (suspend (android.graphics.Bitmap) -> Unit)? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val load by rememberUpdatedState(loadOriginal)
    var original by remember(sourceId) { mutableStateOf<OriginalPreviewBitmap?>(null) }
    var failed by remember(sourceId) { mutableStateOf(false) }
    var attempt by remember(sourceId) { mutableIntStateOf(0) }
    var finding by remember(sourceId) { mutableStateOf(false) }
    var feedback by remember(sourceId) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    var magnify by remember(sourceId) { mutableStateOf<PointPx?>(null) }
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
    val sourceReady by rememberUpdatedState(onSourceReady)
    LaunchedEffect(session, image) { image?.let { sourceReady?.invoke(it.bitmap) } }
    if (image == null) {
        Box(Modifier.fillMaxWidth().height(260.dp).background(AtelierStyle.canvas).testTag("atelier-frame-canvas"),
            contentAlignment = Alignment.Center) {
            if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.atelier_thumbnail_failed))
                Button(onClick = { attempt++ }) { Text(stringResource(R.string.editor_retry_preview)) }
            } else Text(stringResource(R.string.atelier_frame_loading))
        }
        return
    }
    val current by rememberUpdatedState(geometry)
    val chosen by rememberUpdatedState(cubicControl)
    val select by rememberUpdatedState(onSelectBezier)
    val w = image.output.size.width
    val h = image.output.size.height
    val points = EditorShapes.points(geometry)
    val bitmap = remember(image) { image.bitmap.asImageBitmap() }
    Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(AtelierStyle.surfaceRadius))
        .background(AtelierStyle.canvas).testTag("atelier-frame-canvas")) {
        Image(bitmap, stringResource(R.string.editor_original_description), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize().pointerInput(w, h, enabled, session) {
            if (!enabled) return@pointerInput
            val imageRect = fitted(size.width.toFloat(), size.height.toFloat(), w, h)
            val radius = 26.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val cubic = current as? GeometryRecipe.Bezier
                val control = cubic?.let { nearestBezierControl(it.contour, down.position, imageRect, radius, chosen) }
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
                if (control != null) select(control)
                fun move(position: Offset) {
                    val target = position + anchorOffset
                    val x = ((target.x - imageRect.left) / imageRect.width).toDouble().coerceIn(0.0, 1.0)
                    val y = ((target.y - imageRect.top) / imageRect.height).toDouble().coerceIn(0.0, 1.0)
                    magnify = PointPx(x, y)
                    if (cubicDrag != null) session.moveBezier(cubicDrag.control, PointPx(x, y), cubicDrag)
                    else session.dragFrameCorner(checkNotNull(nearest).key.name, x, y)
                }
                var ended = false
                try {
                    move(first.position)
                    ended = drag(first.id) { change -> move(change.position); change.consume() }
                } finally {
                    magnify = null
                    if (cubicDrag != null) session.endBezierDrag(cubicDrag, cancel = !ended)
                    else session.endFrameDrag(cancel = !ended)
                }
            }
        }.pointerInput(w, h, enabled, session) {
            if (!enabled) return@pointerInput
            detectTapGestures(onDoubleTap = { position ->
                val cubic = current as? GeometryRecipe.Bezier ?: return@detectTapGestures
                val rect = fitted(size.width.toFloat(), size.height.toFloat(), w, h)
                nearestInsertion(cubic.contour, position, rect, 22.dp.toPx(), 8.dp.toPx())?.let { hit ->
                    session.insertBezier(hit.control.side, hit.control.segment, hit.t)
                    select(hit.control)
                }
            }, onTap = { position ->
                val cubic = current as? GeometryRecipe.Bezier ?: return@detectTapGestures
                val rect = fitted(size.width.toFloat(), size.height.toFloat(), w, h)
                nearestBezierControl(cubic.contour, position, rect, 26.dp.toPx(), chosen)?.let(select)
            })
        }) {
            val rect = fitted(size.width, size.height, w, h)
            val cubic = (geometry as? GeometryRecipe.Bezier)?.contour
            val anchors = (cubic?.quad?.points() ?: points.values.take(4)).map { project(it, rect) }
            if (anchors.isEmpty()) return@Canvas
            val contour = Path().apply {
                moveTo(anchors.first().x, anchors.first().y)
                if (geometry is GeometryRecipe.ManualCurve) {
                    fun quadratic(a: Offset, midpoint: PointPx, b: Offset) {
                        val p = project(midpoint, rect) * 2f - (a + b) * .5f
                        quadraticTo(p.x, p.y, b.x, b.y)
                    }
                    quadratic(anchors[0], geometry.topMidpoint, anchors[1])
                    lineTo(anchors[2].x, anchors[2].y)
                    quadratic(anchors[2], geometry.bottomMidpoint, anchors[3])
                } else if (cubic == null) anchors.drop(1).forEach { lineTo(it.x, it.y) }
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
            if (geometry is GeometryRecipe.ManualCurve) {
                listOf(geometry.topMidpoint, geometry.bottomMidpoint).forEach { p ->
                    drawCircle(Color(0xFF152E28), 8.dp.toPx(), project(p, rect))
                    drawCircle(Color(0xFFB9FFD2), 6.dp.toPx(), project(p, rect))
                }
            }
            if (cubic != null) {
                for (side in ContourSide.entries) cubic.edge(side).segments.forEachIndexed { index, piece ->
                    val a = project(piece.start, rect)
                    val b = project(piece.end, rect)
                    val out = project(piece.outgoing, rect)
                    val inside = project(piece.incoming, rect)
                    drawLine(Color(0xFFB9FFD2), a, out, 1.dp.toPx())
                    drawLine(Color(0xFFB9FFD2), b, inside, 1.dp.toPx())
                    listOf(out, inside).forEach {
                        drawCircle(Color(0xFF152E28), 6.dp.toPx(), it)
                        drawCircle(Color(0xFFB9FFD2), 4.dp.toPx(), it)
                    }
                    if (index > 0) {
                        drawCircle(Color(0xFF152E28), 8.dp.toPx(), a)
                        drawCircle(Color(0xFFF6FFF1), 6.dp.toPx(), a)
                    }
                }
                cubicControl?.read(cubic)?.let { drawCircle(Color(0xFFB9FFD2), 10.dp.toPx(), project(it, rect), style = Stroke(2.dp.toPx())) }
            }
        }
        magnify?.let { target ->
            val label = stringResource(R.string.carnet_adjusting_point)
            Canvas(Modifier.align(if (target.x > .5) Alignment.TopStart else Alignment.TopEnd).padding(6.dp).size(98.dp)
                .clip(RoundedCornerShape(10.dp)).border(2.dp, Color.White, RoundedCornerShape(10.dp))
                .background(Color(0xFF152E28)).testTag("carnet-crop-magnifier")
                .semantics { contentDescription = label }) {
                // Keep the selected point at the crosshair even at image edges; the outside remains padding.
                val span = (minOf(w, h) / 7).coerceAtLeast(1)
                val x = (target.x * (w - 1)).roundToInt()
                val y = (target.y * (h - 1)).roundToInt()
                val x0 = (x - span / 2).coerceAtLeast(0)
                val y0 = (y - span / 2).coerceAtLeast(0)
                val x1 = (x - span / 2 + span).coerceAtMost(w)
                val y1 = (y - span / 2 + span).coerceAtMost(h)
                val scale = size.width / span
                drawImage(bitmap, IntOffset(x0, y0), IntSize(x1 - x0, y1 - y0),
                    IntOffset((center.x + (x0 - x) * scale).roundToInt(), (center.y + (y0 - y) * scale).roundToInt()),
                    IntSize(((x1 - x0) * scale).roundToInt().coerceAtLeast(1), ((y1 - y0) * scale).roundToInt().coerceAtLeast(1)))
                drawCircle(Color.White, 8.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                drawCircle(Color(0xFF152E28), 10.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
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

private fun nearestBezierControl(contour: BezierContour, touch: Offset, rect: Rect, radius: Float,
    selected: BezierControl?): BezierControl? {
    val controls = ContourSide.entries.flatMap { side ->
        contour.edge(side).segments.indices.flatMap { index -> BezierPart.entries.map { BezierControl(side, index, it) } }
    }
    return (listOfNotNull(selected) + controls).mapNotNull { control ->
        control.read(contour)?.let { control to (project(it, rect) - touch).getDistance() }
    }.minByOrNull { it.second }?.takeIf { it.second <= radius }?.first
}

internal data class EdgeHit(val control: BezierControl, val t: Double)

/** Bounded nearest-site search on the actual drawn cubic, not the chord between endpoints. */
internal fun nearestInsertion(contour: BezierContour, touch: Offset, rect: Rect, radius: Float, nodeRadius: Float): EdgeHit? {
    // Double taps reserve actual node positions, not floating handles that can lie on the insertion edge.
    if (ContourSide.entries.any { side ->
        val segments = contour.edge(side).segments
        segments.any { (project(it.start, rect) - touch).getDistance() <= nodeRadius } ||
            (project(segments.last().end, rect) - touch).getDistance() <= nodeRadius
    }) return null
    fun position(piece: BezierSegment, t: Double): Offset {
        val s = 1 - t
        val c = doubleArrayOf(s * s * s, 3 * s * s * t, 3 * s * t * t, t * t * t)
        val p = listOf(piece.start, piece.outgoing, piece.incoming, piece.end)
        return project(PointPx(p.indices.sumOf { c[it] * p[it].x }, p.indices.sumOf { c[it] * p[it].y }), rect)
    }
    var hit: EdgeHit? = null
    var best = radius
    for (side in ContourSide.entries) {
        val edge = contour.edge(side)
        if (edge.segments.size >= 8) continue
        for ((index, piece) in edge.segments.withIndex()) {
            val limit = maxOf(.02, 1.1e-5 / (piece.to - piece.from))
            if (limit >= .5) continue
            for (sample in 0..48) {
                val t = limit + (1 - 2 * limit) * sample / 48
                val distance = (position(piece, t) - touch).getDistance()
                if (distance < best) { best = distance; hit = EdgeHit(BezierControl(side, index, BezierPart.END), t) }
            }
        }
    }
    return hit
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
