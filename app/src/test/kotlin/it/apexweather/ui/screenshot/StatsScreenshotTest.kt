package it.apexweather.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import it.apexweather.data.WindUnit
import it.apexweather.domain.Contender
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Predicted
import it.apexweather.domain.Quantity
import it.apexweather.domain.VerificationHour
import it.apexweather.domain.model.Source
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.stats.StatsCard
import it.apexweather.ui.stats.StatsContent
import it.apexweather.ui.stats.StatsDetailSheet
import it.apexweather.ui.stats.StatsPeriod
import it.apexweather.ui.stats.StatsStateBuilder
import it.apexweather.ui.stats.StatsUiState
import it.apexweather.ui.theme.ApexTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/** The statistics screen over thirty generated days. Look at every PNG after recording. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w400dp-h1400dp-xhdpi")
class StatsScreenshotTest {
    private val now: Instant = Instant.parse("2026-09-15T12:00:00Z")

    /** Fixed offsets, plus a per-model wobble so the hit rates are not all 100 %. */
    private val offsets = linkedMapOf(
        Source.ICON_CH1 to 0.3, Source.ICON_D2 to -0.6, Source.GEOSPHERE_AROME to 0.9,
        Source.KNMI_HARMONIE to 1.4, Source.GFS to -2.1,
    )

    /**
     * 720 hours ending at [now]. The observation is a daily sine with a slower drift on top, so
     * "same as yesterday" is not a perfect forecast. Rain falls every 13th hour of the first hundred;
     * every model follows it except GFS, which is never wet. GEM reports only the last ten hours.
     */
    private val hours: List<VerificationHour> = (0 until 720).map { i ->
        val t = now.minusSeconds((719 - i) * 3600L)
        val obs = 15.0 + 6.0 * sin(2 * PI * i / 24) + 1.5 * sin(2 * PI * i / 89)
        val rain = if (i <= 100 && i % 13 == 0) 0.6 else 0.0
        val predicted = buildMap {
            offsets.entries.forEachIndexed { idx, (source, offset) ->
                val wobble = 0.9 * sin(i * (0.7 + 0.13 * idx))
                val modelRain = when {
                    source == Source.GFS -> 0.0
                    source == Source.ICON_D2 && i == 13 -> 0.0
                    source == Source.GEOSPHERE_AROME && i == 14 -> 0.4
                    else -> rain
                }
                put(source, Predicted(obs + offset + wobble, modelRain, 8.0 + offset * 2 + wobble))
            }
            if (i >= 710) put(Source.GEM, Predicted(obs + 0.5, 0.0, 9.0))
        }
        VerificationHour(t, obs, 8.0, rain, mapOf(LeadBucket.SIX to predicted))
    }

    private fun state(quantity: Quantity): StatsUiState =
        StatsStateBuilder.build(hours, DORF_TIROL, quantity, StatsPeriod.MONTH, LeadBucket.SIX, now, null, WindUnit.KMH)

    private fun capture(path: String, content: @Composable () -> Unit) {
        captureRoboImage(path) {
            ApexTheme {
                CompositionLocalProvider(LocalFormats provides Formats(Locale.GERMANY, true)) {
                    Box(Modifier.background(Color(0xFF14213A)).width(400.dp)) { content() }
                }
            }
        }
    }

    @Composable
    private fun Screen(state: StatsUiState) =
        StatsContent(state, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun `temperature, 30 days, 6 h`() = capture("src/test/screenshots/stats_temperature.png") { Screen(state(Quantity.TEMPERATURE)) }

    @Test
    @Config(qualifiers = "de-w400dp-h2000dp-xhdpi", fontScale = 2.0f)
    fun `temperature, font scale 2`() = capture("src/test/screenshots/stats_temperature_font2.png") { Screen(state(Quantity.TEMPERATURE)) }

    @Test
    fun rain() = capture("src/test/screenshots/stats_rain.png") { Screen(state(Quantity.RAIN)) }

    @Test
    fun card() = capture("src/test/screenshots/stats_card.png") {
        StatsCard(StatsStateBuilder.card(hours, DORF_TIROL, now), WindUnit.KMH, onOpen = {})
    }

    /** The sheet's content without its window: the parts of the day and the daily chart. */
    @Test
    fun detail() = capture("src/test/screenshots/stats_detail.png") {
        val s = StatsStateBuilder.build(hours, DORF_TIROL, Quantity.TEMPERATURE, StatsPeriod.MONTH, LeadBucket.SIX, now, Source.ICON_D2)
        val rank = s.rows.firstOrNull { it.contender == Contender.Model(Source.ICON_D2) }?.rank
        StatsDetailSheet(s.detail!!, rank = rank, windUnit = WindUnit.KMH, onClose = {}, onOpenSource = {})
    }
}
