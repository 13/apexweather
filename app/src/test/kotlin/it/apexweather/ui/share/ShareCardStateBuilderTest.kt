package it.apexweather.ui.share

import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.DORF_TIROL_WITH_PWS
import it.apexweather.domain.ROME
import it.apexweather.domain.STERZING
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.domain.point
import it.apexweather.ui.home.HomeStateBuilder
import it.apexweather.ui.home.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What travels when a day is shared.
 *
 * The builder's whole job is choosing — it computes no weather, on purpose. The hero on
 * [HomeUiState] has already been through `StationDownscale`, `StationFog`, `StationSun`,
 * `StationDry` and `MeasuredRain`, and re-deriving any of it here would put a second answer about
 * one hour into a picture that then leaves the phone. So these tests are about what is carried and
 * which hours are picked, and the first of them is the one that would catch a re-derivation.
 */
class ShareCardStateBuilderTest {

    private val blender = ConsensusBlender()
    private val german = Locale.GERMANY

    /**
     * Nine days of hours: enough that a seven-day card is a real seven rather than a truncated one,
     * and that the truncation case has to be built deliberately rather than falling out of a short
     * fixture.
     */
    private val forecasts = mapOf(
        Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 216).map { point(it, 10.0 + it % 10, precip = 0.4, condition = Condition.RAIN) }),
        Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 216).map { point(it, 12.0 + it % 10, precip = 0.6, condition = Condition.RAIN) }),
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = forecasts)
    private val consensus = blender.blend(forecasts)

    private fun home(now: java.time.Instant = hour(10)): HomeUiState =
        HomeStateBuilder.build(DORF_TIROL, snapshot, AppSettings(), consensus, now = now)

    @Test
    fun `today's card leads with the hero, not with the consensus hour`() {
        // The hero is whatever the home screen decided; the card must quote it rather than
        // recompute one. Forced apart here so a builder that went back to the blend would fail.
        val state = home().copy(heroTempC = 99.0)
        val card = ShareCardStateBuilder.today(state, german)!!
        assertEquals(99.0, card.tempC!!, 0.0)
    }

    @Test
    fun `a moved reading carries its adjustment`() {
        val card = ShareCardStateBuilder.today(home().copy(heroAdjustmentC = -1.9), german)!!
        assertEquals(-1.9, card.adjustmentC!!, 0.0)
    }

    @Test
    fun `a place with no station has no adjustment to declare`() {
        val state = HomeStateBuilder.build(STERZING, snapshot, AppSettings(), consensus, now = hour(10))
        assertNull(ShareCardStateBuilder.today(state, german)!!.adjustmentC)
    }

    @Test
    fun `the card names the place in the reader's language`() {
        val card = ShareCardStateBuilder.today(home(), german)!!
        assertEquals("Dorf Tirol", card.placeName)
        assertEquals("Tirolo", ShareCardStateBuilder.today(home(), Locale.ITALY)!!.placeName)
    }

    @Test
    fun `there is nothing to share before the first fetch`() {
        val empty = HomeStateBuilder.build(DORF_TIROL, WeatherSnapshot.EMPTY, AppSettings(), blender.blend(emptyMap()), now = hour(10))
        assertTrue(empty.isEmpty)
        assertNull(ShareCardStateBuilder.today(empty, german))
    }

    /**
     * The strip is eight columns wide and about one day, at any hour of that day.
     *
     * A share at nine in the evening has three hours of today left. Running on into tomorrow would
     * put two days under one date; showing three columns would leave the card half empty. So it
     * backs up into the hours already gone, and the card reads as a summary of the day.
     */
    @Test
    fun `late in the evening the strip backs up rather than running into tomorrow`() {
        val now = hour(10).atZone(ROME).withHour(21).toInstant()
        val card = ShareCardStateBuilder.today(home(now), german)!!
        assertEquals(ShareCardStateBuilder.TODAY_COLUMNS, card.hours.size)
        val dates = card.hours.map { it.time.atZone(ROME).toLocalDate() }.distinct()
        assertEquals(1, dates.size)
        assertEquals(card.date, dates.single())
    }

    @Test
    fun `earlier in the day the strip starts at the hour it is`() {
        val now = hour(10).atZone(ROME).withHour(9).toInstant()
        val card = ShareCardStateBuilder.today(home(now), german)!!
        assertEquals(ShareCardStateBuilder.TODAY_COLUMNS, card.hours.size)
        assertEquals(9, card.hours.first().time.atZone(ROME).hour)
    }

    /**
     * A thermometer speaks for the hour it measured, so a future day carries neither a reading nor
     * an adjustment — however much the hero it was built beside had of both.
     */
    @Test
    fun `a future day carries no station reading`() {
        val state = home().copy(heroAdjustmentC = -1.9, heroTempC = 99.0)
        val tomorrow = state.now.atZone(ROME).toLocalDate().plusDays(1)
        val card = ShareCardStateBuilder.day(state, tomorrow, german)!!
        assertNull(card.adjustmentC)
        assertEquals(tomorrow, card.date)
        assertEquals(state.days.first { it.date == tomorrow }.maxC, card.tempC!!, 0.0)
    }

    @Test
    fun `a future day is drawn at three-hour steps through the daylight`() {
        val tomorrow = home().now.atZone(ROME).toLocalDate().plusDays(1)
        val card = ShareCardStateBuilder.day(home(), tomorrow, german)!!
        val hours = card.hours.map { it.time.atZone(ROME).hour }
        assertEquals(listOf(6, 9, 12, 15, 18, 21), hours)
    }

    @Test
    fun `asking for today through the day entry gets today's card`() {
        val state = home().copy(heroTempC = 99.0)
        val today = state.now.atZone(ROME).toLocalDate()
        assertEquals(99.0, ShareCardStateBuilder.day(state, today, german)!!.tempC!!, 0.0)
    }

    @Test
    fun `a day the forecast does not reach cannot be shared`() {
        val far = home().now.atZone(ROME).toLocalDate().plusDays(30)
        assertNull(ShareCardStateBuilder.day(home(), far, german))
    }

    @Test
    fun `the card takes the sky it was drawn under`() {
        val state = home()
        val card = ShareCardStateBuilder.today(state, german)!!
        assertEquals(state.palette, card.palette)
        assertNotNull(card.condition)
    }

    // ---- the day ranges ----

    @Test
    fun `three days carries three rows beginning with today`() {
        val card = ShareCardStateBuilder.today(home(), german, ShareRange.THREE_DAYS)!!
        assertEquals(3, card.days.size)
        assertEquals(card.date, card.days.first().date)
        assertEquals(card.date.plusDays(2), card.days.last().date)
    }

    @Test
    fun `seven days carries seven`() {
        assertEquals(7, ShareCardStateBuilder.today(home(), german, ShareRange.SEVEN_DAYS)!!.days.size)
    }

    /**
     * One or the other, never both. A card that answered "what is this afternoon like" and "what is
     * the week like" at once would be two cards stapled together, taller than a chat preview shows.
     */
    @Test
    fun `a day range drops the hours and today drops the days`() {
        val week = ShareCardStateBuilder.today(home(), german, ShareRange.SEVEN_DAYS)!!
        assertTrue(week.hours.isEmpty())

        val today = ShareCardStateBuilder.today(home(), german, ShareRange.TODAY)!!
        assertTrue(today.days.isEmpty())
        assertTrue(today.hours.isNotEmpty())
    }

    /**
     * Each row answers for itself, which is the whole argument for showing a week: the far days are
     * built from fewer models and have to be able to say so.
     */
    @Test
    fun `each row carries its own agreement and source count`() {
        val card = ShareCardStateBuilder.today(home(), german, ShareRange.SEVEN_DAYS)!!
        val source = home().days.filter { !it.date.isBefore(card.date) }.take(7)
        assertEquals(source.map { it.sourceCount }, card.days.map { it.sourceCount })
        assertEquals(source.map { it.agreement }, card.days.map { it.agreement })
    }

    /**
     * Whatever the forecast reaches, never padded: a seventh empty row is a claim about a day
     * nobody computed.
     */
    @Test
    fun `a forecast that does not reach seven days yields only what it has`() {
        val short = home()
        val trimmed = short.copy(days = short.days.take(4))
        assertEquals(4, ShareCardStateBuilder.today(trimmed, german, ShareRange.SEVEN_DAYS)!!.days.size)
    }

    /**
     * A reader switching chips must not watch the current temperature change underneath them. The
     * hero is one answer about now, and the range is a question about how far the picture reaches.
     */
    @Test
    fun `the hero is identical across every range`() {
        val state = home().copy(heroTempC = 99.0, heroAdjustmentC = -1.9)
        val cards = ShareRange.entries.map { ShareCardStateBuilder.today(state, german, it)!! }
        assertEquals(1, cards.map { it.tempC }.distinct().size)
        assertEquals(1, cards.map { it.adjustmentC }.distinct().size)
        assertEquals(1, cards.map { it.condition }.distinct().size)
    }

    /**
     * Read from the data rather than from the arithmetic that it cannot happen inside seven days —
     * a reach that shortens upstream must not quietly turn the card into one model's opinion.
     */
    @Test
    fun `a single-model day is noticed wherever it falls`() {
        val state = home()
        val thinned = state.copy(
            days = state.days.mapIndexed { i, d -> if (i >= 5) d.copy(sourceCount = 1, ensembleHalfWidthC = null) else d },
        )
        val card = ShareCardStateBuilder.today(thinned, german, ShareRange.SEVEN_DAYS)!!
        assertTrue(card.hasSingleModelDay)
        assertFalse(card.singleModelDaysAreEnsembleBacked)

        val backed = state.copy(
            days = state.days.mapIndexed { i, d -> if (i >= 5) d.copy(sourceCount = 1, ensembleHalfWidthC = 2.0) else d },
        )
        assertTrue(ShareCardStateBuilder.today(backed, german, ShareRange.SEVEN_DAYS)!!.singleModelDaysAreEnsembleBacked)
    }

    @Test
    fun `a full week of models says nothing about thinning`() {
        assertFalse(ShareCardStateBuilder.today(home(), german, ShareRange.SEVEN_DAYS)!!.hasSingleModelDay)
    }

    @Test
    fun `there is nothing to share in any range before the first fetch`() {
        val empty = HomeStateBuilder.build(DORF_TIROL, WeatherSnapshot.EMPTY, AppSettings(), blender.blend(emptyMap()), now = hour(10))
        ShareRange.entries.forEach { assertNull(ShareCardStateBuilder.today(empty, german, it)) }
    }

    /**
     * Weather Underground's data is not licensed for redistribution, and `ShareCapture` writes a
     * PNG that goes into somebody else's chat. So a card built while the hero is an amateur reading
     * carries the models' own value for the hour instead — and no adjustment, because nothing was
     * adjusted.
     *
     * This is the one place in the feature that is deliberately switched off, and the reason is
     * legal rather than technical.
     */
    @Test
    fun `the shared card never carries an amateur reading`() {
        val consensus = blender.blend(forecasts)
        val state = HomeStateBuilder.build(
            DORF_TIROL_WITH_PWS,
            WeatherSnapshot.EMPTY.copy(
                forecasts = forecasts,
                // 1,5 K above what the models say about the station's own site, which is
                // StationDownscale.FULLY_TRUSTED_ANOMALY_C: the whole anomaly is carried, so the
                // hero is exactly the reading. A larger one is deliberately only partly shared —
                // a +12 K anomaly moves the hero by nothing at all — and a test built on one would
                // be asserting the fade rather than the share card's refusal.
                observation = observation(consensus.hourly[3].tempC + 1.5, "Tirolo - Tirol"),
                officialObservation = observation(consensus.hourly[3].tempC, "Meran"),
                stationReference = referenceFor(consensus, warmerBy = 0.0),
            ),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertTrue(state.observationIsPrivate)
        // The hero on screen is the amateur reading, carried up.
        assertEquals(consensus.hourly[3].tempC + 1.5, state.heroTempC!!, 0.01)

        val card = ShareCardStateBuilder.today(state, german)!!
        // What leaves the phone is the models' value for the hour, and no correction is claimed.
        assertEquals(consensus.hourly[3].tempC, card.tempC!!, 0.01)
        assertNull(card.adjustmentC)
    }

    /** With the province's own thermometer, nothing changes: the card carries the hero as before. */
    @Test
    fun `a provincial reading still reaches the card`() {
        val consensus = blender.blend(forecasts)
        val state = HomeStateBuilder.build(
            DORF_TIROL,
            WeatherSnapshot.EMPTY.copy(
                forecasts = forecasts,
                observation = observation(consensus.hourly[3].tempC + 1.5, "Meran"),
                officialObservation = observation(consensus.hourly[3].tempC + 1.5, "Meran"),
                stationReference = referenceFor(consensus, warmerBy = 0.0),
            ),
            AppSettings(), consensus, now = hour(3).plusSeconds(600),
        )
        assertFalse(state.observationIsPrivate)
        val card = ShareCardStateBuilder.today(state, german)!!
        assertEquals(state.heroTempC!!, card.tempC!!, 0.0)
    }

    private fun observation(temp: Double, name: String) = it.apexweather.domain.model.StationObservation(
        name, hour(3), tempC = temp, humidityPct = 40, windKmh = 5.0, windDir = "W",
        gustKmh = null, precipTodayMm = null, pressureHpa = 1010.0,
    )

    /** A reference built on the consensus the test itself blended; see HomeStateBuilderTest. */
    private fun referenceFor(c: it.apexweather.domain.model.ConsensusForecast, warmerBy: Double) =
        it.apexweather.data.remote.StationReference(
            fetchedAt = hour(3),
            elevationM = 330.0,
            bySource = mapOf(
                Source.ICON_CH1.name to c.hourly.associate { it.time.epochSecond to it.tempC + warmerBy },
                Source.ICON_D2.name to c.hourly.associate { it.time.epochSecond to it.tempC + warmerBy },
            ),
        )
}
