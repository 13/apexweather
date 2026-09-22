package it.apexweather.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import it.apexweather.domain.ParticleKind
import it.apexweather.domain.ROME
import it.apexweather.domain.SkyPalette
import it.apexweather.domain.SunPhase
import it.apexweather.domain.hour
import it.apexweather.domain.model.Condition
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.share.ShareCard
import it.apexweather.ui.share.ShareCardState
import it.apexweather.ui.share.ShareDay
import it.apexweather.ui.share.ShareRange
import it.apexweather.ui.share.ShareHour
import it.apexweather.ui.theme.ApexTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.Locale

/**
 * What the shared picture actually paints.
 *
 * Every mapping around this card is covered by a unit test, and none of them can see a card whose
 * bars are the wrong height, whose eight columns no longer fit, or whose text has lost its contrast
 * against the sky behind it. The same argument the weather-icon goldens rest on: an edited path
 * keeps its resource id and every mapping test goes on passing.
 *
 * **Look at each PNG after recording.** A golden recorded without being looked at pins a bug.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w420dp-h900dp-xhdpi")
class ShareCardScreenshotTest {

    private val day = SkyPalette(0xFF2A6FD0, 0xFF5AA0E8, 0xFF9CC9F0, 0xFFFFD27F, 0xFF20304A, ParticleKind.NONE, 0f)
    private val wet = SkyPalette(0xFF2B3A4A, 0xFF44576B, 0xFF66809A, 0xFFB8D4FF, 0xFF1A2430, ParticleKind.RAIN, 0.6f)
    private val night = SkyPalette(0xFF060B1A, 0xFF0D1730, 0xFF17264A, 0xFF9FB6FF, 0xFF05080F, ParticleKind.STARS, 0.4f)

    private fun hours(
        from: Int = 12,
        temps: List<Double> = listOf(17.0, 18.0, 19.0, 19.0, 18.0, 17.0, 16.0, 15.0),
        precip: List<Double> = listOf(0.0, 0.0, 0.0, 0.3, 0.9, 2.6, 5.4, 1.8),
        condition: Condition = Condition.RAIN,
        phase: SunPhase = SunPhase.DAY,
        snow: Boolean = false,
        snowCm: List<Double> = listOf(0.0, 0.0, 0.0, 0.5, 1.5, 4.0, 8.0, 3.0),
    ): List<ShareHour> = temps.indices.map { i ->
        ShareHour(
            time = at(from + i),
            tempC = temps[i],
            precipMm = precip[i],
            snowCm = if (snow) snowCm[i].takeIf { it > 0 } else null,
            condition = if (precip[i] > 0) condition else Condition.PARTLY_CLOUDY,
            phase = phase,
        )
    }

    /** Hours from local midnight, so a night strip may run past 23 into the small hours. */
    private fun at(hoursFromMidnight: Int): Instant =
        hour(0).atZone(ROME).toLocalDate().atStartOfDay(ROME).plusHours(hoursFromMidnight.toLong()).toInstant()

    private fun card(
        palette: SkyPalette = day,
        condition: Condition = Condition.PARTLY_CLOUDY,
        phase: SunPhase = SunPhase.DAY,
        temp: Double? = 18.0,
        start: Instant? = at(19),
        prob: Int? = 40,
        adjustment: Double? = -1.9,
        min: Double = 11.0,
        max: Double = 22.0,
        hours: List<ShareHour> = hours(),
    ) = ShareCardState(
        placeName = "Dorf Tirol",
        date = at(12).atZone(ROME).toLocalDate(),
        tempC = temp,
        condition = condition,
        phase = phase,
        minC = min,
        maxC = max,
        precipStart = start,
        precipProb = prob,
        adjustmentC = adjustment,
        sourceCount = 13,
        palette = palette,
        hours = hours,
    )

    private fun capture(name: String, state: ShareCardState, locale: Locale = Locale.GERMANY) {
        captureRoboImage("src/test/screenshots/$name.png") {
            ApexTheme {
                CompositionLocalProvider(LocalFormats provides Formats(locale, true)) {
                    Box(Modifier.background(Color(0xFF0B1220)).padding(16.dp)) {
                        ShareCard(state)
                    }
                }
            }
        }
    }

    @Test
    fun `a fair afternoon with rain coming`() = capture("share_card_sunny", card())

    @Test
    fun `a wet day`() = capture(
        "share_card_rain",
        card(palette = wet, condition = Condition.RAIN, start = null, prob = 85),
    )

    /** Centimetres, not the millimetres of water it melts to — `Format.showsSnow` decides. */
    @Test
    fun `a snowy day is drawn in centimetres`() = capture(
        "share_card_snow",
        card(
            palette = wet, condition = Condition.SNOW, temp = -3.0, min = -6.0, max = -1.0,
            start = null, prob = 90,
            hours = hours(
                temps = listOf(-2.0, -2.0, -3.0, -3.0, -4.0, -4.0, -5.0, -6.0),
                condition = Condition.SNOW, snow = true,
            ),
        ),
    )

    /** No station: no adjustment line, and the card is a line shorter rather than a line emptier. */
    @Test
    fun `a place with no station claims no measurement`() = capture(
        "share_card_no_station",
        card(adjustment = null),
    )

    @Test
    fun `a clear night`() = capture(
        "share_card_night",
        card(
            palette = night, condition = Condition.CLEAR, phase = SunPhase.NIGHT, temp = 7.0,
            start = null, prob = 0, adjustment = null,
            hours = hours(from = 16, precip = List(8) { 0.0 }, phase = SunPhase.NIGHT),
        ),
    )

    private fun days(count: Int, thinFrom: Int? = null, ensembleBacked: Boolean = false): List<ShareDay> =
        (0 until count).map { i ->
            val thin = thinFrom != null && i >= thinFrom
            ShareDay(
                date = at(12).atZone(ROME).toLocalDate().plusDays(i.toLong()),
                condition = listOf(Condition.CLEAR, Condition.PARTLY_CLOUDY, Condition.RAIN, Condition.CLOUDY)[i % 4],
                minC = 8.0 + i % 4,
                maxC = 19.0 + i % 5,
                precipMm = if (i % 3 == 2) 2.4 else 0.0,
                snowCm = null,
                precipProb = if (i % 3 == 2) 70 else 10 * i % 40,
                agreement = if (thin) 0.4f else 0.9f - 0.08f * i,
                sourceCount = if (thin) 1 else 13 - i,
                ensembleBacked = thin && ensembleBacked,
            )
        }

    private fun dayCard(range: ShareRange, rows: List<ShareDay>) =
        card(hours = emptyList()).copy(range = range, days = rows)

    @Test
    fun `three days`() = capture("share_card_3d", dayCard(ShareRange.THREE_DAYS, days(3)))

    @Test
    fun `seven days`() = capture("share_card_7d", dayCard(ShareRange.SEVEN_DAYS, days(7)))

    /**
     * The tail that has to be accounted for: a grey dot needs its sentence, and this is the only
     * place that pairing is pinned. Built deliberately, because at seven days the real forecast
     * still has several models — and the card must not depend on that staying true.
     */
    @Test
    fun `seven days with a thinning consensus`() = capture(
        "share_card_7d_thin",
        dayCard(ShareRange.SEVEN_DAYS, days(7, thinFrom = 5)),
    )

}
