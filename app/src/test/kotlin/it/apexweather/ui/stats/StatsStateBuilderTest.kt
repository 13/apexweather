package it.apexweather.ui.stats

import it.apexweather.domain.Contender
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Predicted
import it.apexweather.domain.Quantity
import it.apexweather.domain.STERZING
import it.apexweather.domain.VerificationHour
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StatsStateBuilderTest {
    private val now: Instant = Instant.parse("2026-09-15T12:00:00Z")

    /**
     * 60 recorded hours, spaced five hours apart so no two are exactly 24 h apart (gcd(5, 24) = 1)
     * — a constant observed temperature would otherwise make "the same hour yesterday" a perfect,
     * zero-error predictor and win the ranking on an accident of the fixture rather than of the
     * scoring. ICON-D2 misses by 1 K, AROME by 2 K, GFS only has 10 hours.
     */
    private val hours = (1..60).map { i ->
        val t = now.minusSeconds(i * 5 * 3600L)
        VerificationHour(
            t, 15.0, 8.0, 0.0,
            mapOf(LeadBucket.SIX to buildMap {
                put(Source.ICON_D2, Predicted(16.0, 0.0, 8.0))
                put(Source.GEOSPHERE_AROME, Predicted(13.0, 0.0, 8.0))
                if (i <= 10) put(Source.GFS, Predicted(15.0, 0.0, 8.0))
            }),
        )
    }

    @Test
    fun `rows interleave references by score and unranked go last`() {
        val s = StatsStateBuilder.build(hours, DORF_TIROL, Quantity.TEMPERATURE, StatsPeriod.MONTH, LeadBucket.SIX, now, null)
        assertTrue(s.hasStation)
        assertEquals("Meran", s.stationName)
        val order = s.rows.map { it.contender }
        // The consensus of 16 and 13 is 14,5, half a kelvin off, so it sits above ICON-D2 unranked.
        assertEquals(Contender.Consensus, order.first())
        assertNull(s.rows.first().rank)
        val firstRanked = s.rows.first { it.rank != null }
        assertEquals(Contender.Model(Source.ICON_D2), firstRanked.contender)
        assertEquals(1, firstRanked.rank)
        assertEquals(listOf(Contender.Model(Source.GFS)), s.unranked.map { it.contender })
        assertEquals(60, s.observedHours)
    }

    @Test
    fun `a place without a station says so and has no rows`() {
        val s = StatsStateBuilder.build(emptyList(), STERZING, Quantity.TEMPERATURE, StatsPeriod.MONTH, LeadBucket.SIX, now, null)
        assertFalse(s.hasStation)
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun `the card shows the top three models and the source ranks follow the same ranking`() {
        val card = StatsStateBuilder.card(hours, DORF_TIROL, now)
        assertTrue(card.enoughData)
        assertEquals(listOf(Contender.Model(Source.ICON_D2), Contender.Model(Source.GEOSPHERE_AROME)), card.top.map { it.contender })
        val ranks = StatsStateBuilder.sourceRanks(hours, now)
        assertEquals(1, ranks.getValue(Source.ICON_D2).rank)
        assertEquals(2, ranks.getValue(Source.ICON_D2).of)
        assertNull(ranks.getValue(Source.GFS).rank)
    }

    @Test
    fun `an empty history is not enough data`() {
        assertFalse(StatsStateBuilder.card(emptyList(), DORF_TIROL, now).enoughData)
    }

    @Test
    fun `the detail carries parts of the day and daily error`() {
        val s = StatsStateBuilder.build(hours, DORF_TIROL, Quantity.TEMPERATURE, StatsPeriod.MONTH, LeadBucket.SIX, now, Source.ICON_D2)
        val d = s.detail!!
        assertEquals(Source.ICON_D2, d.source)
        assertEquals(4, d.byPart.size)
        assertTrue(d.daily.isNotEmpty())
    }
}
