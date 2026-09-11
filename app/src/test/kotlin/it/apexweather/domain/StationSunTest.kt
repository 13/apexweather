package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.StationObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The afternoon this was written for.
 *
 * On 2026-09-11 at 13:00 the app led with "Bedeckt" over Dorf Tirol while the sun was out of a
 * nearly clear sky, and it was not wrong about its sources: of the six regional models, ICON-CH1,
 * ICON-D2 and DMI HARMONIE called it overcast — two of them at 100 % cloud — ICON-CH2 and ICON-2I
 * said partly cloudy, and only KNMI saw the real sky at 14 %. Three to two to one.
 *
 * The station at Meran was measuring 834 W/m² at the time, which at that sun height is a cloudless
 * sky, and the app was throwing the number away.
 */
class StationSunTest {

    /** Meran's station, where the reading that started this came from. */
    private val lat = 46.688
    private val lon = 11.1366

    /** 13:40 local on 2026-09-11, the reading's own timestamp. */
    private val at: Instant = Instant.parse("2026-09-11T11:40:00Z")

    private fun observation(radiation: Double?, time: Instant = at) = StationObservation(
        stationName = "Meran", time = time, tempC = 24.4, humidityPct = 57, windKmh = 18.0,
        windDir = "S", gustKmh = 45.4, precipTodayMm = 0.0, pressureHpa = 1014.5,
        radiationWm2 = radiation,
    )

    private fun hour(condition: Condition, precipMm: Double = 0.0) = ConsensusHour(
        time = at, tempC = 22.0, tempMinC = 21.0, tempMaxC = 23.0, feelsLikeC = 22.0,
        precipMm = precipMm, precipProb = 0, windKmh = 10.0, gustKmh = null, freezingLevelM = null,
        condition = condition, agreement = 0.8f, sourceCount = 6,
        perSource = mapOf(
            Source.ICON_D2 to HourlyPoint(time = at, tempC = 22.0, cloudPct = 100, condition = Condition.CLOUDY),
        ),
    )

    private fun corrected(voted: Condition, radiation: Double?, precipMm: Double = 0.0) =
        StationSun.corrected(voted, observation(radiation), lat, lon, at, hour(voted, precipMm))

    @Test
    fun `the sun height is where it was that afternoon`() {
        val elevation = SunPhaseCalculator.elevationDegrees(at, lat, lon)
        assertEquals(47.5, elevation, 1.0)
    }

    /** The whole point: 834 W/m² under a sun that high is not an overcast sky. */
    @Test
    fun `a pyranometer in full sun overrules a forecast of overcast`() {
        val index = StationSun.clearSkyIndex(observation(834.0), lat, lon, at)!!
        assertTrue("index was $index", index >= StationSun.CLEAR_INDEX)
        assertEquals(Condition.MOSTLY_CLEAR, corrected(Condition.CLOUDY, 834.0))
    }

    /** Half the expected sunlight is real sun through real cloud, and says so rather than "clear". */
    @Test
    fun `a middling reading says broken cloud, not clear sky`() {
        assertEquals(Condition.PARTLY_CLOUDY, corrected(Condition.CLOUDY, 420.0))
    }

    /**
     * The asymmetry this rule stands on. A station in a valley loses the sun behind a ridge long
     * before the sky clouds over — across the network on that same clear afternoon the index ran
     * from 0,09 at Salurn to 1,19 at Ulten Weißbrunn — so a dark reading is shade at least as often
     * as cloud, and must never be allowed to add cloud of its own.
     */
    @Test
    fun `a shaded station never makes the sky cloudier than the models said`() {
        assertEquals(Condition.PARTLY_CLOUDY, corrected(Condition.PARTLY_CLOUDY, 40.0))
        assertEquals(Condition.CLEAR, corrected(Condition.CLEAR, 40.0))
        assertEquals(Condition.CLOUDY, corrected(Condition.CLOUDY, 40.0))
    }

    /** A forecast already clearer than the measurement allows is left exactly as it is. */
    @Test
    fun `it only ever lightens`() {
        assertEquals(Condition.CLEAR, corrected(Condition.CLEAR, 834.0))
        assertEquals(Condition.MOSTLY_CLEAR, corrected(Condition.MOSTLY_CLEAR, 834.0))
    }

    /** Sun and rain coexist; a bright shower is still a shower, and its amount is not this rule's. */
    @Test
    fun `a wet hour is left alone however bright it is`() {
        assertEquals(Condition.RAIN, corrected(Condition.RAIN, 834.0))
        assertEquals(Condition.THUNDERSTORM, corrected(Condition.THUNDERSTORM, 834.0))
        // Even a dry-labelled hour with rain in it: the amount is what decides.
        assertEquals(Condition.CLOUDY, corrected(Condition.CLOUDY, 834.0, precipMm = 0.5))
    }

    /** Fog is not something 834 W/m² can be measured through. */
    @Test
    fun `bright sun beats a vote of fog`() {
        assertEquals(Condition.MOSTLY_CLEAR, corrected(Condition.FOG, 834.0))
    }

    /**
     * At night the instrument reads nothing and means nothing, and near sunrise the denominator is
     * small enough that haze or a ridge swamps the answer. Neither may conclude "overcast".
     */
    @Test
    fun `nothing is concluded from a dark or a low sun`() {
        val midnight = Instant.parse("2026-09-11T23:00:00Z")
        assertNull(StationSun.clearSkyIndex(observation(0.0, midnight), lat, lon, midnight))
        assertEquals(Condition.CLOUDY, StationSun.corrected(Condition.CLOUDY, observation(0.0, midnight), lat, lon, midnight, hour(Condition.CLOUDY)))

        val dawn = Instant.parse("2026-09-11T05:00:00Z")
        assertTrue(SunPhaseCalculator.elevationDegrees(dawn, lat, lon) < StationSun.MIN_ELEVATION_DEG)
        assertNull(StationSun.clearSkyIndex(observation(60.0, dawn), lat, lon, dawn))
    }

    /** A station that publishes no radiation — eight of the fifty-seven — changes nothing. */
    @Test
    fun `a station without a pyranometer changes nothing`() {
        assertEquals(Condition.CLOUDY, corrected(Condition.CLOUDY, null))
        assertNull(StationSun.clearSkyIndex(observation(null), lat, lon, at))
    }

    /** An old reading is a fact about an hour that has passed, not about this sky. */
    @Test
    fun `a stale reading says nothing`() {
        val later = at.plus(StationSun.FRESH_FOR).plusSeconds(60)
        assertNull(StationSun.clearSkyIndex(observation(834.0), lat, lon, later))
    }
}
