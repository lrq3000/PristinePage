package app.pristinepage.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Semantic roles from docs/design/atelier/v1. Leave system font scaling and dynamic safe insets to consumers. */
internal object AtelierStyle {
    val canvas = Color(0xFF10191D)
    val divider = Color(0xFF435257)
    val focus = Color(0xFFB9FFD2)
    val camera = Color(0xFF142021)
    val shutter = Color(0xFFF5F6E9)
    val touchTarget = 48.dp
    val surfaceRadius = 12.dp
}

private val atelierColors = darkColorScheme(
    background = Color(0xFF1B2428), onBackground = Color(0xFFF2F7F5),
    surface = Color(0xFF273237), onSurface = Color(0xFFF2F7F5), onSurfaceVariant = Color(0xFFB9CCC5),
    primary = Color(0xFFB5E0D5), onPrimary = Color(0xFF142C28),
    primaryContainer = Color(0xFF344B49), onPrimaryContainer = Color(0xFFF2F7F5),
    surfaceVariant = Color(0xFF344B49), outline = Color(0xFF697E82), outlineVariant = AtelierStyle.divider,
    error = Color(0xFFFFB4AB), onError = Color(0xFF3A0B08),
)

@Composable internal fun AtelierTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = atelierColors, content = content)
