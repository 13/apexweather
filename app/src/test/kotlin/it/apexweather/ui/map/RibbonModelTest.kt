package it.apexweather.ui.map

import it.apexweather.data.remote.NowcastCell
import it.apexweather.data.remote.NowcastKind
import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.RadarReading
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Locale

class RibbonModelTest {

    private val t0 = Instant.parse("2026-09-14T06:00:00Z")
    private val place = DORF_TIROL

    private fun state(): MapUiState {
        val radar = listOf(RadarFrame(t0.minusSeconds(600), "x"), RadarFrame(t0, "y"))
        val here = NowcastCell(place.lat + 0.002, place.lon, 0.96)
        val away = NowcastCell(place.lat + 0.1, place.lon, 9.0)
        val steps = listOf(
            NowcastStep(t0.plusSeconds(900), listOf(here, away)),
            NowcastStep(t0.plusSeconds(1800), listOf(away)),
            NowcastStep(t0.plusSeconds(2700), listOf(here.copy(mmPerHour = 0.15))),
            NowcastStep(t0.plusSeconds(3600), listOf(here.copy(mmPerHour = 30.0))),
        )
        val check = PlaceCheck(place.lat, place.lon, mapOf(t0.minusSeconds(600) to RadarReading(30), t0 to RadarReading(8)))
        return MapUiState(frames = MapUiState.timeline(radar, steps, check), check = check, place = place, loading = false)
    }

    @Test
    fun `a bar per visible frame, with the place's own value`() {
        val bars = RibbonModel.bars(state())
        assertEquals(listOf(BarKind.OBSERVED, BarKind.OBSERVED, BarKind.NOWCAST, BarKind.NOWCAST, BarKind.NOWCAST, BarKind.NOWCAST), bars.map { it.kind })
        assertEquals(2.73, bars[0].mmPerHour, 0.01) // 30 dBZ
        assertEquals(0.0, bars[1].mmPerHour, 0.0) // 8 dBZ is not rain
        assertEquals(0.96, bars[2].mmPerHour, 0.0)
        assertEquals("rain five kilometres away is not rain here", 0.0, bars[3].mmPerHour, 0.0)
    }

    @Test
    fun `words follow the radar's boundaries`() {
        val bars = RibbonModel.bars(state())
        assertEquals(RainWord.MODERATE, RibbonModel.word(bars[0]))
        assertEquals(RainWord.DRY, RibbonModel.word(bars[1]))
        // The newest frame is dry at the place, so the first hour is only possible.
        assertEquals(RainWord.POSSIBLE, RibbonModel.word(bars[2]))
        assertEquals(RainWord.DRY, RibbonModel.word(bars[3]))
        assertEquals(RainWord.POSSIBLE, RibbonModel.word(bars[4]))
    }

    @Test
    fun `word boundaries`() {
        fun w(mm: Double, upper: Double? = null, unconfirmed: Boolean = false) =
            RibbonModel.word(RibbonBar(t0, BarKind.OUTLOOK, mm, upper, unconfirmed))
        assertEquals(RainWord.DRY, w(0.0))
        assertEquals(RainWord.POSSIBLE, w(0.1))
        assertEquals(RainWord.POSSIBLE, w(0.0, upper = 0.5))
        assertEquals(RainWord.LIGHT, w(0.33))
        assertEquals(RainWord.MODERATE, w(2.4))
        assertEquals(RainWord.HEAVY, w(24.0))
        assertEquals(RainWord.POSSIBLE, w(24.0, unconfirmed = true))
    }

    @Test
    fun `Heute bars come from the outlook`() {
        val outlook = listOf(MapFrame.Forecast(NowcastStep(t0, listOf(NowcastCell(place.lat, place.lon, 1.0, 3.0)), NowcastKind.OUTLOOK)))
        val bars = RibbonModel.bars(state().copy(outlook = outlook).withZoom(MapZoom.TODAY))
        assertEquals(1, bars.size)
        assertEquals(BarKind.OUTLOOK, bars[0].kind)
        assertEquals(3.0, bars[0].upperMmPerHour!!, 0.0)
    }

    /** Local hours: t0 is 06:00Z, 08:00 in the province in September. */
    @Test
    fun `labels fall on whole local hours, every hour in Jetzt and at 00, 06, 12, 18 in Heute`() {
        val quarter = (0 until 20).map { RibbonBar(t0.plusSeconds(it * 900L), BarKind.NOWCAST, 0.0, null, false) }
        assertEquals(listOf(0, 4, 8, 12, 16), RibbonModel.labelIndices(quarter, MapZoom.NOW))
        val hourly = (0 until 24).map { RibbonBar(t0.plusSeconds(it * 3600L), BarKind.OUTLOOK, 0.0, null, false) }
        assertEquals(listOf(4, 10, 16, 22), RibbonModel.labelIndices(hourly, MapZoom.TODAY))
    }

    @Test
    fun `short durations`() {
        val f = Formats(Locale.GERMAN, use24Hour = true)
        assertEquals("35 min", Format.shortDuration(Duration.ofMinutes(35), f))
        assertEquals("9 h", Format.shortDuration(Duration.ofMinutes(9 * 60 + 10), f))
        assertEquals("20 min", Format.shortDuration(Duration.ofMinutes(-20), f))
    }
}
