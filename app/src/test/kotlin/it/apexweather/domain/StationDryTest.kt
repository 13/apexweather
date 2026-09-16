package it.apexweather.domain

import it.apexweather.domain.model.StationObservation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StationDryTest {

    /** Meran, 2026-09-16, 18:56 local: the screen said "Gewitter", the station read 0,0 mm and 49 %. */
    private val now: Instant = Instant.parse("2026-09-16T16:56:00Z")
    private val readAt: Instant = Instant.parse("2026-09-16T16:40:00Z")

    private fun obs(
        today: Double?,
        rh: Int? = 49,
        at: Instant = readAt,
        previous: Double? = null,
        previousAt: Instant? = null,
    ) = StationObservation(
        stationName = "Meran", time = at, tempC = 25.9, humidityPct = rh, windKmh = null,
        windDir = null, gustKmh = null, precipTodayMm = today, pressureHpa = null,
        previousPrecipTodayMm = previous, previousTime = previousAt,
    )

    @Test
    fun `nothing fallen all day is dry`() {
        assertTrue(StationDry.isDry(obs(0.0), now))
    }

    @Test
    fun `an unchanged total since the previous reading is dry`() {
        assertTrue(StationDry.isDry(obs(4.2, previous = 4.2, previousAt = readAt.minusSeconds(3600)), now))
    }

    @Test
    fun `a rising total is not dry`() {
        assertFalse(StationDry.isDry(obs(4.4, previous = 4.2, previousAt = readAt.minusSeconds(3600)), now))
    }

    /** Rain earlier today and nothing to compare against: the total alone cannot say it stopped. */
    @Test
    fun `a wet total without a previous reading says nothing`() {
        assertFalse(StationDry.isDry(obs(4.2), now))
    }

    /** A previous reading from yesterday is a different midnight's total. */
    @Test
    fun `a previous reading from another day is not compared`() {
        assertFalse(StationDry.isDry(obs(4.2, previous = 4.2, previousAt = Instant.parse("2026-09-15T20:00:00Z")), now))
    }

    /** Drizzle can fall for a while without tipping the bucket, and it saturates the air. */
    @Test
    fun `humid air is not trusted to be dry`() {
        assertFalse(StationDry.isDry(obs(0.0, rh = 92), now))
        assertTrue(StationDry.isDry(obs(0.0, rh = 89), now))
    }

    /** A shower can start and end inside an hour, so the reading has to be recent. */
    @Test
    fun `an old reading says nothing about now`() {
        assertFalse(StationDry.isDry(obs(0.0, at = now.minusSeconds(31 * 60)), now))
        assertTrue(StationDry.isDry(obs(0.0, at = now.minusSeconds(29 * 60)), now))
    }

    @Test
    fun `no station and no total are not evidence`() {
        assertFalse(StationDry.isDry(null, now))
        assertFalse(StationDry.isDry(obs(null), now))
    }

    @Test
    fun `the previous reading is carried when the station publishes a new one`() {
        val older = obs(0.0, at = readAt.minusSeconds(1200))
        val newer = obs(0.0)
        val carried = StationDry.withPrevious(newer, older)
        assertTrue(carried.previousTime == older.time && carried.previousPrecipTodayMm == 0.0)
        // The same reading fetched twice keeps the comparison it already had.
        val again = StationDry.withPrevious(newer, carried)
        assertTrue(again.previousTime == older.time)
    }
}
