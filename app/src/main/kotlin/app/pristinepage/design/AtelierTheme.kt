package app.pristinepage.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual roles; keep viewfinder/contour contrast independent of the library's paper palette. */
internal object AtelierStyle {
    val canvas = Color(0xFFE3E8DA)
    val divider = Color(0xFFD6DDD0)
    val focus = Color(0xFFB9FFD2)
    val camera = Color(0xFF142021)
    val shutter = Color(0xFFF5F6E9)
    val touchTarget = 48.dp
    val surfaceRadius = 17.dp
}

private val ink = Color(0xFF182D24)
private val muted = Color(0xFF4F6355)
private val forest = Color(0xFF244F3E)
private val sage = Color(0xFFE2EBD3)
private val carnetColors = lightColorScheme(
    background = Color(0xFFF8F8EF), onBackground = ink,
    surface = Color(0xFFFFFDF4), onSurface = ink, onSurfaceVariant = muted,
    primary = forest, onPrimary = Color.White, primaryContainer = sage, onPrimaryContainer = ink,
    secondary = forest, onSecondary = Color.White, secondaryContainer = sage, onSecondaryContainer = ink,
    tertiary = muted, onTertiary = Color.White, tertiaryContainer = sage, onTertiaryContainer = ink,
    surfaceVariant = sage, surfaceTint = forest, outline = Color(0xFF717E72), outlineVariant = AtelierStyle.divider,
    surfaceContainerLowest = Color(0xFFFFFDF4), surfaceContainerLow = Color(0xFFF8F8EF),
    surfaceContainer = Color(0xFFF1F3E8), surfaceContainerHigh = Color(0xFFEBEFE3),
    surfaceContainerHighest = AtelierStyle.canvas,
)

private val defaults = Typography()
private val carnetTypography = Typography(
    displaySmall = defaults.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
        fontSize = 33.sp, lineHeight = 38.sp, letterSpacing = (-1).sp),
)
private val carnetShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(AtelierStyle.surfaceRadius),
    large = RoundedCornerShape(AtelierStyle.surfaceRadius),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable internal fun CarnetTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = carnetColors, typography = carnetTypography, shapes = carnetShapes, content = content)

private val viewfinderColors = darkColorScheme(
    background = AtelierStyle.camera, onBackground = Color(0xFFF2F4EA),
    surface = Color(0xFF263434), onSurface = Color(0xFFF2F4EA), onSurfaceVariant = Color(0xFFC0CCC4),
    primary = Color(0xFFB5E0D5), onPrimary = Color(0xFF142C28),
    primaryContainer = Color(0xFF344B49), onPrimaryContainer = Color(0xFFF2F4EA),
    secondaryContainer = Color(0xFF344B49), onSecondaryContainer = Color(0xFFF2F4EA),
    surfaceContainer = Color(0xFF263434), outline = Color(0xFF9AABA6),
)

@Composable internal fun CarnetViewfinderTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = viewfinderColors, content = content)
