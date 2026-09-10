package it.apexweather.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.home.PrecipScale
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import it.apexweather.domain.SkyPaletteSelector
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Condition
import it.apexweather.ui.common.iconRes
import it.apexweather.ui.theme.fromArgb
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden images of the two things in this app that are drawn rather than written.
 *
 * `WeatherIconsTest` already asserts that every condition maps to its own drawable, but a mapping
 * test cannot see the drawing: a path edited by one control point, or a vector that lost its fill,
 * keeps the same resource id and the same test passes. These capture what is actually painted, so
 * an accidental edit to a vector or a palette shows up as an image diff.
 *
 * Record after a deliberate change with `./gradlew :app:recordRoborazziDebug`, and look at the
 * result before committing it — a golden nobody looked at proves nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class WeatherVisualsScreenshotTest {

    /** Every condition in both phases: twelve drawings, and the day/night pairs that differ. */
    @Test
    fun `the weather icon set`() {
        captureRoboImage("src/test/screenshots/weather_icons.png") {
            Column(
                Modifier.background(Color(0xFF14213A)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Condition.entries.forEach { condition ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(condition.name, color = Color.White, fontSize = 10.sp, modifier = Modifier.width(120.dp))
                        SunPhase.entries.forEach { phase ->
                            Icon(
                                painterResource(condition.iconRes(phase)),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The bar row across the scale: nothing, a trace, a millimetre, the two step colours, the top of
     * the scale and past it, and the two frozen conditions that must not be painted as rain.
     */
    @Test
    fun `the precipitation bars`() {
        val cases = listOf(
            Triple(0.0, 0, Condition.CLEAR),
            Triple(0.0, 20, Condition.CLOUDY),
            Triple(0.2, 40, Condition.DRIZZLE),
            Triple(1.0, 64, Condition.RAIN),
            Triple(2.5, 80, Condition.RAIN),
            Triple(6.2, 90, Condition.HEAVY_RAIN),
            Triple(14.0, 95, Condition.THUNDERSTORM),
            Triple(2.0, 80, Condition.SNOW),
            Triple(0.8, 55, Condition.SLEET),
        )
        captureRoboImage("src/test/screenshots/precip_bars.png") {
            CompositionLocalProvider(LocalFormats provides Formats(java.util.Locale.GERMANY, true)) {
                Row(
                    Modifier.background(Color(0xFF14213A)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    cases.forEach { (mm, prob, condition) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$prob%", color = Color.White, fontSize = 9.sp)
                            PrecipBarPreview(mm, prob, condition)
                        }
                    }
                }
            }
        }
    }

    /**
     * `PrecipBar` is private to HomeSections, and it should stay that way — this repeats the six
     * lines of drawing rather than widening its visibility for a test. `PrecipScale` is the part
     * that has to agree, and it does, because both sides read it.
     */
    @Composable
    private fun PrecipBarPreview(mm: Double, prob: Int, condition: Condition) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.width(18.dp).height(30.dp).clip(RoundedCornerShape(4.dp)).background(PrecipScale.TRACK),
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (PrecipScale.hasAmount(mm)) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height((30.dp * PrecipScale.fillFraction(mm)).coerceAtLeast(3.dp))
                            .clip(RoundedCornerShape(4.dp))
                            .background(PrecipScale.fillColor(mm, condition)),
                    )
                }
            }
            Text(
                if (PrecipScale.hasAmount(mm)) Format.mm(mm, LocalFormats.current) else "",
                color = Color.White, fontSize = 10.sp,
            )
            Text(if (prob > 0) "$prob %" else "", color = Color(0xFFB9D2F5), fontSize = 10.sp)
        }
    }

    /**
     * The sky gradients, which are chosen by the same conditions and are just as easy to break.
     *
     * Fog is here because nothing else covers it. Its palette and its drifting bands are built and
     * are almost never reached in this valley — no model voted fog on 2026-09-10 while it was foggy
     * outside — so a golden is the only thing that would notice them rotting.
     */
    @Test
    fun `the sky palettes`() {
        captureRoboImage("src/test/screenshots/sky_palettes.png") {
            Column(Modifier.background(Color(0xFF101010)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SunPhase.entries.forEach { phase ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(phase.name, color = Color.White, fontSize = 9.sp, modifier = Modifier.width(70.dp))
                        listOf(Condition.CLEAR, Condition.CLOUDY, Condition.FOG, Condition.RAIN, Condition.SNOW, Condition.THUNDERSTORM).forEach { condition ->
                            Swatch(condition, phase)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Swatch(condition: Condition, phase: SunPhase) {
        val palette = SkyPaletteSelector.select(condition, phase, if (condition.isPrecipitation) 2.0 else 0.0)
        Box(
            Modifier.size(48.dp)
                .background(Brush.verticalGradient(listOf(Color.fromArgb(palette.top), Color.fromArgb(palette.bottom)))),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(10.dp).background(Color.fromArgb(palette.accent)))
        }
    }
}
