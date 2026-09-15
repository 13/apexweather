package it.apexweather.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import it.apexweather.data.remote.SourceMeta
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.DayPart
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.ModelBias
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.domain.point
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.compare.SourceDetailSheet
import it.apexweather.ui.compare.SourceDetailStateBuilder
import it.apexweather.ui.compare.SourceMetaUi
import it.apexweather.ui.theme.ApexTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.Locale

/** The source sheet as the reader first sees it. Look at the PNG after recording. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w400dp-h1400dp-xhdpi")
class SourceDetailScreenshotTest {

    @Test
    fun `ICON-CH1 at Dorf Tirol`() {
        val icons = listOf(Source.ICON_CH1, Source.ICON_CH2, Source.ICON_2I, Source.ICON_D2, Source.GEOSPHERE_AROME)
        val snapshot = WeatherSnapshot.EMPTY.copy(
            forecasts = icons.associateWith { s -> forecast(s, (0 until 34).map { point(it, 12.0) }) },
            status = icons.associateWith { SourceStatus.Ok(hour(1)) },
            modelBias = ModelBias(mapOf(Source.ICON_CH1 to mapOf(
                DayPart.MORNING to mapOf(LeadBucket.SIX to 0.2),
                DayPart.AFTERNOON to mapOf(LeadBucket.SIX to 1.4),
                DayPart.EVENING to mapOf(LeadBucket.SIX to -0.04),
            ))),
        )
        val state = SourceDetailStateBuilder.build(Source.ICON_CH1, snapshot, DORF_TIROL, hour(3))
        val meta = SourceMetaUi.Loaded(SourceMeta(hour(0), hour(1), Duration.ofHours(3)))
        captureRoboImage("src/test/screenshots/source_detail_icon_ch1.png") {
            ApexTheme {
                CompositionLocalProvider(LocalFormats provides Formats(Locale.GERMANY, true)) {
                    Box(Modifier.background(Color(0xFF14213A)).width(400.dp)) {
                        SourceDetailSheet(state, meta, onClose = {})
                    }
                }
            }
        }
    }
}
