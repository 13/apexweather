package it.apexweather.ui.stats

import it.apexweather.domain.Contender
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.ForecastScores
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Predicted
import it.apexweather.domain.Quantity
import it.apexweather.domain.RankedRow
import it.apexweather.domain.STERZING
import it.apexweather.domain.VerificationHour
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StatsStateBuilderTest {
    private val now: Instant = Instant.parse("2026-09-15T12:00:00Z")

    /**
     * 60 hourly-spaced recorded hours. The observation follows a seven-hour step
     * (`obs(i) = 15.0 + 1.5` when `i % 7 < 4`, else `- 1.5`) rather than a constant: a constant
     * observed temperature makes "the same hour yesterday" a perfect, zero-error predictor
     * whenever a 24-hours-earlier entry exists in the fixture, which would win the ranking on an
     * accident of the fixture rather than of the scoring. Seven does not divide 24, so the
     * yesterday lookup lands in the opposite half of the step for all but one residue mod 7.
     *
     * ICON-D2 and AROME track the observation with a fixed offset, so their miss is exactly 1 K
     * and 2 K respectively at every hour regardless of what the observation itself does; GFS
     * matches it exactly, but only for the first ten hours.
     *
     * By hand:
     * - Consensus: with three models present (i <= 10) the weighted median of {obs+1, obs, obs-2}
     *   is obs itself (GFS's own value), error 0; with two (i > 10) it is the average of obs+1
     *   and obs-2, i.e. obs - 0.5, error 0.5 — both independent of obs. Mean over 60 hours =
     *   (10 * 0 + 50 * 0.5) / 60 = 25/60 ≈ 0.417 K.
     * - ICON-D2: error is exactly 1.0 K at every hour, mean 1.0 K. AROME: exactly 2.0 K, mean 2.0 K.
     * - SameAsYesterday compares obs(i) to obs(i + 24), for i = 1..36 (the only hours with an
     *   entry 24 h back). (i + 24) mod 7 = (i mod 7 + 3) mod 7, which lands back in the same half
     *   of the step only when i mod 7 == 0 (5 of the 36 hours: i = 7, 14, 21, 28, 35 — error 0
     *   there) and in the other half for the remaining 31 (error |1.5 - (-1.5)| = 3.0 K). Mean =
     *   31 * 3.0 / 36 = 93/36 ≈ 2.583 K.
     *
     * So, ascending: Consensus (0.417 K) < ICON-D2 (1.0 K) < AROME (2.0 K) < SameAsYesterday
     * (2.583 K) — a fixed order, not merely "somewhere in the middle".
     */
    private val hours = (1..60).map { i ->
        val t = now.minusSeconds(i * 3600L)
        val obs = 15.0 + if (i % 7 < 4) 1.5 else -1.5
        VerificationHour(
            t, obs, 8.0, 0.0,
            mapOf(LeadBucket.SIX to buildMap {
                put(Source.ICON_D2, Predicted(obs + 1.0, 0.0, 8.0))
                put(Source.GEOSPHERE_AROME, Predicted(obs - 2.0, 0.0, 8.0))
                if (i <= 10) put(Source.GFS, Predicted(obs, 0.0, 8.0))
            }),
        )
    }

    @Test
    fun `rows interleave references by score and unranked go last`() {
        val s = StatsStateBuilder.build(hours, DORF_TIROL, Quantity.TEMPERATURE, StatsPeriod.MONTH, LeadBucket.SIX, now, null)
        assertTrue(s.hasStation)
        assertEquals("Meran", s.stationName)

        // Both reference rows are present, and neither carries a rank — see the fixture's comment.
        val consensusRow = s.rows.firstOrNull { it.contender == Contender.Consensus }
        val yesterdayRow = s.rows.firstOrNull { it.contender == Contender.SameAsYesterday }
        assertNotNull("Konsens row missing", consensusRow)
        assertNotNull("wie-gestern row missing", yesterdayRow)
        assertNull(consensusRow!!.rank)
        assertNull(yesterdayRow!!.rank)

        // The rows are sorted by ForecastScores.compare itself: every adjacent pair agrees with it.
        val comparator = ForecastScores.compare(Quantity.TEMPERATURE)
        s.rows.map { RankedRow(it.contender, it.score, it.rank) }.zipWithNext().forEach { (a, b) ->
            assertTrue("$a should not sort after $b", comparator.compare(a, b) <= 0)
        }

        // By hand (see the fixture's doc comment): Consensus 0.417 K < ICON-D2 1.0 K <
        // AROME 2.0 K < SameAsYesterday 2.583 K, with clear margins between all four, so the whole
        // order is fixed rather than merely bounded.
        assertEquals(
            listOf(Contender.Consensus, Contender.Model(Source.ICON_D2), Contender.Model(Source.GEOSPHERE_AROME), Contender.SameAsYesterday),
            s.rows.map { it.contender },
        )

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
