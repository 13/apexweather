package it.apexweather.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import it.apexweather.ui.map.BarKind
import it.apexweather.ui.map.RainRibbon
import it.apexweather.ui.map.RibbonBar
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * The ribbon in the three states of the brainstorm mockup. Look at each PNG after recording: a
 * golden nobody looked at proves nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class MapRibbonScreenshotTest {

    private val t0 = Instant.parse("2026-09-14T04:00:00Z")

    private fun bars(vararg spec: Pair<BarKind, Double>, unconfirmedFrom: Int = Int.MAX_VALUE, upper: Double? = null) =
        spec.mapIndexed { i, (kind, mm) -> RibbonBar(t0.plusSeconds(i * 900L), kind, mm, upper?.takeIf { kind == BarKind.OUTLOOK && mm > 0 }, i >= unconfirmedFrom && mm > 0) }

    private fun capture(name: String, bars: List<RibbonBar>, selected: Int, now: Int) = captureRoboImage("src/test/screenshots/$name.png") {
        Box(Modifier.background(Color(0xFF0B1020)).padding(16.dp).width(320.dp)) {
            RainRibbon(bars, selected, now, listOf(0 to "06", 4 to "07", 8 to "08", 12 to "09", 16 to "10"), "", onSelect = {})
        }
    }

    private val o = BarKind.OBSERVED
    private val n = BarKind.NOWCAST

    @Test
    fun `a forecast step selected`() = capture(
        "map_ribbon_forecast_selected",
        bars(o to 0.0, o to 0.0, o to 0.0, o to 0.5, o to 1.3, o to 0.0, o to 0.0, o to 0.0, o to 0.0,
            n to 0.0, n to 0.6, n to 1.4, n to 1.0, n to 0.35, n to 0.0, n to 0.0, n to 0.0, n to 0.0, n to 0.0, n to 0.0),
        selected = 11, now = 8,
    )

    @Test
    fun `this morning, unconfirmed by the radar`() = capture(
        "map_ribbon_unconfirmed",
        bars(o to 0.0, o to 0.0, o to 0.0, o to 0.0, o to 0.0, o to 0.0, o to 0.0, o to 0.0, o to 0.0,
            n to 0.96, n to 0.32, n to 0.12, n to 0.0, n to 0.0, n to 0.2, n to 0.0, n to 0.0, n to 0.0, n to 0.0, n to 0.0,
            unconfirmedFrom = 9),
        selected = 10, now = 8,
    )

    @Test
    fun `Heute, with the wetter end of the ensemble`() = capture(
        "map_ribbon_heute_ensemble",
        bars(*(0 until 24).map { h -> BarKind.OUTLOOK to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.3, 0.8, 2.0, 1.2, 0.2).getOrElse(h) { 0.0 } }.toTypedArray(), upper = 4.0),
        selected = 9, now = 0,
    )
}
