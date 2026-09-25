package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.StationObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class StrongWindTest {

    private fun h(i: Int, gust: Double?) = ConsensusHour(
        time = hour(i), tempC = 10.0, tempMinC = 10.0, tempMaxC = 10.0, feelsLikeC = null,
        precipMm = 0.0, precipProb = 0, windKmh = 10.0, gustKmh = gust?.plus(30.0), gustMedianKmh = gust,
        freezingLevelM = null, condition = Condition.CLEAR, agreement = 1f, sourceCount = 3,
        perSource = emptyMap(),
    )

    @Test
    fun `thresholds are inclusive and there are two levels`() {
        assertNull(StrongWind.levelOf(49.9))
        assertEquals(StrongWind.Level.STRONG, StrongWind.levelOf(50.0))
        assertEquals(StrongWind.Level.STRONG, StrongWind.levelOf(74.9))
        assertEquals(StrongWind.Level.STORM, StrongWind.levelOf(75.0))
        assertNull(StrongWind.levelOf(null as Double?))
    }

    /** The maximum is 30 km/h higher in every fixture hour and must never raise the line. */
    @Test
    fun `the line reads the median, not the maximum`() {
        val hours = (0 until 24).map { h(it, 40.0) }
        assertNull(StrongWind.line(hours, hour(0).plusSeconds(600)))
    }

    @Test
    fun `wind already blowing has no start time`() {
        val hours = listOf(h(0, 55.0), h(1, 62.0), h(2, 30.0), h(3, 90.0))
        val line = StrongWind.line(hours, hour(0).plusSeconds(600))!!
        assertNull(line.from)
        // The peak of the run it is in, not of a later, separate one.
        assertEquals(62.0, line.peakKmh, 0.0)
        assertEquals(StrongWind.Level.STRONG, line.level)
    }

    @Test
    fun `wind coming later carries the hour it starts`() {
        val hours = (0 until 24).map { h(it, if (it in 5..7) 80.0 else 20.0) }
        val line = StrongWind.line(hours, hour(0).plusSeconds(600))!!
        assertEquals(hour(5), line.from)
        assertEquals(StrongWind.Level.STORM, line.level)
    }

    @Test
    fun `past half a day it is the day list's business`() {
        val hours = (0 until 24).map { h(it, if (it == 13) 80.0 else 20.0) }
        assertNull(StrongWind.line(hours, hour(0).plusSeconds(600)))
    }

    @Test
    fun `an hour already gone does not count`() {
        val hours = listOf(h(0, 80.0), h(1, 20.0), h(2, 20.0))
        assertNull(StrongWind.line(hours, hour(1).plusSeconds(600)))
    }

    private fun obs(gust: Double?, age: Duration) = StationObservation(
        "Meran", hour(3).minus(age), tempC = 10.0, humidityPct = 50, windKmh = 10.0, windDir = "W",
        gustKmh = gust, precipTodayMm = null, pressureHpa = null,
    )

    @Test
    fun `a fresh measured gust replaces the models' in both directions`() {
        val now = hour(3)
        assertEquals(60.0, StrongWind.withMeasured(h(3, 20.0), obs(60.0, Duration.ofMinutes(10)), now).gustMedianKmh!!, 0.0)
        assertEquals(15.0, StrongWind.withMeasured(h(3, 70.0), obs(15.0, Duration.ofMinutes(10)), now).gustMedianKmh!!, 0.0)
    }

    @Test
    fun `a stale or absent measured gust changes nothing`() {
        val now = hour(3)
        assertEquals(20.0, StrongWind.withMeasured(h(3, 20.0), obs(60.0, Duration.ofMinutes(45)), now).gustMedianKmh!!, 0.0)
        assertEquals(20.0, StrongWind.withMeasured(h(3, 20.0), obs(null, Duration.ofMinutes(5)), now).gustMedianKmh!!, 0.0)
    }
}
