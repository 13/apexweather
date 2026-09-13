package it.apexweather.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.rememberFormats
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
    // The hero temperature, and the single largest thing on the home screen by a long way.
    //
    // Eighty-eight rather than a hundred and twelve. At 112 sp the two digits and the degree sign
    // stood 131 dp tall on a 832 dp screen, and the hero block around them took 252 dp — a third of
    // everything above the bottom bar — to show one number. At 88 sp it is 103 dp, still more than
    // three times the size of anything else on the screen, and the day list starts a row and a half
    // higher. The tracking moves with it: -4 sp was -3.6 % of the size, and -3 sp is the same
    // fraction of this one, so the digits keep the spacing they were drawn with.
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 88.sp, lineHeight = 88.sp, letterSpacing = (-3).sp, fontFeatureSettings = "tnum"),
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
    // Formats rides along with the theme: every screen that writes a number or a time needs the
    // reader's language and clock preference, and resolving it here means no screen can forget.
    CompositionLocalProvider(LocalFormats provides rememberFormats()) {
        MaterialTheme(colorScheme = ApexColors, typography = ApexTypography, shapes = ApexShapes, content = content)
    }
}
