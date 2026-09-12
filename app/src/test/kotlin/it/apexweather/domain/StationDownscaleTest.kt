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

    /** Dorf Tirol at 600 m over its station in Meran at 330 m, which is what these cases are set at. */
    private val dz = 270

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
            observation(hour(2), 12.4), reference(offsetFromVillage = 1.9), forecasts, consensus, now = hour(2), heightDifferenceM = dz,
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
            observation(hour(2), 8.3), reference(offsetFromVillage = 1.7), forecasts, consensus, now = hour(2), heightDifferenceM = dz,
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
     *
     * Leaving it means the two sources cannot be reconciled, and there is then nothing here worth
     * quoting: the caller falls back to the models' own village value, which is already at the
     * village's height. It used to be clamped to the nearer end of the bracket instead, and the
     * nearer end is the thermometer whenever the reading is the outer value — see the class doc for
     * the morning over Dorf Tirol that turned that into the Etschtal's fog quoted as the village's
     * temperature. On this night it makes no difference to the screen, because the nearer end here
     * *is* the models' village value.
     */
    @Test
    fun `a reading that cannot be reconciled with the models is not quoted at all`() {
        // models: village 10,0, station 11,5 (a gap of -1,5); thermometer 10,05, just above both.
        // Carrying it up gives 8,55 — colder than the thermometer and colder than the forecast.
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 10.05), reference(offsetFromVillage = 1.5), forecasts, consensus, now = hour(2), heightDifferenceM = dz,
        )
        assertNull(t)
    }

    /**
     * The thermometer does not report on the hour, and the models only exist on it.
     *
     * Meran publishes every twenty minutes, so a reading is up to fifty minutes away from the model
     * hour it used to be compared against — and on a September morning the models have the valley
     * warming at three degrees an hour. Measuring the station's anomaly against a stale hour charges
     * the thermometer for the warming it has not done yet, invents a cold anomaly of up to two and a
     * half degrees, and then carries part of that invention up the hill. That is most of what put
     * 11° over Dorf Tirol on 2026-09-12 while the strip below it said 13: the 07:50 reading was held
     * against the models' 07:00.
     *
     * The models are interpolated to the minute the thermometer actually read instead.
     */
    @Test
    fun `the anomaly is measured at the minute the thermometer read, not on the hour`() {
        val ramping = StationReference(
            fetchedAt = hour(0), elevationM = 330.0,
            bySource = mapOf(
                Source.ICON_CH1.name to mapOf(hour(2).epochSecond to 12.0, hour(3).epochSecond to 15.0),
                Source.ICON_D2.name to mapOf(hour(2).epochSecond to 12.0, hour(3).epochSecond to 15.0),
            ),
        )
        val halfPast = hour(2).plusSeconds(1800)
        // Halfway through a 3 K hour the models say 13,5 there, so a thermometer reading 13,0 is
        // half a degree cool — not the whole degree warm the hour's own value would have made it.
        assertEquals(-0.5, StationDownscale.stationAnomaly(halfPast, 13.0, ramping, now = hour(3))!!, 1e-9)
        // On the hour there is nothing to interpolate and the value is the hour's own.
        assertEquals(1.0, StationDownscale.stationAnomaly(hour(2), 13.0, ramping, now = hour(3))!!, 1e-9)
    }

    /** The last hour the reference covers has no hour after it to lean on, and is used as it is. */
    @Test
    fun `a reading past the end of the reference falls back to the last hour it has`() {
        val ending = StationReference(
            fetchedAt = hour(0), elevationM = 330.0,
            bySource = mapOf(Source.ICON_CH1.name to mapOf(hour(2).epochSecond to 12.0)),
        )
        assertEquals(1.0, StationDownscale.stationAnomaly(hour(2).plusSeconds(1800), 13.0, ending, now = hour(3))!!, 1e-9)
    }

    /** The bracket costs the honest case nothing: an afternoon correction still applies in full. */
    @Test
    fun `a correction inside the bracket is left alone`() {
        // models: village 10,0, station 12,0; thermometer 12,4 — the village is genuinely colder.
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 12.4), reference(offsetFromVillage = 2.0), forecasts, consensus, now = hour(2), heightDifferenceM = dz,
        )!!
        assertEquals(10.4, t, 1e-9)
    }

    /** A thermometer that agrees with the models is carried up whole, as it always was. */
    @Test
    fun `a station the models agree with is carried up in full`() {
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 11.7), reference(offsetFromVillage = 1.7), forecasts, consensus, now = hour(2), heightDifferenceM = dz,
        )
        assertEquals(10.0, t!!, 1e-9)
    }

    /** On an inversion night the valley floor is the colder of the two and the sign flips. */
    @Test
    fun `an inversion moves the reading upwards, not down`() {
        val t = StationDownscale.villageTemperature(
            observation(hour(2), 7.0), reference(offsetFromVillage = -3.0), forecasts, consensus, now = hour(2), heightDifferenceM = dz,
        )
        assertEquals(10.0, t!!, 1e-9)
    }

    /** Beyond a few degrees this is no longer a height difference but a broken input. */
    @Test
    fun `an implausible difference is refused rather than applied`() {
        assertNull(StationDownscale.offsetAt(hour(2), reference(offsetFromVillage = 20.0), forecasts, now = hour(2), heightDifferenceM = dz))
    }

    @Test
    fun `a reference from yesterday no longer describes today's air`() {
        val old = reference(1.9, fetchedAt = hour(0).minusSeconds(24 * 3600))
        assertNull(StationDownscale.offsetAt(hour(2), old, forecasts, now = hour(2), heightDifferenceM = dz))
    }

    @Test
    fun `no reference and no observed temperature both mean no correction`() {
        assertNull(StationDownscale.offsetAt(hour(2), null, forecasts, now = hour(2), heightDifferenceM = dz))
        assertNull(StationDownscale.villageTemperature(observation(hour(2), null), reference(1.9), forecasts, consensus, hour(2), dz))
    }

    /** An hour the reference does not cover cannot be corrected, and is not guessed at. */
    @Test
    fun `an hour outside the reference is left alone`() {
        assertNull(StationDownscale.offsetAt(hour(100), reference(1.9), forecasts, now = hour(2), heightDifferenceM = dz))
    }

    /**
     * What counts as implausible depends on how far the reading has to travel.
     *
     * Five degrees between two points 400 m apart is a steep afternoon; five degrees between two
     * points at the same altitude is a broken input, and the old flat six-degree cap called both of
     * them fine. Kastelruth's station now stands 73 m below it and Karneid's 45 m above: for those
     * places a five-degree "height correction" is not one.
     */
    @Test
    fun `how far the reading may be moved depends on how far it has to travel`() {
        val steep = reference(offsetFromVillage = 5.0)
        // The station is five degrees above the village here, so the offset that carries a reading
        // up to it is -5.
        assertEquals(
            -5.0,
            StationDownscale.offsetAt(hour(2), steep, forecasts, now = hour(2), heightDifferenceM = 400)!!,
            1e-9,
        )
        assertNull(
            "a station at the village's own altitude cannot be five degrees away from it",
            StationDownscale.offsetAt(hour(2), steep, forecasts, now = hour(2), heightDifferenceM = 0),
        )
    }

    /**
     * The hill is measured model by model, not by subtracting two medians taken over different
     * models.
     *
     * Here every one of the four runs says the same thing about the hill: the station is 1,9 K
     * warmer than the village. But the village median and the station median are not taken over the
     * same four. [ConsensusForecast] drops both globals the moment two regional sources are present,
     * while [StationReference] is the Open-Meteo call and holds everything it returns — so the
     * village side read 10,0 (the two regionals) and the station side 16,9 (the median of two
     * regionals at 11,9 and two globals at 21,9). Subtracting those gave -6,9 K, which is not a
     * height difference, and the guard below then refused the whole correction: the hero silently
     * dropped back to the consensus on a day nothing was wrong.
     *
     * Pairing each model with itself gives -1,9 four times over, which is what the models said.
     */
    @Test
    fun `the offset is each model against itself, not one median minus another`() {
        val hot = forecasts + mapOf(
            Source.ECMWF to forecast(Source.ECMWF, (0 until 24).map { point(it, 20.0) }),
            Source.ECMWF_AIFS to forecast(Source.ECMWF_AIFS, (0 until 24).map { point(it, 20.0) }),
        )
        val atStation = StationReference(
            fetchedAt = hour(0),
            elevationM = 330.0,
            bySource = hot.mapValues { (_, f) ->
                f.hourly.associate { it.time.epochSecond to it.tempC + 1.9 }
            }.mapKeys { it.key.name },
        )
        assertEquals(
            -1.9,
            StationDownscale.offsetAt(hour(2), atStation, hot, now = hour(2), heightDifferenceM = dz)!!,
            1e-9,
        )
    }

    /**
     * And a model's own habit cancels instead of being reported as the height of the hill.
     *
     * [BiasCorrector] subtracts a per-model habit from the village consensus and nothing from the
     * station reference, which has no thermometer to measure a habit against. Subtracting the two
     * put up to three degrees of that correction into the offset and quoted it to the reader as a
     * lapse rate. The same model on both ends of the subtraction cannot do that.
     */
    @Test
    fun `a model's bias correction does not leak into the height offset`() {
        val bias = ModelBias(
            mapOf(
                Source.ICON_CH1 to DayPart.entries.associateWith { LeadBucket.entries.associateWith { 2.5 } },
                Source.ICON_D2 to DayPart.entries.associateWith { LeadBucket.entries.associateWith { 2.5 } },
            ),
        )
        val corrected = blender.blend(forecasts, bias, now = hour(0))
        // The correction really is in the consensus: 10,0 less a 2,5 K warm habit.
        assertEquals(7.5, corrected.hourly.first { it.time == hour(2) }.tempC, 1e-9)
        // And not in the offset, which is still the 1,9 K the models put between the two points.
        assertEquals(
            -1.9,
            StationDownscale.offsetAt(hour(2), reference(offsetFromVillage = 1.9), forecasts, now = hour(2), heightDifferenceM = dz)!!,
            1e-9,
        )
    }

    /** A station at the place's own height is still allowed the slack two points always differ by. */
    @Test
    fun `a station at the same altitude still gets the horizontal slack`() {
        val gentle = reference(offsetFromVillage = 1.5)
        assertEquals(
            -1.5,
            StationDownscale.offsetAt(hour(2), gentle, forecasts, now = hour(2), heightDifferenceM = 0)!!,
            1e-9,
        )
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
