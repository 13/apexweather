package it.apexweather.widget

import it.apexweather.R
import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.point
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.home.HomeStateBuilder
import it.apexweather.ui.home.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WidgetStateTest {
    private val formats = Formats(Locale.GERMAN, use24Hour = true)

    @Test
    fun `builds temperature, six upcoming hours and icons`() {
        val f = mapOf(Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 24).map { point(it, 20.0 + it, condition = if (it < 3) Condition.RAIN else Condition.CLEAR) }))
        val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = f, lastSuccessfulRefresh = hour(0))
        val home = HomeStateBuilder.build(snapshot, AppSettings(), ConsensusBlender().blend(f), hour(0).plusSeconds(30))
        val w = WidgetStateBuilder.build(home, SouthTyrol.ZONE, formats)
        assertTrue(w.hasData)
        assertEquals("20°", w.tempText)
        assertEquals(R.drawable.ic_wx_rain, w.iconRes)
        assertEquals(6, w.hours.size)
        assertEquals("03", w.hours[0].label) // hour(1) = 03:00 local
        assertEquals(R.drawable.ic_wx_moon, w.hours[2].iconRes) // hour(3) = 05:00 local, clear → night icon
        assertEquals(R.drawable.ic_wx_sun, w.hours[5].iconRes) // hour(6) = 08:00 local, clear → day icon
        assertEquals(Format.time(hour(0), SouthTyrol.ZONE, formats), w.updatedText)
        assertFalse(w.isStale)
    }

    /**
     * The small widget drops the condition line to make room for the age, so it must know when the
     * data has gone old. Without this the small widget showed an arbitrarily old reading with
     * nothing to say so.
     */
    @Test
    fun `data older than three hours is marked stale`() {
        val f = mapOf(Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 24).map { point(it, 20.0 + it) }))
        val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = f, lastSuccessfulRefresh = hour(0))
        fun stale(hoursLater: Long) =
            WidgetStateBuilder.build(
                HomeStateBuilder.build(snapshot, AppSettings(), ConsensusBlender().blend(f), hour(0).plusSeconds(hoursLater * 3600)),
                SouthTyrol.ZONE, formats,
            ).isStale
        assertFalse(stale(2))
        assertTrue(stale(4))
    }

    @Test
    fun `empty home gives no-data widget`() {
        val w = WidgetStateBuilder.build(HomeUiState(loading = false, isEmpty = true), SouthTyrol.ZONE, formats)
        assertFalse(w.hasData)
        assertEquals("–", w.tempText)
        assertEquals(R.string.empty_title, w.conditionRes)
        assertEquals(R.drawable.ic_wx_cloud, w.iconRes)
        // Never refreshed at all is the oldest case there is, not the freshest.
        assertTrue(w.isStale)
    }

    /**
     * Before the first refresh there is no age to print. The small widget must fall back to the
     * condition line rather than leaving it blank, which is what it did when the age unconditionally
     * displaced it.
     */
    @Test
    fun `with nothing cached there is no age to show`() {
        val w = WidgetStateBuilder.build(HomeUiState(loading = false, isEmpty = true), SouthTyrol.ZONE, formats)
        assertTrue(w.isStale)
        assertEquals("", w.updatedText)
    }

    /**
     * The widget has two lines and a warning outranks both, so the state has to carry the worst one
     * as resource ids — the widget renders outside a composition and cannot resolve strings itself.
     */
    @Test
    fun `the worst warning in force reaches the widget`() {
        fun warning(id: String, level: it.apexweather.domain.model.WarningLevel) = it.apexweather.domain.model.Warning(
            identifier = id, type = it.apexweather.domain.model.WarningType.THUNDERSTORM, level = level,
            areaDesc = "Trentino Alto Adige", onset = hour(0), expires = hour(6), headline = "Warning",
        )
        // The repository hands them over worst first; the widget takes the head of that list.
        val home = HomeUiState(
            loading = false, isEmpty = false, heroTempC = 18.0,
            warnings = listOf(warning("a", it.apexweather.domain.model.WarningLevel.RED), warning("b", it.apexweather.domain.model.WarningLevel.YELLOW)),
        )
        val w = WidgetStateBuilder.build(home, SouthTyrol.ZONE, formats)
        assertEquals(R.string.warn_thunderstorm, w.warningTypeRes)
        assertEquals(R.string.warn_level_red, w.warningLevelRes)
        assertEquals(0xFFFF5A4EL, w.warningColor)
    }

    @Test
    fun `with nothing in force the widget is told nothing`() {
        val w = WidgetStateBuilder.build(HomeUiState(loading = false, isEmpty = false, heroTempC = 18.0), SouthTyrol.ZONE, formats)
        assertEquals(null, w.warningTypeRes)
        assertEquals(null, w.warningLevelRes)
    }
}
