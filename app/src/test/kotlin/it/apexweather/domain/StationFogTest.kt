package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.StationObservation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StationFogTest {

    private val now: Instant = Instant.parse("2026-09-10T18:50:00Z")

    private fun obs(rh: Int?, at: Instant = now) = StationObservation(
        stationName = "Meran", time = at, tempC = 15.9, humidityPct = rh, windKmh = null,
        windDir = null, gustKmh = null, precipTodayMm = null, pressureHpa = null,
    )

    /** The reading from Meran on the evening this was written, while it was foggy in the village. */
    @Test
    fun `a hundred per cent is saturated`() {
        assertTrue(StationFog.saturated(obs(100), now))
    }

    @Test
    fun `ninety seven is the bar, and ninety six is under it`() {
        assertTrue(StationFog.saturated(obs(97), now))
        assertFalse(StationFog.saturated(obs(96), now))
    }

    /** Older than the hero's own cutoff, it is no longer evidence about what the sky is doing now. */
    @Test
    fun `a stale reading says nothing about this minute`() {
        assertFalse(StationFog.saturated(obs(100, at = now.minusSeconds(91 * 60)), now))
        assertTrue(StationFog.saturated(obs(100, at = now.minusSeconds(89 * 60)), now))
    }

    @Test
    fun `no station and no humidity are both simply not evidence`() {
        assertFalse(StationFog.saturated(null, now))
        assertFalse(StationFog.saturated(obs(null), now))
    }
    private fun hourOf(precipMm: Double, cloudPct: Int?) = ConsensusHour(
        time = now, tempC = 15.0, tempMinC = 14.0, tempMaxC = 16.0, feelsLikeC = null,
        precipMm = precipMm, precipProb = 60, windKmh = 4.0, gustKmh = null, freezingLevelM = null,
        condition = Condition.CLOUDY, agreement = 0.8f, sourceCount = 2,
        perSource = mapOf(
            Source.ICON_CH1 to HourlyPoint(now, 15.0, precipMm = precipMm, cloudPct = cloudPct, condition = Condition.CLOUDY),
            Source.ICON_D2 to HourlyPoint(now, 15.0, precipMm = precipMm, cloudPct = cloudPct, condition = Condition.CLOUDY),
        ),
    )

    /** Dorf Tirol, 2026-09-10, 19:00: foggy outside, ten forecasts saying otherwise, station at 100 %. */
    @Test
    fun `saturated air under a covered sky with nothing falling is fog`() {
        assertTrue(StationFog.impliesFog(obs(100), now, hourOf(precipMm = 0.35, cloudPct = 100)))
    }

    /** Rain saturates the air too, and "Nebel" in a downpour is the same error facing the other way. */
    @Test
    fun `a wet hour is not fog however saturated the station`() {
        assertFalse(StationFog.impliesFog(obs(100), now, hourOf(precipMm = 2.0, cloudPct = 100)))
    }

    /** Without this clause a clear humid dawn reads as fog. */
    @Test
    fun `a clearing sky is not fog however saturated the station`() {
        assertFalse(StationFog.impliesFog(obs(100), now, hourOf(precipMm = 0.0, cloudPct = 40)))
    }

    @Test
    fun `a dry station is not fog`() {
        assertFalse(StationFog.impliesFog(obs(70), now, hourOf(precipMm = 0.0, cloudPct = 100)))
    }

    @Test
    fun `no hour and no cloud reading are both simply not evidence`() {
        assertFalse(StationFog.impliesFog(obs(100), now, null))
        assertFalse(StationFog.impliesFog(obs(100), now, hourOf(precipMm = 0.0, cloudPct = null)))
    }

}
