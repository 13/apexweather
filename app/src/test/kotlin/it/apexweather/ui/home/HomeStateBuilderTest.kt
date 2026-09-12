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
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
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
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot, AppSettings(), consensus, now = hour(3).plusSeconds(600))
        assertEquals(consensus.hourly[3].tempC, s.heroTempC!!, 0.0)
        assertEquals(Condition.RAIN, s.heroCondition)
        assertEquals(ParticleKind.RAIN, s.palette.particle)
        assertEquals(1.0, s.bandHalfWidth!!, 0.0)
        assertFalse(s.isEmpty)
    }

    private fun observation(temp: Double) = StationObservation(
        "Meran", hour(3), tempC = temp, humidityPct = 40, windKmh = 5.0, windDir = "W",
        gustKmh = null, precipTodayMm = 0.0, pressureHpa = 1010.0,
    )

    /** The models' view from down at the station, warmer than the village by [warmerBy]. */
    private fun reference(warmerBy: Double, fetchedAt: java.time.Instant = hour(3)) =
        it.apexweather.data.remote.StationReference(
            fetchedAt = fetchedAt,
            elevationM = 330.0,
            bySource = mapOf(
                Source.ICON_CH1.name to consensus.hourly.associate { it.time.epochSecond to it.tempC + warmerBy },
                Source.ICON_D2.name to consensus.hourly.associate { it.time.epochSecond to it.tempC + warmerBy },
            ),
        )

    /**
     * The station sits 270 m below the village, so its reading is quoted only after the models'
     * own difference between the two points has been applied to it.
     */
    @Test
    fun `a fresh observation is carried up to the village before it becomes the hero`() {
        // The models put the village at 14,0 and the station at 16,0; a thermometer reading 16,5
        // agrees with them to within half a degree, so the whole 2 K is taken off.
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot.copy(observation = observation(16.5), stationReference = reference(warmerBy = 2.0)),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertEquals(14.5, s.heroTempC!!, 1e-9)
        assertEquals(-2.0, s.heroAdjustmentC!!, 1e-9)
        assertEquals(Condition.RAIN, s.heroCondition)
        assertTrue(s.observation != null)
    }

    /**
     * The line under the hero quotes the correction, so it has to quote the one that was applied.
     * A station well away from what the models say about it is carried up only in part, and the
     * difference between the reading and the number on screen is then not the models' own gap.
     */
    @Test
    fun `the stated correction is the one actually applied`() {
        // village 14,0, station 16,0, thermometer 19,0: an anomaly of +3 K, half of which is shared.
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot.copy(observation = observation(19.0), stationReference = reference(warmerBy = 2.0)),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertEquals(15.5, s.heroTempC!!, 1e-9)
        assertEquals(s.heroTempC!! - 19.0, s.heroAdjustmentC!!, 1e-9)
        assertEquals(-3.5, s.heroAdjustmentC!!, 1e-9)
    }

    /**
     * When the carried reading lands outside what either of its sources says, neither of them
     * supports it and the station's is the one standing at the wrong altitude. The models' village
     * value wins, and the hero stops calling itself a measurement.
     *
     * Measured over Dorf Tirol on 2026-09-12 at 08:00. Meran was fogged in — 10,9 °C at 100 %
     * humidity under 35 W/m² of global radiation, which is a valley floor with cold air pooled on
     * it — while the village 264 m up was in the clear at about 13. The eight models put the station
     * at 13,35 and the village at 13,0, and their own gap between the two points said -1,60. The
     * carried reading came out at 10,08, below the thermometer *and* below the models' village, and
     * the bracket then handed the screen the raw 10,9: the Etschtal's temperature, quoted as the
     * village's, with the strip one line below it reading 13. The bracket was written to stop the
     * app leading with a number nothing supports; picking the thermometer when the two cannot be
     * reconciled is that same fault in the other direction, because the thermometer is the source
     * that is in the wrong place.
     */
    @Test
    fun `a reading that cannot be reconciled with the models gives way to them`() {
        // Models: village 14,0, station 16,0. A thermometer reading 13,0 is 3 K below what the
        // models say about its own site, and carrying what is left of that up the hill lands at
        // 12,5 — colder than the reading and colder than the models' village both.
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot.copy(observation = observation(13.0), stationReference = reference(warmerBy = 2.0)),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertEquals(consensus.hourly[3].tempC, s.heroTempC!!, 1e-9)
        // Not a moved reading any more, so the line under the hero must not claim one — and the
        // station's own card still shows what was actually measured.
        assertNull(s.heroAdjustmentC)
        assertNull(s.observation)
        assertTrue(s.station != null)
        // And the column labelled "Jetzt" says the same thing the hero does.
        assertEquals(s.heroTempC!!, s.upcomingHours.first().tempC, 1e-9)
    }

    /**
     * With nothing to carry it up with, the raw station reading is 270 m too low to stand for the
     * village, while the consensus is already at the village's height. The forecast wins.
     */
    @Test
    fun `without a reference the consensus is preferred to an uncorrected station reading`() {
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot.copy(observation = observation(25.5)),
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
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot.copy(observation = observation(25.5), stationReference = reference(2.0, fetchedAt = hour(3).minusSeconds(48 * 3600))),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertNull(s.heroAdjustmentC)
    }

    @Test
    fun `stale observation is ignored`() {
        val obs = StationObservation("Meran", hour(0), tempC = 25.5, null, null, null, null, null, null)
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot.copy(observation = obs), AppSettings(), consensus, now = hour(0).plus(Duration.ofMinutes(91)))
        assertNull(s.observation)
        assertEquals(consensus.hourly[1].tempC, s.heroTempC!!, 0.0)
    }

    /**
     * The strip's first column is labelled "Jetzt", and so is the hero. Two different numbers under
     * one word is the bug this covers.
     *
     * Measured on the phone on 2026-09-12 at 08:14 over Dorf Tirol: the hero read 11° and the column
     * directly beneath it read 13°, with nothing on screen accounting for the gap. The models had
     * the morning 2,5 K too warm — the thermometer in Meran read 10,9 where they put it at 13,35,
     * and the stations standing at the village's own height (St. Martin, 588 m: 9,1; Naturns,
     * 541 m: 9,4) agreed with the thermometer, not with the models. The hero had the correction and
     * the strip did not, because only the hour's *condition* was ever carried across.
     */
    @Test
    fun `the strip's first column carries the hero's corrected temperature`() {
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot.copy(observation = observation(16.5), stationReference = reference(warmerBy = 2.0)),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertEquals(14.5, s.heroTempC!!, 1e-9)
        assertEquals(s.heroTempC!!, s.upcomingHours.first().tempC, 1e-9)
        assertEquals(s.heroTempC!!, s.currentHour!!.tempC, 1e-9)
        // Only that hour. The models are not second-guessed for any hour a thermometer cannot see.
        assertEquals(consensus.hourly[4].tempC, s.upcomingHours[1].tempC, 1e-9)
    }

    /** With no reading to correct it with, the column is the consensus, untouched. */
    @Test
    fun `the strip's first column is the consensus when the hero is`() {
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot, AppSettings(), consensus, now = hour(3).plusSeconds(600))
        assertEquals(consensus.hourly[3].tempC, s.upcomingHours.first().tempC, 0.0)
        assertEquals(s.heroTempC!!, s.upcomingHours.first().tempC, 0.0)
    }

    @Test
    fun `upcoming hours start at the current hour and cap at 48`() {
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot, AppSettings(), consensus, now = hour(5).plusSeconds(1))
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
        val s = HomeStateBuilder.build(DORF_TIROL, WeatherSnapshot.EMPTY.copy(forecasts = withSun), AppSettings(), blender.blend(withSun), now = hour(3),
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
        val s = HomeStateBuilder.build(DORF_TIROL, WeatherSnapshot.EMPTY.copy(forecasts = week), AppSettings(), blender.blend(week), now = hour(0))

        val dayFive = hour(24 * 5).atZone(ROME).toLocalDate()
        assertTrue(s.upcomingHours.none { it.time.atZone(ROME).toLocalDate() == dayFive })
        assertTrue(s.hoursByDate.getValue(dayFive).isNotEmpty())
        assertEquals(setOf(Source.ICON_CH1, Source.ECMWF), s.sourcesForDay(dayFive).keys)
    }

    @Test
    fun `a day no model reaches has no per-source rows`() {
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot, AppSettings(), consensus, now = hour(0))
        assertTrue(s.sourcesForDay(hour(0).atZone(ROME).toLocalDate().plusDays(30)).isEmpty())
    }

    @Test
    fun `empty snapshot gives empty state with default palette`() {
        val s = HomeStateBuilder.build(DORF_TIROL, WeatherSnapshot.EMPTY, AppSettings(), consensus = ConsensusForecast.EMPTY, now = hour(0))
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
            windDir = "NO", gustKmh = 12.0, precipTodayMm = 0.0, pressureHpa = 1013.0,
        )
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot.copy(observation = old), AppSettings(), consensus,
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
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot.copy(warnings = listOf(w)), AppSettings(), consensus, now = hour(0))
        assertEquals(listOf(w), s.warnings)
    }

    @Test
    fun `the day list runs two weeks rather than one`() {
        assertEquals(14, HomeStateBuilder.MAX_DAYS)
    }

    /**
     * Not every municipality has a thermometer near enough to speak for it. Such a place shows the
     * consensus, which is already at the right altitude, and says nothing about a measurement.
     */
    @Test
    fun `a place with no station shows the consensus and claims no measurement`() {
        val s = HomeStateBuilder.build(
            it.apexweather.domain.STERZING, snapshot.copy(observation = null, stationReference = null),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertEquals(consensus.hourly[3].tempC, s.heroTempC!!, 0.0)
        assertNull(s.heroAdjustmentC)
        assertNull(s.station)
        assertEquals("Sterzing", s.place?.nameDe)
    }
    /**
     * The evening of 2026-09-10: foggy in Dorf Tirol, and not one of the ten sources said so. The
     * station said 100 % relative humidity, which is the only ground truth the app has about the
     * sky — so where a source *has* forecast fog, a saturated station is enough to carry it, for
     * this hour and no other.
     */
    @Test
    fun `a saturated station lets the current hour read as fog`() {
        val t0 = hour(0)
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 15.0, condition = Condition.CLOUDY))),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, listOf(point(0, 15.0, condition = Condition.CLOUDY))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 15.0, condition = Condition.CLOUDY))),
            Source.GEOSPHERE_AROME to forecast(Source.GEOSPHERE_AROME, listOf(point(0, 15.0, condition = Condition.FOG))),
        )
        val snapshot = WeatherSnapshot.EMPTY.copy(
            forecasts = f,
            observation = StationObservation(
                stationName = "Meran", time = t0, tempC = 15.9, humidityPct = 100, windKmh = null,
                windDir = null, gustKmh = null, precipTodayMm = null, pressureHpa = null,
            ),
        )
        val consensus = ConsensusBlender().blend(f)
        val state = HomeStateBuilder.build(DORF_TIROL, snapshot, AppSettings(), consensus, t0)

        assertEquals(Condition.FOG, state.heroCondition)
        // and the strip's first column is the same hour, so it must not disagree with the hero
        assertEquals(Condition.FOG, state.upcomingHours.first().condition)
    }

    /**
     * The afternoon of 2026-09-11: the app led with "Bedeckt" over Dorf Tirol while the sun was out
     * of a nearly clear sky. Three of the six regional models called it overcast, two partly cloudy
     * and one clear, and the consensus reported them faithfully — while the station a kilometre and
     * a half away measured 834 W/m², which at that sun height is a cloudless sky.
     *
     * The hero and the strip's first column are the same hour and must not disagree.
     */
    @Test
    fun `a station in full sun overrules a current hour voted overcast`() {
        // 13:40 local, the reading's own timestamp, with the sun 47 degrees up.
        val t0 = java.time.Instant.parse("2026-09-11T11:40:00Z")
        // Exactly what the six regional models said that afternoon, labels and cloud both.
        fun saying(condition: Condition, cloud: Int) = it.apexweather.domain.model.HourlyPoint(
            time = t0, tempC = 22.0, cloudPct = cloud, condition = condition,
        )
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(saying(Condition.CLOUDY, 100))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(saying(Condition.CLOUDY, 100))),
            Source.DMI_HARMONIE to forecast(Source.DMI_HARMONIE, listOf(saying(Condition.CLOUDY, 87))),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, listOf(saying(Condition.PARTLY_CLOUDY, 71))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(saying(Condition.PARTLY_CLOUDY, 61))),
            Source.KNMI_HARMONIE to forecast(Source.KNMI_HARMONIE, listOf(saying(Condition.CLEAR, 14))),
        )
        val consensus = ConsensusBlender().blend(f)
        // Their labels are three overcast to two partly to one clear, which a plurality would call
        // overcast; the cloud they publish has a median of 79 %, which is not.
        assertEquals("the median of the cloud, not the plurality of the labels",
            Condition.PARTLY_CLOUDY, consensus.hourly.first().condition)

        val sunny = WeatherSnapshot.EMPTY.copy(
            forecasts = f,
            observation = StationObservation(
                stationName = "Meran", time = t0, tempC = 24.4, humidityPct = 57, windKmh = 18.0,
                windDir = "S", gustKmh = 45.4, precipTodayMm = 0.0, pressureHpa = 1014.5,
                radiationWm2 = 834.0,
            ),
        )
        val state = HomeStateBuilder.build(DORF_TIROL, sunny, AppSettings(), consensus, t0)
        assertEquals(Condition.MOSTLY_CLEAR, state.heroCondition)
        assertEquals(Condition.MOSTLY_CLEAR, state.upcomingHours.first().condition)

        // And with the pyranometer in shade, or absent, the models keep the hour.
        val shaded = sunny.copy(observation = sunny.observation!!.copy(radiationWm2 = 40.0))
        assertEquals(Condition.PARTLY_CLOUDY, HomeStateBuilder.build(DORF_TIROL, shaded, AppSettings(), consensus, t0).heroCondition)
        val blind = sunny.copy(observation = sunny.observation!!.copy(radiationWm2 = null))
        assertEquals(Condition.PARTLY_CLOUDY, HomeStateBuilder.build(DORF_TIROL, blind, AppSettings(), consensus, t0).heroCondition)
    }

    /** A dry station leaves the vote exactly as the models cast it. */
    @Test
    fun `without a saturated station one fog source does not carry the hour`() {
        val t0 = hour(0)
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 15.0, condition = Condition.CLOUDY))),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, listOf(point(0, 15.0, condition = Condition.CLOUDY))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 15.0, condition = Condition.CLOUDY))),
            Source.GEOSPHERE_AROME to forecast(Source.GEOSPHERE_AROME, listOf(point(0, 15.0, condition = Condition.FOG))),
        )
        val snapshot = WeatherSnapshot.EMPTY.copy(
            forecasts = f,
            observation = StationObservation(
                stationName = "Meran", time = t0, tempC = 15.9, humidityPct = 60, windKmh = null,
                windDir = null, gustKmh = null, precipTodayMm = null, pressureHpa = null,
            ),
        )
        val state = HomeStateBuilder.build(DORF_TIROL, snapshot, AppSettings(), ConsensusBlender().blend(f), t0)
        assertEquals(Condition.CLOUDY, state.heroCondition)
    }

    /**
     * A source that never answers has to be visible somewhere the reader looks.
     *
     * GeoSphere AROME returned HTTP 400 to every request for months. The consensus went from ten
     * models to nine, the agreement badge stayed exactly as confident, and the only place that said
     * anything was four scrolls down the comparison screen.
     */
    @Test
    fun `a source that has never delivered is named`() {
        val broken = WeatherSnapshot.EMPTY.copy(
            lastSuccessfulRefresh = hour(0),
            status = mapOf(
                Source.ICON_D2 to SourceStatus.Ok(hour(0)),
                Source.GEOSPHERE_AROME to SourceStatus.Failed("HTTP 400", lastIssuedAt = null),
            ),
        )
        val state = HomeStateBuilder.build(DORF_TIROL, broken, AppSettings(), consensus, hour(0))
        assertEquals(listOf(Source.GEOSPHERE_AROME), state.silentSources)
    }

    /** One that failed this time but has data from last time is having a bad minute, not dying. */
    @Test
    fun `a source with cached data is not called silent`() {
        val flaky = WeatherSnapshot.EMPTY.copy(
            lastSuccessfulRefresh = hour(0),
            status = mapOf(Source.ICON_D2 to SourceStatus.Failed("timeout", lastIssuedAt = hour(0))),
        )
        assertEquals(
            emptyList<Source>(),
            HomeStateBuilder.build(DORF_TIROL, flaky, AppSettings(), consensus, hour(0)).silentSources,
        )
    }

    /** And nothing is called silent before anything has ever worked. */
    @Test
    fun `the very first fetch accuses nobody`() {
        val firstRun = WeatherSnapshot.EMPTY.copy(
            lastSuccessfulRefresh = null,
            status = mapOf(Source.GEOSPHERE_AROME to SourceStatus.Failed("HTTP 400", lastIssuedAt = null)),
        )
        assertEquals(
            emptyList<Source>(),
            HomeStateBuilder.build(DORF_TIROL, firstRun, AppSettings(), consensus, hour(0)).silentSources,
        )
    }

}
