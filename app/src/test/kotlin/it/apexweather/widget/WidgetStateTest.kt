package it.apexweather.widget

import it.apexweather.R
import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.point
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.home.HomeStateBuilder
import it.apexweather.ui.home.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {
    @Test
    fun `builds temperature, six upcoming hours and icons`() {
        val f = mapOf(Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 24).map { point(it, 20.0 + it, condition = if (it < 3) Condition.RAIN else Condition.CLEAR) }))
        val home = HomeStateBuilder.build(WeatherSnapshot.EMPTY.copy(forecasts = f), AppSettings(), ConsensusBlender().blend(f), hour(0).plusSeconds(30))
        val w = WidgetStateBuilder.build(home, DorfTirol.ZONE)
        assertTrue(w.hasData)
        assertEquals("20°", w.tempText)
        assertEquals(R.drawable.ic_wx_rain, w.iconRes)
        assertEquals(6, w.hours.size)
        assertEquals("03", w.hours[0].label) // hour(1) = 03:00 local
        assertEquals(R.drawable.ic_wx_moon, w.hours[2].iconRes) // hour(3) = 05:00 local, clear → night icon
        assertEquals(R.drawable.ic_wx_sun, w.hours[5].iconRes) // hour(6) = 08:00 local, clear → day icon
    }

    @Test
    fun `empty home gives no-data widget`() {
        val w = WidgetStateBuilder.build(HomeUiState(loading = false, isEmpty = true), DorfTirol.ZONE)
        assertFalse(w.hasData)
        assertEquals("–", w.tempText)
    }
}
