package it.apexweather.ui.home

import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.ParticleKind
import it.apexweather.domain.ROME
import it.apexweather.domain.SunPhase
import it.apexweather.domain.T0
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.point
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.StationObservation
import it.apexweather.domain.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class HomeStateBuilderTest {
    private val blender = ConsensusBlender()
    private val forecasts = mapOf(
        Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 48).map { point(it, 10.0 + it % 10, condition = Condition.RAIN) }),
        Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 48).map { point(it, 12.0 + it % 10, condition = Condition.RAIN) }),
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = forecasts)
    private val consensus = blender.blend(forecasts)

    @Test
    fun `hero uses consensus hour when no fresh observation`() {
        val s = HomeStateBuilder.build(snapshot, AppSettings(), consensus, now = hour(3).plusSeconds(600))
        assertEquals(consensus.hourly[3].tempC, s.heroTempC!!, 0.0)
        assertEquals(Condition.RAIN, s.heroCondition)
        assertEquals(ParticleKind.RAIN, s.palette.particle)
        assertEquals(1.0, s.bandHalfWidth!!, 0.0)
        assertFalse(s.isEmpty)
    }

    @Test
    fun `fresh observation overrides hero temperature but not condition`() {
        val obs = StationObservation("Meran", hour(3), tempC = 25.5, humidityPct = 40, windKmh = 5.0, windDir = "W", gustKmh = null, precipMm = 0.0, pressureHpa = 1010.0)
        val s = HomeStateBuilder.build(snapshot.copy(observation = obs), AppSettings(), consensus, now = hour(3).plusSeconds(600))
        assertEquals(25.5, s.heroTempC!!, 0.0)
        assertEquals(Condition.RAIN, s.heroCondition)
        assertTrue(s.observation != null)
    }

    @Test
    fun `stale observation is ignored`() {
        val obs = StationObservation("Meran", hour(0), tempC = 25.5, null, null, null, null, null, null)
        val s = HomeStateBuilder.build(snapshot.copy(observation = obs), AppSettings(), consensus, now = hour(0).plus(Duration.ofMinutes(91)))
        assertNull(s.observation)
        assertEquals(consensus.hourly[1].tempC, s.heroTempC!!, 0.0)
    }

    @Test
    fun `upcoming hours start at the current hour and cap at 48`() {
        val s = HomeStateBuilder.build(snapshot, AppSettings(), consensus, now = hour(5).plusSeconds(1))
        assertEquals(hour(5), s.upcomingHours.first().time)
        assertTrue(s.upcomingHours.size <= 48)
    }

    @Test
    fun `phase at an hour tomorrow uses tomorrow sunrise and sunset`() {
        val today = T0.atZone(ROME).toLocalDate()
        val tomorrow = today.plusDays(1)
        fun daily(d: LocalDate) = DailyPoint(
            date = d, minC = 8.0, maxC = 20.0, precipMm = 0.0, condition = Condition.CLEAR,
            sunrise = d.atTime(6, 0).atZone(ROME).toInstant(),
            sunset = d.atTime(20, 0).atZone(ROME).toInstant(),
        )
        val withSun = forecasts.mapValues { (_, f) -> f.copy(daily = listOf(daily(today), daily(tomorrow))) }
        val s = HomeStateBuilder.build(
            WeatherSnapshot.EMPTY.copy(forecasts = withSun), AppSettings(), blender.blend(withSun), now = hour(3),
        )
        assertEquals(SunPhase.DAY, s.phaseAt(tomorrow.atTime(12, 0).atZone(ROME).toInstant()))
        assertEquals(SunPhase.NIGHT, s.phaseAt(tomorrow.atTime(3, 0).atZone(ROME).toInstant()))
    }

    @Test
    fun `empty snapshot gives empty state with default palette`() {
        val s = HomeStateBuilder.build(WeatherSnapshot.EMPTY, AppSettings(), consensus = ConsensusForecast.EMPTY, now = hour(0))
        assertTrue(s.isEmpty)
        assertNull(s.heroTempC)
    }
}
