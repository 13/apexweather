package it.apexweather.domain

import it.apexweather.data.remote.StationReference
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.StationObservation
import it.apexweather.domain.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The station is 1.4 km from the village but 270 m below it. Against the live models on 2026-09-09
 * those 270 m were worth a median 1.9 K over a two-day run, ranging from 0.3 K overnight to 2.7 K on
 * a clear afternoon — which is why the correction is taken from the models hour by hour instead of
 * from a fixed lapse rate.
 */
class StationDownscaleTest {

    private val blender = ConsensusBlender()
    private val forecasts = mapOf(
        Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 24).map { point(it, 10.0) }),
        Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 24).map { point(it, 10.0) }),
    )
    private val consensus = blender.blend(forecasts)

    private fun observation(at: Instant, temp: Double?) = StationObservation(
        stationName = "Meran", time = at, tempC = temp, humidityPct = null, windKmh = null,
        windDir = null, gustKmh = null, precipTodayMm = null, pressureHpa = null,
    )

    private fun reference(offsetFromVillage: Double, fetchedAt: Instant = hour(0)) = StationReference(
        fetchedAt = fetchedAt,
        elevationM = 330.0,
        bySource = mapOf(
            Source.ICON_CH1.name to consensus.hourly.associate { it.time.epochSecond to it.tempC + offsetFromVillage },
            Source.ICON_D2.name to consensus.hourly.associate { it.time.epochSecond to it.tempC + offsetFromVillage },
        ),
    )

    /**
     * The models put the village at 10,0 and the station 1,9 K above it; a thermometer reading
     * 12,4 is 0,5 K above what they say the station should read, and that small a disagreement is
     * the whole column being slightly off, so all of it is carried up.
     */
    @Test
    fun `the reading is moved by exactly what the models put between the two points`() {
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 12.4), reference(offsetFromVillage = 1.9), consensus, now = hour(2),
        )
        assertEquals(10.5, t!!, 1e-9)
    }

    /**
     * The night this was written for. The station read 12,7 while the models put it at 15,05 and the
     * village at 13,35; carrying the whole -2,35 K anomaly up gave 11,0, and a thermometer in the
     * village read 12. Cold air pools on the valley floor and the slope does not join in, so only
     * part of that anomaly belongs to the village.
     */
    @Test
    fun `a valley-floor anomaly is only partly carried up the hill`() {
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 8.3), reference(offsetFromVillage = 1.7), consensus, now = hour(2),
        )
        // models: village 10,0, station 11,7; the thermometer is 3,4 K below what they say the
        // station should read. Carrying all of it up would say 6,6; 36,7 % of it is shared at the
        // village, so 10,0 - 0,367 x 3,4.
        assertEquals(8.7533333333, t!!, 1e-6)
        assertTrue("the correction must not overshoot the thermometer downwards", t > 8.3 - 1.7)
    }

    /**
     * 2026-09-11, 05:00: the station read 12,9 while the models put it at 14,45 and the village at
     * 12,95. Their gap carried the reading down to 11,4 — colder than the thermometer and colder
     * than the forecast, a number neither source supported. The village's own thermometer read 12
     * to 13. The result may not leave the bracket its two sources span.
     */
    @Test
    fun `the moved reading never leaves what its two sources say`() {
        // models: village 10,0, station 11,5 (a gap of -1,5); thermometer 10,05, just above both.
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 10.05), reference(offsetFromVillage = 1.5), consensus, now = hour(2),
        )!!
        // Pulled back to the nearer end of the bracket, which here is the models' own village value.
        assertTrue("$t is colder than both the thermometer and the forecast", t >= 10.0)
        assertTrue("$t is warmer than both", t <= 10.05)
        assertEquals(10.0, t, 1e-9)
    }

    /** The bracket costs the honest case nothing: an afternoon correction still applies in full. */
    @Test
    fun `a correction inside the bracket is left alone`() {
        // models: village 10,0, station 12,0; thermometer 12,4 — the village is genuinely colder.
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 12.4), reference(offsetFromVillage = 2.0), consensus, now = hour(2),
        )!!
        assertEquals(10.4, t, 1e-9)
    }

    /** A thermometer that agrees with the models is carried up whole, as it always was. */
    @Test
    fun `a station the models agree with is carried up in full`() {
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 11.7), reference(offsetFromVillage = 1.7), consensus, now = hour(2),
        )
        assertEquals(10.0, t!!, 1e-9)
    }

    /** On an inversion night the valley floor is the colder of the two and the sign flips. */
    @Test
    fun `an inversion moves the reading upwards, not down`() {
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 7.0), reference(offsetFromVillage = -3.0), consensus, now = hour(2),
        )
        assertEquals(10.0, t!!, 1e-9)
    }

    /** Beyond a few degrees this is no longer a height difference but a broken input. */
    @Test
    fun `an implausible difference is refused rather than applied`() {
        assertNull(StationDownscale.offsetAt(hour(2), reference(offsetFromVillage = 20.0), consensus, now = hour(2)))
    }

    @Test
    fun `a reference from yesterday no longer describes today's air`() {
        val old = reference(1.9, fetchedAt = hour(0).minusSeconds(24 * 3600))
        assertNull(StationDownscale.offsetAt(hour(2), old, consensus, now = hour(2)))
    }

    @Test
    fun `no reference and no observed temperature both mean no correction`() {
        assertNull(StationDownscale.offsetAt(hour(2), null, consensus, now = hour(2)))
        assertNull(StationDownscale.villageTemperature(observation(hour(2), null), reference(1.9), consensus, hour(2)))
    }

    /** An hour the reference does not cover cannot be corrected, and is not guessed at. */
    @Test
    fun `an hour outside the reference is left alone`() {
        assertNull(StationDownscale.offsetAt(hour(100), reference(1.9), consensus, now = hour(2)))
    }
}

/**
 * A stale model run stays visible per source, with its age beside it, but is kept out of the number
 * the app leads with — mixing yesterday's run into the median looks exactly as confident as the rest.
 */
class ForecastsForBlendTest {

    private val fresh = forecast(Source.ICON_CH1, listOf(point(0, 10.0)))
    private val stale = forecast(Source.ICON_D2, listOf(point(0, 20.0)))
    private val issued: Instant = hour(0)

    private fun snapshot(vararg statuses: Pair<Source, SourceStatus>) = WeatherSnapshot.EMPTY.copy(
        forecasts = mapOf(Source.ICON_CH1 to fresh, Source.ICON_D2 to stale),
        status = statuses.toMap(),
    )

    @Test
    fun `a stale run is left out of the blend`() {
        val s = snapshot(Source.ICON_CH1 to SourceStatus.Ok(issued), Source.ICON_D2 to SourceStatus.Stale(issued))
        assertEquals(setOf(Source.ICON_CH1), s.forecastsForBlend.keys)
        // and is still there for the per-source lists
        assertEquals(2, s.forecasts.size)
    }

    @Test
    fun `a failed source is left out too`() {
        val s = snapshot(Source.ICON_CH1 to SourceStatus.Ok(issued), Source.ICON_D2 to SourceStatus.Failed("boom", issued))
        assertEquals(setOf(Source.ICON_CH1), s.forecastsForBlend.keys)
    }

    /** With nothing current there is nothing to prefer; the screen's staleness banner says the rest. */
    @Test
    fun `when everything is stale everything is used`() {
        val s = snapshot(Source.ICON_CH1 to SourceStatus.Stale(issued), Source.ICON_D2 to SourceStatus.Stale(issued))
        assertEquals(setOf(Source.ICON_CH1, Source.ICON_D2), s.forecastsForBlend.keys)
    }
}
