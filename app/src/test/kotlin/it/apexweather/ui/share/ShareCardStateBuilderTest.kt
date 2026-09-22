package it.apexweather.ui.share

import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DORF_TIROL
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

    /** Three days of hours, so a card for tomorrow has something to draw. */
    private val forecasts = mapOf(
        Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 72).map { point(it, 10.0 + it % 10, precip = 0.4, condition = Condition.RAIN) }),
        Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 72).map { point(it, 12.0 + it % 10, precip = 0.6, condition = Condition.RAIN) }),
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
}
