package it.apexweather.ui.compare

import it.apexweather.data.AppSettings
import it.apexweather.data.CompareVariable
import it.apexweather.data.WindUnit
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.point
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareStateBuilderTest {
    private val forecasts = mapOf(
        Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 72).map { point(it, 10.0, precip = 1.0, wind = 10.0) }),
        Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 72).map { point(it, 14.0, precip = 3.0, wind = 20.0) }),
        Source.ECMWF to forecast(Source.ECMWF, (0 until 168).map { point(it, 12.0) }),
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = forecasts, status = forecasts.keys.associateWith { SourceStatus.Ok(hour(0)) })
    private val consensus = ConsensusBlender().blend(forecasts)

    @Test
    fun `series limited to selected sources and 72h window from now`() {
        val settings = AppSettings(compareSources = setOf(Source.ICON_CH1, Source.ECMWF))
        val s = CompareStateBuilder.build(snapshot, settings, consensus, hour(2).plusSeconds(1))
        assertEquals(setOf(Source.ICON_CH1, Source.ECMWF), s.series.keys)
        assertEquals(hour(2), s.series.getValue(Source.ICON_CH1).first().time)
        assertTrue(s.series.getValue(Source.ECMWF).size <= 72)
        assertEquals(hour(2), s.consensusLine.first().time)
    }

    @Test
    fun `variable picks the right value`() {
        // KMOS reports no wind (the mapper stores 0.0), so the wind chart must leave it out.
        val withKmos = snapshot.copy(forecasts = forecasts + (Source.SIAG_KMOS to forecast(Source.SIAG_KMOS, (0 until 72).map { point(it, 13.0, precip = 2.0) })))
        val temp = CompareStateBuilder.build(withKmos, AppSettings(compareVariable = CompareVariable.TEMPERATURE), consensus, hour(0))
        val wind = CompareStateBuilder.build(withKmos, AppSettings(compareVariable = CompareVariable.WIND), consensus, hour(0))
        val precip = CompareStateBuilder.build(withKmos, AppSettings(compareVariable = CompareVariable.PRECIPITATION), consensus, hour(0))
        assertEquals(14.0, temp.series.getValue(Source.ICON_D2).first().value, 0.0)
        assertEquals(20.0, wind.series.getValue(Source.ICON_D2).first().value, 0.0)
        assertEquals(3.0, precip.series.getValue(Source.ICON_D2).first().value, 0.0)
        // KMOS has no wind: it must not draw a fabricated flat line, but it stays for temperature.
        assertTrue(Source.SIAG_KMOS in temp.series)
        assertFalse(Source.SIAG_KMOS in wind.series)
    }

    @Test
    fun `wind series and consensus line follow the wind unit setting`() {
        val settings = AppSettings(compareVariable = CompareVariable.WIND, windUnit = WindUnit.MS)
        val s = CompareStateBuilder.build(snapshot, settings, consensus, hour(0))
        assertEquals(20.0 / 3.6, s.series.getValue(Source.ICON_D2).first().value, 1e-9)
        assertEquals(15.0 / 3.6, s.consensusLine.first().value, 1e-9)
    }

    @Test
    fun `day table has a row per consensus day and a cell per source`() {
        val s = CompareStateBuilder.build(snapshot, AppSettings(), consensus, hour(0))
        assertEquals(consensus.daily.size, s.dayRows.size)
        val row0 = s.dayRows.first()
        assertTrue(row0.cells.containsKey(Source.ICON_D2))
        assertEquals(14.0, row0.cells.getValue(Source.ICON_D2).maxC, 0.0)
        assertEquals(3, s.statuses.size)
    }
}
