package app.pristinepage.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pristinepage.R
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.recipe.ContourSide
import app.pristinepage.core.recipe.GeometryRecipe

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AtelierBezierControls(geometry: GeometryRecipe.Bezier, enabled: Boolean,
    session: EditorSession, canvas: @Composable (BezierControl) -> Unit) {
    val contour = geometry.contour
    var side by rememberSaveable { mutableStateOf(ContourSide.TOP) }
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var part by rememberSaveable { mutableStateOf(BezierPart.OUTGOING) }
    val edge = contour.edge(side)
    val index = segment.coerceIn(edge.segments.indices)
    val parts = BezierPart.entries.filter { BezierControl(side, index, it).read(contour) != null }
    val control = BezierControl(side, index, part.takeIf { it in parts } ?: BezierPart.OUTGOING)
    val selected = checkNotNull(control.read(contour))
    canvas(control)
    Text(stringResource(R.string.atelier_bezier_controls), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (option in ContourSide.entries) FilterChip(side == option, { side = option; segment = 0 },
            { Text(stringResource(option.label())) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton({ segment = index - 1 }, enabled = enabled && index > 0) { Text(stringResource(R.string.atelier_bezier_previous)) }
        TextButton({ segment = index + 1 }, enabled = enabled && index < edge.segments.lastIndex) { Text(stringResource(R.string.atelier_bezier_next)) }
    }
    Text(stringResource(R.string.atelier_bezier_segment, index + 1, edge.segments.size))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (option in parts) FilterChip(control.part == option, { part = option },
            { Text(stringResource(option.label())) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
    }
    Text(stringResource(R.string.atelier_bezier_position, selected.x * 100, selected.y * 100))
    fun nudge(dx: Int, dy: Int) {
        val size = geometry.source.sourceSize
        session.moveBezier(control, PointPx((selected.x + dx.toDouble() / (size.width - 1)).coerceIn(0.0, 1.0),
            (selected.y + dy.toDouble() / (size.height - 1)).coerceIn(0.0, 1.0)))
    }
    FlowRow {
        TextButton({ nudge(-1, 0) }, enabled = enabled) { Text(stringResource(R.string.atelier_bezier_left)) }
        TextButton({ nudge(1, 0) }, enabled = enabled) { Text(stringResource(R.string.atelier_bezier_right)) }
        TextButton({ nudge(0, -1) }, enabled = enabled) { Text(stringResource(R.string.atelier_bezier_up)) }
        TextButton({ nudge(0, 1) }, enabled = enabled, modifier = Modifier.testTag("atelier-bezier-down")) { Text(stringResource(R.string.atelier_bezier_down)) }
    }
    val piece = edge.segments[index]
    val node = control.nodeIndex
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({
            session.insertBezier(side, index)
            part = BezierPart.END
        }, enabled = enabled && edge.segments.size < 8 && piece.to - piece.from >= 2e-5,
            modifier = Modifier.testTag("atelier-bezier-insert")) { Text(stringResource(R.string.atelier_bezier_insert)) }
        OutlinedButton({
            if (node != null) { session.removeBezier(side, node); segment = node - 1; part = BezierPart.OUTGOING }
        }, enabled = enabled && node != null, modifier = Modifier.testTag("atelier-bezier-remove")) {
            Text(stringResource(R.string.atelier_bezier_remove))
        }
    }
}

private fun ContourSide.label() = when (this) {
    ContourSide.TOP -> R.string.atelier_bezier_side_top
    ContourSide.RIGHT -> R.string.atelier_bezier_side_right
    ContourSide.BOTTOM -> R.string.atelier_bezier_side_bottom
    ContourSide.LEFT -> R.string.atelier_bezier_side_left
}

private fun BezierPart.label() = when (this) {
    BezierPart.START -> R.string.atelier_bezier_start_node
    BezierPart.OUTGOING -> R.string.atelier_bezier_start_handle
    BezierPart.INCOMING -> R.string.atelier_bezier_end_handle
    BezierPart.END -> R.string.atelier_bezier_end_node
}
