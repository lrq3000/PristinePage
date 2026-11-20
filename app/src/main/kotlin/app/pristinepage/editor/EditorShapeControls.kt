package app.pristinepage.editor

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.pristinepage.R
import app.pristinepage.core.recipe.GeometryRecipe
import kotlin.math.roundToInt

/** Source-space controls leave the Result viewport and preview owner unchanged. */
@Composable internal fun EditorShapeControls(geometry: GeometryRecipe, enabled: Boolean, session: EditorSession) {
    var replace by remember(session) { mutableStateOf<(() -> Unit)?>(null) }
    fun choose(action: () -> Unit) {
        if (geometry is GeometryRecipe.Bezier) replace = action else action()
    }
    Text(stringResource(R.string.editor_shape), style = MaterialTheme.typography.titleLarge)
    OutlinedButton(
        onClick = { choose(session::resetShape) },
        enabled = enabled && geometry !is GeometryRecipe.None,
        modifier = shapeTarget("editor-shape-reset"),
    ) { Text(stringResource(R.string.editor_shape_reset)) }
    if (!EditorShapes.canAdjust(geometry)) return

    OutlinedButton(
        onClick = { choose(session::selectPerspective) },
        enabled = enabled,
        modifier = shapeTarget("editor-shape-perspective").semantics { selected = geometry is GeometryRecipe.Perspective },
    ) { Text(stringResource(R.string.editor_shape_perspective)) }
    OutlinedButton(
        onClick = { choose(session::selectManualCurve) },
        enabled = enabled,
        modifier = shapeTarget("editor-shape-curve").semantics { selected = geometry is GeometryRecipe.ManualCurve },
    ) { Text(stringResource(R.string.editor_shape_curve)) }
    OutlinedButton(
        onClick = session::selectBezier,
        enabled = enabled && geometry !is GeometryRecipe.Bezier,
        modifier = shapeTarget("editor-shape-bezier").semantics { selected = geometry is GeometryRecipe.Bezier },
    ) { Text(stringResource(R.string.atelier_bezier_mode)) }
    replace?.let { action ->
        AlertDialog(onDismissRequest = { replace = null },
            title = { Text(stringResource(R.string.atelier_bezier_replace)) },
            text = { Text(stringResource(R.string.atelier_bezier_replace_body)) },
            confirmButton = { TextButton({ replace = null; action() }, enabled = enabled) { Text(stringResource(R.string.atelier_bezier_replace_confirm)) } },
            dismissButton = { TextButton({ replace = null }) { Text(stringResource(R.string.cancel_import)) } })
    }
    val points = EditorShapes.points(geometry)
    if (points.isEmpty()) return

    var selectedPoint by remember(session) { mutableStateOf(ShapePoint.TOP_LEFT) }
    var expanded by remember { mutableStateOf(false) }
    val label = if (selectedPoint in points) selectedPoint else ShapePoint.TOP_LEFT
    val point = checkNotNull(points[label])
    val pointName = stringResource(label.label())
    Text(stringResource(R.string.editor_shape_source_axes))
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = shapeTarget("editor-shape-point"),
        ) { Text(stringResource(R.string.editor_shape_edit_point, pointName)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            points.keys.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.label())) },
                    onClick = { selectedPoint = entry; expanded = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("editor-shape-point-${entry.name}").semantics { selected = entry == label },
                )
            }
        }
    }
    ShapeCoordinate("editor-shape-x", stringResource(R.string.editor_shape_x, pointName), point.x, enabled, session, label, horizontal = true)
    ShapeCoordinate("editor-shape-y", stringResource(R.string.editor_shape_y, pointName), point.y, enabled, session, label, horizontal = false)
}

@Composable private fun ShapeCoordinate(tag: String, label: String, value: Double, enabled: Boolean, session: EditorSession, point: ShapePoint, horizontal: Boolean) = key(session, point, horizontal) {
    // Replace the Slider and its pointer node when the control changes, not just the callback and interaction source.
    val interactions = remember(enabled) { ShapeDragInteractions(session, point, horizontal) }
    DisposableEffect(interactions) { onDispose(interactions::dispose) }
    val percent = (value * 100).roundToInt()
    val keyStep = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    Text(stringResource(R.string.editor_shape_coordinate, label, percent))
    Slider(
        value = value.toFloat(),
        onValueChange = { interactions.change((it * 100).roundToInt()) },
        enabled = enabled,
        steps = 99,
        interactionSource = interactions,
        modifier = shapeTarget(tag).onPreviewKeyEvent { event ->
            // Normally Material handles keys. During pointer drag, explicitly route recognized keyboard edits as discrete so no key can enter the pointer transaction.
            if (!enabled || !interactions.dragging || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val next = when (event.key) {
                Key.DirectionUp, Key.DirectionRight -> percent + keyStep
                Key.DirectionDown, Key.DirectionLeft -> percent - keyStep
                Key.MoveHome -> 0
                Key.MoveEnd -> 100
                Key.PageUp -> percent - 10
                Key.PageDown -> percent + 10
                else -> return@onPreviewKeyEvent false
            }
            interactions.discrete(next)
            true
        }.semantics {
            contentDescription = label
            setProgress { target ->
                if (!enabled || !target.isFinite()) return@setProgress false
                val next = (target.coerceIn(0f, 1f) * 100).roundToInt()
                if (next == percent) false else { interactions.discrete(next); true }
            }
        },
    )
}

/** Observe the actual Slider drag interactions synchronously, not a flow collector that could run after the first delta or miss disposal. Preserve the normal interaction source for Material feedback. */
private class ShapeDragInteractions(
    private val session: EditorSession,
    private val point: ShapePoint,
    private val horizontal: Boolean,
    private val source: MutableInteractionSource = MutableInteractionSource(),
) : MutableInteractionSource by source {
    private var start: DragInteraction.Start? = null
    private var drag: EditorSession.ShapeDrag? = null
    private var disposed = false
    val dragging get() = start != null

    override suspend fun emit(interaction: Interaction) {
        observe(interaction)
        source.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        observe(interaction)
        return source.tryEmit(interaction)
    }

    fun change(percent: Int) {
        if (!disposed && (!dragging || drag != null)) {
            session.setShapeCoordinate(point, horizontal, percent / 100.0, drag)
        }
    }

    fun discrete(percent: Int) {
        if (!disposed) session.setShapeCoordinate(point, horizontal, percent.coerceIn(0, 100) / 100.0)
    }

    fun dispose() { disposed = true; end() }

    private fun observe(interaction: Interaction) {
        if (disposed) return
        when (interaction) {
            is DragInteraction.Start -> {
                end()
                start = interaction
                drag = session.beginShapeDrag(point, horizontal)
            }
            is DragInteraction.Stop -> if (interaction.start === start) end()
            is DragInteraction.Cancel -> if (interaction.start === start) end()
            else -> Unit
        }
    }

    private fun end() {
        drag?.let { session.endShapeDrag(it) }
        drag = null
        start = null
    }
}

private fun shapeTarget(tag: String) = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)

private fun ShapePoint.label() = when (this) {
    ShapePoint.TOP_LEFT -> R.string.editor_shape_top_left
    ShapePoint.TOP_RIGHT -> R.string.editor_shape_top_right
    ShapePoint.BOTTOM_RIGHT -> R.string.editor_shape_bottom_right
    ShapePoint.BOTTOM_LEFT -> R.string.editor_shape_bottom_left
    ShapePoint.TOP_MIDPOINT -> R.string.editor_shape_top_midpoint
    ShapePoint.BOTTOM_MIDPOINT -> R.string.editor_shape_bottom_midpoint
}
