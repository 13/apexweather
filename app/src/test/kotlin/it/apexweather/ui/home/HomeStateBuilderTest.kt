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

    private fun observation(temp: Double) = StationObservation(
        "Meran", hour(3), tempC = temp, humidityPct = 40, windKmh = 5.0, windDir = "W",
        gustKmh = null, precipMm = 0.0, pressureHpa = 1010.0,
    )

    /** The models' view from down at the station, warmer than the village by [warmerBy]. */
    private fun reference(warmerBy: Double, fetchedAt: java.time.Instant = hour(3)) =
        it.apexweather.data.remote.StationReference(
            fetchedAt = fetchedAt,
            elevationM = 330.0,
            tempByEpochSecond = consensus.hourly.associate { it.time.epochSecond to it.tempC + warmerBy },
        )

    /**
     * The station sits 270 m below the village, so its reading is quoted only after the models'
     * own difference between the two points has been applied to it.
     */
    @Test
    fun `a fresh observation is carried up to the village before it becomes the hero`() {
        val s = HomeStateBuilder.build(
            snapshot.copy(observation = observation(25.5), stationReference = reference(warmerBy = 2.0)),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertEquals(23.5, s.heroTempC!!, 1e-9)
        assertEquals(-2.0, s.heroAdjustmentC!!, 1e-9)
        assertEquals(Condition.RAIN, s.heroCondition)
        assertTrue(s.observation != null)
    }

    /**
     * With nothing to carry it up with, the raw station reading is 270 m too low to stand for the
     * village, while the consensus is already at the village's height. The forecast wins.
     */
    @Test
    fun `without a reference the consensus is preferred to an uncorrected station reading`() {
        val s = HomeStateBuilder.build(
            snapshot.copy(observation = observation(25.5)),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertEquals(consensus.hourly[3].tempC, s.heroTempC!!, 0.0)
        assertNull(s.heroAdjustmentC)
        // The card still shows what was measured; only the hero declines to quote it.
        assertNull(s.observation)
        assertTrue(s.station != null)
    }

    /** A reference from days ago describes air that has since moved on. */
    @Test
    fun `a stale reference is not used to correct anything`() {
        val s = HomeStateBuilder.build(
            snapshot.copy(observation = observation(25.5), stationReference = reference(2.0, fetchedAt = hour(3).minusSeconds(48 * 3600))),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertNull(s.heroAdjustmentC)
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

    /**
     * The 48-hour strip is a window, not the whole forecast: a day late in the week still needs its
     * hours for the day sheet, and each model still needs its own view of that day.
     */
    @Test
    fun `a day beyond the 48 hour strip still has its hours and its per-source values`() {
        val week = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 168).map { point(it, 10.0 + it % 10) }),
            Source.ECMWF to forecast(Source.ECMWF, (0 until 168).map { point(it, 12.0 + it % 10) }),
        )
        val s = HomeStateBuilder.build(WeatherSnapshot.EMPTY.copy(forecasts = week), AppSettings(), blender.blend(week), now = hour(0))

        val dayFive = hour(24 * 5).atZone(ROME).toLocalDate()
        assertTrue(s.upcomingHours.none { it.time.atZone(ROME).toLocalDate() == dayFive })
        assertTrue(s.hoursByDate.getValue(dayFive).isNotEmpty())
        assertEquals(setOf(Source.ICON_CH1, Source.ECMWF), s.sourcesForDay(dayFive).keys)
    }

    @Test
    fun `a day no model reaches has no per-source rows`() {
        val s = HomeStateBuilder.build(snapshot, AppSettings(), consensus, now = hour(0))
        assertTrue(s.sourcesForDay(hour(0).atZone(ROME).toLocalDate().plusDays(30)).isEmpty())
    }

    @Test
    fun `empty snapshot gives empty state with default palette`() {
        val s = HomeStateBuilder.build(WeatherSnapshot.EMPTY, AppSettings(), consensus = ConsensusForecast.EMPTY, now = hour(0))
        assertTrue(s.isEmpty)
        assertNull(s.heroTempC)
    }

    /**
     * The hero drops a station reading older than ninety minutes, because it stands in for the
     * forecast there. The card shows the same reading with its timestamp instead, so an older
     * measurement stays readable rather than disappearing without a word.
     */
    @Test
    fun `the station card keeps a reading the hero has already let go`() {
        val old = it.apexweather.domain.model.StationObservation(
            stationName = "Meran", time = hour(0), tempC = 18.0, humidityPct = 60, windKmh = 5.0,
            windDir = "NO", gustKmh = 12.0, precipMm = 0.0, pressureHpa = 1013.0,
        )
        val s = HomeStateBuilder.build(
            snapshot.copy(observation = old), AppSettings(), consensus,
            now = hour(0).plusSeconds(4 * 3600),
        )
        assertNull("the hero must not quote a four-hour-old reading", s.observation)
        assertEquals(old, s.station)
    }

    @Test
    fun `warnings travel from the snapshot to the screen untouched`() {
        val w = it.apexweather.domain.model.Warning(
            identifier = "x", type = it.apexweather.domain.model.WarningType.RAIN,
            level = it.apexweather.domain.model.WarningLevel.ORANGE, areaDesc = "Trentino Alto Adige",
            onset = hour(0), expires = hour(6), headline = "Orange Rain Warning",
        )
        val s = HomeStateBuilder.build(snapshot.copy(warnings = listOf(w)), AppSettings(), consensus, now = hour(0))
        assertEquals(listOf(w), s.warnings)
    }

    @Test
    fun `the day list runs two weeks rather than one`() {
        assertEquals(14, HomeStateBuilder.MAX_DAYS)
    }
}
