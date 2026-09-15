package it.apexweather.domain

import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ForecastScoresTest {
    private val t0: Instant = Instant.parse("2026-09-01T00:00:00Z")
    private val since: Instant = t0.minusSeconds(3600)
    private val lead = LeadBucket.SIX

    /** [n] hours; [obs] gives the observation, [models] each model's forecast, for hour i. */
    private fun hours(
        n: Int,
        obs: (Int) -> Triple<Double?, Double?, Double?>,
        models: (Int) -> Map<Source, Predicted>,
    ) = (0 until n).map { i ->
        val (t, rain, wind) = obs(i)
        VerificationHour(t0.plusSeconds(i * 3600L), t, wind, rain, mapOf(lead to models(i)))
    }

    private fun model(r: Ranking, s: Source) = (r.ranked + r.unranked).single { it.contender == Contender.Model(s) }

    @Test
    fun `temperature is ranked by average miss, with hits and lean`() {
        // ICON-D2 is always +1 K; GFS alternates +3 and -3 K, so the same lean of zero but a worse miss.
        val h = hours(24, { Triple(10.0, 0.0, 5.0) }) { i ->
            mapOf(
                Source.ICON_D2 to Predicted(11.0, 0.0, 5.0),
                Source.GFS to Predicted(if (i % 2 == 0) 13.0 else 7.0, 0.0, 5.0),
            )
        }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        assertEquals(listOf(Contender.Model(Source.ICON_D2), Contender.Model(Source.GFS)), r.ranked.map { it.contender })
        val d2 = model(r, Source.ICON_D2).score
        assertEquals(1.0, d2.main!!, 1e-9)
        assertEquals(1.0, d2.hitRate!!, 1e-9)
        assertEquals(1.0, d2.lean!!, 1e-9)
        val gfs = model(r, Source.GFS).score
        assertEquals(3.0, gfs.main!!, 1e-9)
        assertEquals(0.0, gfs.hitRate!!, 1e-9)
        assertEquals(0.0, gfs.lean!!, 1e-9)
        assertEquals(1, model(r, Source.ICON_D2).rank)
    }

    @Test
    fun `fewer than 24 hours is listed, not ranked`() {
        val h = hours(23, { Triple(10.0, 0.0, 5.0) }) { mapOf(Source.ICON_D2 to Predicted(10.0, 0.0, 5.0)) }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        assertTrue(r.ranked.isEmpty())
        assertEquals(23, model(r, Source.ICON_D2).score.hours)
        assertNull(model(r, Source.ICON_D2).rank)
    }

    /** 83 % of hours are dry here; a model that never says rain must not win on plain accuracy. */
    @Test
    fun `a model that never forecasts rain does not win`() {
        // 30 hours, wet (1 mm) in hours 0..5.
        val h = hours(30, { i -> Triple(10.0, if (i < 6) 1.0 else 0.0, 5.0) }) { i ->
            mapOf(
                Source.ICON_D2 to Predicted(10.0, if (i < 5 || i == 10) 0.8 else 0.0, 5.0), // 5 hits, 1 miss, 1 false alarm
                Source.GFS to Predicted(10.0, 0.0, 5.0), // never wet: 0 hits, 6 misses
            )
        }
        val r = ForecastScores.rank(h, Quantity.RAIN, lead, since)
        val d2 = model(r, Source.ICON_D2).score
        assertEquals(5.0 / 7.0, d2.main!!, 1e-9)   // hits / (hits + misses + false alarms)
        assertEquals(5.0 / 6.0, d2.hitRate!!, 1e-9) // detected
        assertEquals(1.0 / 6.0, d2.lean!!, 1e-9)    // false alarms
        assertEquals(Contender.Model(Source.ICON_D2), r.ranked.first().contender)
        assertEquals(0.0, model(r, Source.GFS).score.main!!, 1e-9)
    }

    @Test
    fun `rain needs five wet hours before it is ranked`() {
        val h = hours(30, { i -> Triple(10.0, if (i < 2) 1.0 else 0.0, 5.0) }) { i ->
            mapOf(Source.ICON_D2 to Predicted(10.0, if (i < 2) 1.0 else 0.0, 5.0))
        }
        val r = ForecastScores.rank(h, Quantity.RAIN, lead, since)
        assertTrue(r.ranked.isEmpty())
        assertEquals(2, model(r, Source.ICON_D2).score.wetHours)
    }

    @Test
    fun `equal misses are ordered by hits, then list order`() {
        // Both average 1 K, but ICON_CH1 hits 2 K every hour and ECMWF misses by 2.5 or 1.5 alternately.
        val h = hours(24, { Triple(10.0, 0.0, 5.0) }) { i ->
            mapOf(
                Source.ECMWF to Predicted(if (i % 2 == 0) 10.0 else 12.5, 0.0, 5.0).let { if (i % 4 == 1) it.copy(tempC = 7.5) else it }.let { if (i % 4 == 3) it.copy(tempC = 11.5) else it },
                Source.ICON_CH1 to Predicted(11.0, 0.0, 5.0),
                Source.ICON_CH2 to Predicted(11.0, 0.0, 5.0),
            )
        }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        assertEquals(
            listOf(Contender.Model(Source.ICON_CH1), Contender.Model(Source.ICON_CH2), Contender.Model(Source.ECMWF)),
            r.ranked.map { it.contender },
        )
    }

    @Test
    fun `an absurd miss is excluded and counted`() {
        val h = hours(25, { Triple(10.0, 0.0, 5.0) }) { i -> mapOf(Source.ICON_D2 to Predicted(if (i == 0) 40.0 else 11.0, 0.0, 5.0)) }
        val s = model(ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since), Source.ICON_D2).score
        assertEquals(24, s.hours)
        assertEquals(1, s.excluded)
        assertEquals(1.0, s.main!!, 1e-9)
    }

    @Test
    fun `the consensus is the weighted median, and same-as-yesterday reads 24 hours back`() {
        // Four ICON runs at 12 and AROME at 8: the four share one core (weight 0.5 each = 2.0 against 1.0).
        val h = hours(48, { i -> Triple(if (i < 24) 9.0 else 10.0, 0.0, 5.0) }) {
            mapOf(
                Source.ICON_CH1 to Predicted(12.0, 0.0, 5.0), Source.ICON_CH2 to Predicted(12.0, 0.0, 5.0),
                Source.ICON_2I to Predicted(12.0, 0.0, 5.0), Source.ICON_D2 to Predicted(12.0, 0.0, 5.0),
                Source.GEOSPHERE_AROME to Predicted(8.0, 0.0, 5.0),
            )
        }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        val consensus = r.references.single { it.contender == Contender.Consensus }.score
        // Weighted median 12 → error +3 for the first 24 h, +2 for the last 24 h.
        assertEquals(2.5, consensus.main!!, 1e-9)
        val yesterday = r.references.single { it.contender == Contender.SameAsYesterday }.score
        // Only the last 24 hours have a reading 24 h earlier: 9 against 10 → 1 K.
        assertEquals(24, yesterday.hours)
        assertEquals(1.0, yesterday.main!!, 1e-9)
        assertTrue(r.references.none { it.rank != null })
    }

    @Test
    fun `rain has no same-as-yesterday row`() {
        val h = hours(48, { i -> Triple(10.0, if (i % 6 == 0) 1.0 else 0.0, 5.0) }) { mapOf(Source.ICON_D2 to Predicted(10.0, 1.0, 5.0)) }
        assertTrue(ForecastScores.rank(h, Quantity.RAIN, lead, since).references.none { it.contender == Contender.SameAsYesterday })
    }

    @Test
    fun `KMOS and hours before the period are left out`() {
        val h = hours(30, { Triple(10.0, 0.0, 5.0) }) { mapOf(Source.SIAG_KMOS to Predicted(10.0, 0.0, 5.0), Source.ICON_D2 to Predicted(10.0, 0.0, 5.0)) }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, t0.plusSeconds(10 * 3600L))
        assertTrue((r.ranked + r.unranked).none { it.contender == Contender.Model(Source.SIAG_KMOS) })
        assertEquals(20, model(r, Source.ICON_D2).score.hours)
        assertEquals(20, r.observedHours)
        assertEquals(t0.plusSeconds(10 * 3600L), r.firstHour)
    }

    @Test
    fun `part of day and daily error`() {
        val rome = ZoneId.of("Europe/Rome")
        val h = hours(48, { Triple(10.0, 0.0, 5.0) }) { i -> mapOf(Source.ICON_D2 to Predicted(if (i < 24) 11.0 else 13.0, 0.0, 5.0)) }
        val parts = ForecastScores.byPart(h, Quantity.TEMPERATURE, lead, since, Source.ICON_D2, rome)
        assertEquals(DayPart.entries.toSet(), parts.keys)
        val daily = ForecastScores.dailyError(h, Quantity.TEMPERATURE, lead, since, Source.ICON_D2, rome)
        assertTrue(daily.isNotEmpty())
        assertTrue(ForecastScores.dailyError(h, Quantity.RAIN, lead, since, Source.ICON_D2, rome).isEmpty())
    }
}
