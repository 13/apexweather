package it.apexweather.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Color.Companion.fromArgb(argb: Long): Color = Color(argb.toInt())

val ApexColors = darkColorScheme(
    primary = Color(0xFFFFD166),
    onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFF9CC9FF),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFF4F6FB),
    surface = Color(0xFF121A2E),
    onSurface = Color(0xFFF4F6FB),
    surfaceVariant = Color(0x1AFFFFFF),
    onSurfaceVariant = Color(0xCCFFFFFF),
    outline = Color(0x33FFFFFF),
)

val ApexTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 112.sp, lineHeight = 112.sp, letterSpacing = (-4).sp, fontFeatureSettings = "tnum"),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium),
)

val ApexShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun ApexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ApexColors, typography = ApexTypography, shapes = ApexShapes, content = content)
}
