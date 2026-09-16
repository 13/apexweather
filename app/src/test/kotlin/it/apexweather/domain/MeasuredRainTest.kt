package it.apexweather.domain

import it.apexweather.domain.model.StationObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MeasuredRainTest {

    private val now: Instant = Instant.parse("2026-09-16T18:56:00Z")

    private fun obs(
        today: Double?,
        previous: Double? = null,
        previousMinutesBefore: Long = 20,
        rh: Int? = 70,
        at: Instant = now.minusSeconds(10 * 60),
    ) = StationObservation(
        stationName = "Meran", time = at, tempC = 15.0, humidityPct = rh, windKmh = null,
        windDir = null, gustKmh = null, precipTodayMm = today, pressureHpa = null,
        previousPrecipTodayMm = previous, previousTime = previous?.let { at.minusSeconds(previousMinutesBefore * 60) },
    )

    private fun radar(dbz: Int?, minutesOld: Long = 10, snow: Boolean = false) =
        RadarNow(now.minusSeconds(minutesOld * 60), RadarReading(dbz, snow))

    @Test
    fun `a rising gauge is rain, at the rate it rose`() {
        val m = MeasuredRain.now(obs(today = 3.0, previous = 2.5, previousMinutesBefore = 20), radar = null, now = now)
        assertTrue(m is MeasuredRain.Wet)
        assertEquals(1.5, (m as MeasuredRain.Wet).mmPerHour, 1e-9)
    }

    /** A rise measured over hours says it rained at some point, not that it is raining. */
    @Test
    fun `a rise over too long an interval is not rain now`() {
        assertNull(MeasuredRain.now(obs(today = 3.0, previous = 2.5, previousMinutesBefore = 90, rh = 95), radar = null, now = now))
    }

    @Test
    fun `a gauge that has not moved is dry, whatever the radar says`() {
        assertEquals(MeasuredRain.Dry, MeasuredRain.now(obs(today = 0.0), radar(dbz = 30), now))
    }

    /** Where the gauge has nothing to say, a fresh radar echo is rain at its Marshall–Palmer rate. */
    @Test
    fun `a radar echo is rain where the gauge is silent`() {
        val humid = obs(today = 2.0, rh = 95) // no previous reading, too humid to call dry
        val m = MeasuredRain.now(humid, radar(dbz = 30), now) as MeasuredRain.Wet
        assertEquals(RadarAtPlace.rateOf(30), m.mmPerHour, 1e-9)
        val noStation = MeasuredRain.now(null, radar(dbz = 30), now)
        assertTrue(noStation is MeasuredRain.Wet)
    }

    @Test
    fun `a radar echo under rain strength, or an old frame, is nothing`() {
        assertNull(MeasuredRain.now(null, radar(dbz = 10), now))
        assertNull(MeasuredRain.now(null, radar(dbz = 30, minutesOld = 25), now))
        assertNull(MeasuredRain.now(null, radar(dbz = null), now))
    }

    @Test
    fun `radar snow is snow`() {
        val m = MeasuredRain.now(null, radar(dbz = 25, snow = true), now) as MeasuredRain.Wet
        assertTrue(m.snow)
    }

    /** The gauge's rise outranks a dry radar: the bucket measured it. */
    @Test
    fun `a rising gauge beats a dry radar`() {
        assertTrue(MeasuredRain.now(obs(today = 1.0, previous = 0.8), radar(dbz = null), now) is MeasuredRain.Wet)
    }
}
