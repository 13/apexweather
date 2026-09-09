package it.apexweather.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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

    /** The sky gradients, which are chosen by the same conditions and are just as easy to break. */
    @Test
    fun `the sky palettes`() {
        captureRoboImage("src/test/screenshots/sky_palettes.png") {
            Column(Modifier.background(Color(0xFF101010)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SunPhase.entries.forEach { phase ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(phase.name, color = Color.White, fontSize = 9.sp, modifier = Modifier.width(70.dp))
                        listOf(Condition.CLEAR, Condition.CLOUDY, Condition.RAIN, Condition.SNOW, Condition.THUNDERSTORM).forEach { condition ->
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
