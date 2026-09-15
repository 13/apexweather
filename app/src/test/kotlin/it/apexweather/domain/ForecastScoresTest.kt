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
        // Both average 1 K, but ICON_CH1 hits 2 K every hour and ECMWF's misses are 0, 2.5, 0 and 1.5 K.
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

    /** Three models, each its own family, agreeing on 11 at every hour; [odd] replaces GFS's value. */
    private fun agreeing(odd: (Int) -> Double = { 11.0 }, wind: Double = 5.0) = { i: Int ->
        mapOf(
            Source.ICON_D2 to Predicted(11.0, 0.0, wind),
            Source.GEOSPHERE_AROME to Predicted(11.0, 0.0, wind),
            Source.ECMWF to Predicted(11.0, 0.0, wind),
            Source.GFS to Predicted(odd(i), 0.0, wind),
        )
    }

    /** The station and the consensus agree; one model is 16 K off. That is the model's miss, not a fault. */
    @Test
    fun `one model far off while the station and the consensus agree is scored, not excluded`() {
        val h = hours(25, { Triple(10.0, 0.0, 5.0) }, agreeing(odd = { i -> if (i == 0) 26.0 else 11.0 }))
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        val gfs = model(r, Source.GFS).score
        assertEquals(25, gfs.hours)
        assertEquals((16.0 + 24 * 1.0) / 25, gfs.main!!, 1e-9)
        assertEquals(0, r.excludedHours)
    }

    /** The station reads 40 K off from every model on one hour: that hour goes for everyone. */
    @Test
    fun `a station fault drops the hour for every model and both references`() {
        val fault = 30
        val h = hours(48, { i -> Triple(if (i == fault) 51.0 else 10.0, 0.0, 5.0) }, agreeing())
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        assertEquals(1, r.excludedHours)
        listOf(Source.ICON_D2, Source.GEOSPHERE_AROME, Source.ECMWF, Source.GFS).forEach { s ->
            assertEquals("$s", 47, model(r, s).score.hours)
            assertEquals("$s", 1.0, model(r, s).score.main!!, 1e-9)
        }
        assertEquals(47, r.references.single { it.contender == Contender.Consensus }.score.hours)
        // Hours 24..47 have a reading a day earlier; the fault hour is one of them.
        val yesterday = r.references.single { it.contender == Contender.SameAsYesterday }.score
        assertEquals(23, yesterday.hours)
        assertEquals(0.0, yesterday.main!!, 1e-9)
    }

    /** Foehn: 72 km/h at the station, 70 in the models, and one model at 8 — kept, and its miss counts. */
    @Test
    fun `a foehn hour is kept, and a model that missed it by more than the limit is charged`() {
        val h = hours(24, { i -> Triple(10.0, 0.0, if (i == 0) 72.0 else 5.0) }) { i ->
            val w = if (i == 0) 70.0 else 5.0
            mapOf(
                Source.ICON_D2 to Predicted(10.0, 0.0, w),
                Source.GEOSPHERE_AROME to Predicted(10.0, 0.0, w),
                Source.ECMWF to Predicted(10.0, 0.0, w),
                Source.GFS to Predicted(10.0, 0.0, if (i == 0) 8.0 else 5.0),
            )
        }
        val r = ForecastScores.rank(h, Quantity.WIND, lead, since)
        assertEquals(0, r.excludedHours)
        assertEquals(24, model(r, Source.ICON_D2).score.hours)
        assertEquals(2.0 / 24, model(r, Source.ICON_D2).score.main!!, 1e-9)
        assertEquals(24, model(r, Source.GFS).score.hours)
        assertEquals(64.0 / 24, model(r, Source.GFS).score.main!!, 1e-9)
    }

    @Test
    fun `a station exactly at the fault limit from the consensus is kept, just past it is dropped`() {
        // Every model says 25.0 on hour 0 against an observed 10.0: exactly TEMP_FAULT_K from the consensus.
        val kept = hours(25, { Triple(10.0, 0.0, 5.0) }, agreeing()).mapIndexed { i, hour ->
            if (i == 0) hour.copy(predicted = mapOf(lead to hour.predicted.getValue(lead).mapValues { it.value.copy(tempC = 25.0) })) else hour
        }
        val rk = ForecastScores.rank(kept, Quantity.TEMPERATURE, lead, since)
        assertEquals(0, rk.excludedHours)
        assertEquals(25, model(rk, Source.ICON_D2).score.hours)
        assertEquals(25, rk.references.single { it.contender == Contender.Consensus }.score.hours)

        val dropped = kept.mapIndexed { i, hour ->
            if (i == 0) hour.copy(predicted = mapOf(lead to hour.predicted.getValue(lead).mapValues { it.value.copy(tempC = 25.0001) })) else hour
        }
        val rd = ForecastScores.rank(dropped, Quantity.TEMPERATURE, lead, since)
        assertEquals(1, rd.excludedHours)
        assertEquals(24, model(rd, Source.ICON_D2).score.hours)
        assertEquals(24, rd.references.single { it.contender == Contender.Consensus }.score.hours)
    }

    @Test
    fun `a wind station exactly at the fault limit from the consensus is kept, just past it is dropped`() {
        val at = hours(24, { i -> Triple(10.0, 0.0, if (i == 0) 65.0 else 5.0) }, agreeing())
        assertEquals(0, ForecastScores.rank(at, Quantity.WIND, lead, since).excludedHours)
        val past = hours(24, { i -> Triple(10.0, 0.0, if (i == 0) 65.0001 else 5.0) }, agreeing())
        val r = ForecastScores.rank(past, Quantity.WIND, lead, since)
        assertEquals(1, r.excludedHours)
        assertEquals(23, model(r, Source.ICON_D2).score.hours)
    }

    @Test
    fun `a temperature miss of exactly the hit tolerance counts as a hit`() {
        // Forecast 12.0 against an observed 10.0: error exactly +2.0 K, exactly TEMP_HIT_K.
        val h = hours(24, { Triple(10.0, 0.0, 5.0) }) { mapOf(Source.ICON_D2 to Predicted(12.0, 0.0, 5.0)) }
        val s = model(ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since), Source.ICON_D2).score
        assertEquals(1.0, s.hitRate!!, 1e-9)
    }

    @Test
    fun `a wind miss of exactly the hit tolerance counts as a hit`() {
        // Observed wind is 5.0; a forecast of 10.0 misses by exactly +5.0 km/h, exactly WIND_HIT_KMH.
        val h = hours(24, { Triple(10.0, 0.0, 5.0) }) { mapOf(Source.ICON_D2 to Predicted(10.0, 0.0, 10.0)) }
        val s = model(ForecastScores.rank(h, Quantity.WIND, lead, since), Source.ICON_D2).score
        assertEquals(1.0, s.hitRate!!, 1e-9)
    }

    @Test
    fun `excludedHours counts hours, not model misses`() {
        // 25 hours, only hour 0's observation is a station fault: 25 K from the consensus of 35.
        val h = hours(25, { Triple(10.0, 0.0, 5.0) }) { i ->
            mapOf(
                Source.ICON_D2 to Predicted(if (i == 0) 40.0 else 11.0, 0.0, 5.0), // hour 0: error 30 K
                Source.GFS to Predicted(if (i == 0) 30.0 else 11.0, 0.0, 5.0), // hour 0: error 20 K
            )
        }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        assertEquals(1, r.excludedHours)
        assertEquals(24, model(r, Source.ICON_D2).score.hours)
        assertEquals(24, model(r, Source.GFS).score.hours)
    }

    @Test
    fun `the consensus is the weighted median, and same-as-yesterday reads 24 hours back`() {
        // Four ICON runs at 12, and AROME, ECMWF and GFS at 8, each in its own family: weight 0.5 for
        // each of the four ICON runs (2.0 total) against 1.0 each for the other three (3.0 total), so
        // the weighted median is 8 — where the plain median of [8,8,8,12,12,12,12] would be 12.
        val h = hours(48, { i -> Triple(if (i < 24) 9.0 else 10.0, 0.0, 5.0) }) {
            mapOf(
                Source.ICON_CH1 to Predicted(12.0, 0.0, 5.0), Source.ICON_CH2 to Predicted(12.0, 0.0, 5.0),
                Source.ICON_2I to Predicted(12.0, 0.0, 5.0), Source.ICON_D2 to Predicted(12.0, 0.0, 5.0),
                Source.GEOSPHERE_AROME to Predicted(8.0, 0.0, 5.0),
                Source.ECMWF to Predicted(8.0, 0.0, 5.0), Source.GFS to Predicted(8.0, 0.0, 5.0),
            )
        }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        val consensus = r.references.single { it.contender == Contender.Consensus }.score
        // Weighted median 8 → error -1 for the first 24 h, -2 for the last 24 h.
        assertEquals(1.5, consensus.main!!, 1e-9)
        val yesterday = r.references.single { it.contender == Contender.SameAsYesterday }.score
        // Only the last 24 hours have a reading 24 h earlier: 9 against 10 → 1 K.
        assertEquals(24, yesterday.hours)
        assertEquals(1.0, yesterday.main!!, 1e-9)
        assertTrue(r.references.none { it.rank != null })
    }

    @Test
    fun `the rain consensus is the family-weighted mean, not the plain one`() {
        // Hours 0..5 are wet and every model says so. On the 18 dry hours the four ICON runs say
        // 0.2 mm and AROME, ECMWF and GFS say 0: weights 0.5 each for ICON (2.0) against 1.0 each for
        // the other three (3.0), so the weighted mean is 0.2 × 2.0 / 5.0 = 0.08 mm, dry. The plain
        // mean, 0.8 / 7 = 0.114 mm, would be wet and call every one of those hours a false alarm.
        val icon = listOf(Source.ICON_CH1, Source.ICON_CH2, Source.ICON_2I, Source.ICON_D2)
        val others = listOf(Source.GEOSPHERE_AROME, Source.ECMWF, Source.GFS)
        val h = hours(24, { i -> Triple(10.0, if (i < 6) 1.0 else 0.0, 5.0) }) { i ->
            icon.associateWith { Predicted(10.0, if (i < 6) 1.0 else 0.2, 5.0) } +
                others.associateWith { Predicted(10.0, if (i < 6) 1.0 else 0.0, 5.0) }
        }
        val r = ForecastScores.rank(h, Quantity.RAIN, lead, since)
        val consensus = r.references.single { it.contender == Contender.Consensus }.score
        assertEquals("the dry hours are no false alarm", 0.0, consensus.lean!!, 1e-9)
        assertEquals(1.0, consensus.main!!, 1e-9)
    }

    @Test
    fun `rain at exactly the threshold survives float noise on both sides`() {
        // 0.3 - 0.2 is 0.09999999999999998 in a Double, strictly under WET_MM; both the station's
        // derived rain and a model's own value can land there.
        val noisy = 0.3 - 0.2
        val h = hours(24, { Triple(10.0, noisy, 5.0) }) { mapOf(Source.ICON_D2 to Predicted(10.0, noisy, 5.0)) }
        val s = model(ForecastScores.rank(h, Quantity.RAIN, lead, since), Source.ICON_D2).score
        // All 24 hours are hits; without the tolerance neither side would count as wet at all and main would be null.
        assertEquals(1.0, s.main!!, 1e-9)
    }

    @Test
    fun `critical success index ranks correctly where plain accuracy would not`() {
        // 30 hours, wet (1 mm) in hours 0..5. GFS never forecasts rain: right on all 24 dry hours
        // (80 % accuracy) but CSI 0. ICON_D2 also forecasts wet on 10 of the dry hours: only 66.7 %
        // accuracy (20 of 30 hours right) but CSI 6/16 = 0.375, so it must rank above GFS.
        val h = hours(30, { i -> Triple(10.0, if (i < 6) 1.0 else 0.0, 5.0) }) { i ->
            mapOf(
                Source.GFS to Predicted(10.0, 0.0, 5.0),
                Source.ICON_D2 to Predicted(10.0, if (i < 16) 1.0 else 0.0, 5.0),
            )
        }
        val r = ForecastScores.rank(h, Quantity.RAIN, lead, since)
        assertEquals(listOf(Contender.Model(Source.ICON_D2), Contender.Model(Source.GFS)), r.ranked.map { it.contender })
    }

    /** Thirty hours: the consensus has all of them, "same as yesterday" only the last six. */
    @Test
    fun `a reference row under the minimum is not shown`() {
        val h = hours(30, { i -> Triple(10.0 + i % 3, 0.0, 5.0) }) { mapOf(Source.ICON_D2 to Predicted(11.0, 0.0, 5.0)) }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        assertTrue(r.references.any { it.contender == Contender.Consensus })
        assertTrue(r.references.none { it.contender == Contender.SameAsYesterday })
        // Rain: no wet hour at all, so neither is the consensus.
        assertTrue(ForecastScores.rank(h, Quantity.RAIN, lead, since).references.isEmpty())
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
