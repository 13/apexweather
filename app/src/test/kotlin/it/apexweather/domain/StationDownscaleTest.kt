package it.apexweather.domain

import it.apexweather.data.remote.StationReference
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.StationObservation
import it.apexweather.domain.model.WeatherSnapshot
import org.junit.Assert.assertEquals
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
        windDir = null, gustKmh = null, precipMm = null, pressureHpa = null,
    )

    private fun reference(offsetFromVillage: Double, fetchedAt: Instant = hour(0)) = StationReference(
        fetchedAt = fetchedAt,
        elevationM = 330.0,
        bySource = mapOf(
            Source.ICON_CH1.name to consensus.hourly.associate { it.time.epochSecond to it.tempC + offsetFromVillage },
            Source.ICON_D2.name to consensus.hourly.associate { it.time.epochSecond to it.tempC + offsetFromVillage },
        ),
    )

    @Test
    fun `the reading is moved by exactly what the models put between the two points`() {
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 25.5), reference(offsetFromVillage = 1.9), consensus, now = hour(2),
        )
        assertEquals(23.6, t!!, 1e-9)
    }

    /** On an inversion night the valley floor is the colder of the two and the sign flips. */
    @Test
    fun `an inversion moves the reading upwards, not down`() {
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 2.0), reference(offsetFromVillage = -3.0), consensus, now = hour(2),
        )
        assertEquals(5.0, t!!, 1e-9)
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
