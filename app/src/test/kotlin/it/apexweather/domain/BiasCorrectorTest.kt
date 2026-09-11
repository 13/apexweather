package it.apexweather.domain

import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A weather station is the only ground truth this app has, and this is what it is for: over a few
 * days the difference between what a model said and what the thermometer read stops being noise and
 * starts being that model's habit in this valley.
 *
 * Every guard here exists because a correction applied on thin evidence does harm.
 */
class BiasCorrectorTest {

    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val now: Instant = Instant.parse("2026-09-10T12:00:00Z")

    /**
     * [hour] o'clock local, on each of the [days] days before today. One part of the day and one
     * lead time only, so a test says exactly which cell it is filling.
     */
    private fun samplesAt(
        hour: Int,
        days: Int,
        error: Double,
        source: Source = Source.ICON_D2,
        observed: Double = 10.0,
        lead: LeadBucket = LeadBucket.NOW,
    ) = (1..days).map { d ->
        val time = LocalDate.of(2026, 9, 10).minusDays(d.toLong())
            .atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant()
        StationSample(time, observed, mapOf(lead to mapOf(source to observed + error)))
    }

    /** An instant at [hour] o'clock local, to read a bias back at. */
    private fun at(hour: Int): Instant =
        LocalDate.of(2026, 9, 10).atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant()

    private fun biasAt(
        samples: List<StationSample>,
        hour: Int,
        source: Source = Source.ICON_D2,
        leadHours: Long = 0,
    ) = BiasCorrector.biases(samples, now, zone).at(source, at(hour), zone, leadHours)

    @Test
    fun `a model that runs consistently warm is measured as warm`() {
        assertEquals(1.5, biasAt(samplesAt(hour = 14, days = 12, error = 1.5), hour = 14)!!, 1e-9)
    }

    @Test
    fun `a model that runs cold comes out negative`() {
        assertEquals(-0.8, biasAt(samplesAt(hour = 14, days = 12, error = -0.8), hour = 14)!!, 1e-9)
    }

    /** Two hours of agreement is luck, not a habit. */
    @Test
    fun `too few hours means no opinion at all`() {
        val samples = samplesAt(hour = 14, days = BiasCorrector.MIN_SAMPLES - 1, error = 2.0)
        assertNull("a handful of hours must not produce a correction", biasAt(samples, hour = 14))
    }

    @Test
    fun `exactly enough hours is enough`() {
        val samples = samplesAt(hour = 14, days = BiasCorrector.MIN_SAMPLES, error = 2.0)
        assertEquals(2.0, biasAt(samples, hour = 14)!!, 1e-9)
    }

    /** Beyond a few degrees the input is broken rather than biased, and moving the forecast a long
     * way on bad evidence is worse than leaving it alone. */
    @Test
    fun `an implausible error is refused rather than applied`() {
        assertNull(biasAt(samplesAt(hour = 14, days = 12, error = 9.0), hour = 14))
    }

    @Test
    fun `hours outside the window are not counted`() {
        val old = (1..12).map { i ->
            StationSample(
                now.minus(BiasCorrector.WINDOW).minusSeconds(i * 86_400L),
                10.0,
                mapOf(LeadBucket.NOW to mapOf(Source.ICON_D2 to 12.0)),
            )
        }
        assertNull(BiasCorrector.biases(old, now, zone).at(Source.ICON_D2, at(14), zone, leadHours = 0))
    }

    /** Each model is judged on its own record; one absent from an hour is not scored for it. */
    @Test
    fun `models are measured separately`() {
        val mixed = (1..12).map { d ->
            val time = LocalDate.of(2026, 9, 10).minusDays(d.toLong())
                .atTime(LocalTime.of(14, 0)).atZone(zone).toInstant()
            StationSample(
                time, 10.0,
                mapOf(LeadBucket.NOW to mapOf(Source.ICON_D2 to 11.0, Source.ECMWF to 8.0)),
            )
        }
        val bias = BiasCorrector.biases(mixed, now, zone)
        assertEquals(1.0, bias.at(Source.ICON_D2, at(14), zone, leadHours = 0)!!, 1e-9)
        assertEquals(-2.0, bias.at(Source.ECMWF, at(14), zone, leadHours = 0)!!, 1e-9)
        assertNull(bias.at(Source.SIAG_KMOS, at(14), zone, leadHours = 0))
    }

    /**
     * The reason the day is split at all.
     *
     * A model that runs two degrees warm every afternoon and two degrees cold every night has a
     * mean error of zero, and a single number over the week reports it as the best model on the
     * list. That is the error shape a model actually has in an alpine valley — it mixes the column
     * too readily by day and does not pool cold air on the floor at night — so the one average that
     * cancels is the one that matters.
     */
    @Test
    fun `a model warm by day and cold by night is not called unbiased`() {
        val samples = samplesAt(hour = 14, days = 10, error = 2.0) +
            samplesAt(hour = 3, days = 10, error = -2.0)
        assertEquals(2.0, biasAt(samples, hour = 14)!!, 1e-9)
        assertEquals(-2.0, biasAt(samples, hour = 3)!!, 1e-9)
    }

    /** Evidence about the afternoon says nothing about the small hours. */
    @Test
    fun `a part of the day with no evidence has no opinion, even when another part does`() {
        val samples = samplesAt(hour = 14, days = 10, error = 2.0)
        assertEquals(2.0, biasAt(samples, hour = 14)!!, 1e-9)
        assertNull(biasAt(samples, hour = 3))
        assertNull(biasAt(samples, hour = 9))
    }

    /**
     * The second axis, and the reason the record is kept at all.
     *
     * A model's error on the hour that has just happened is very nearly its error on a measurement;
     * its error half a day out is a different quantity and is the one the app actually applies. A
     * record of the first says nothing about the second.
     */
    @Test
    fun `a habit measured at lead zero is not applied half a day out`() {
        val samples = samplesAt(hour = 14, days = 10, error = 2.0, lead = LeadBucket.NOW)
        assertEquals(2.0, biasAt(samples, hour = 14, leadHours = 0)!!, 1e-9)
        assertNull(biasAt(samples, hour = 14, leadHours = 12))
    }

    @Test
    fun `a habit measured twelve hours out is what is applied twelve hours out`() {
        val samples = samplesAt(hour = 14, days = 10, error = 2.0, lead = LeadBucket.TWELVE)
        assertEquals(2.0, biasAt(samples, hour = 14, leadHours = 12)!!, 1e-9)
        assertNull(biasAt(samples, hour = 14, leadHours = 0))
    }

    /**
     * The same hour can be wrong by different amounts depending on how far ahead it was asked, and
     * usually is: a model that nails this afternoon from three hours away can still be two degrees
     * out about it from half a day away.
     */
    @Test
    fun `each lead time keeps its own record`() {
        val samples = samplesAt(hour = 14, days = 10, error = 0.2, lead = LeadBucket.NOW) +
            samplesAt(hour = 14, days = 10, error = 2.0, lead = LeadBucket.TWELVE)
        assertEquals(0.2, biasAt(samples, hour = 14, leadHours = 1)!!, 1e-9)
        assertEquals(2.0, biasAt(samples, hour = 14, leadHours = 12)!!, 1e-9)
    }

    @Test
    fun `the lead buckets cover the hours between them`() {
        assertEquals(LeadBucket.NOW, LeadBucket.judging(0))
        assertEquals(LeadBucket.NOW, LeadBucket.judging(3))
        assertEquals(LeadBucket.SIX, LeadBucket.judging(4))
        assertEquals(LeadBucket.SIX, LeadBucket.judging(8))
        assertEquals(LeadBucket.TWELVE, LeadBucket.judging(9))
        // Past the furthest the app verifies the furthest record is still the nearest thing to
        // evidence; correctionAt is what stops it being trusted whole.
        assertEquals(LeadBucket.TWELVE, LeadBucket.judging(30))
    }

    @Test
    fun `the day is cut into six-hour parts, in the place's own time zone`() {
        assertEquals(DayPart.NIGHT, DayPart.of(at(0), zone))
        assertEquals(DayPart.NIGHT, DayPart.of(at(5), zone))
        assertEquals(DayPart.MORNING, DayPart.of(at(6), zone))
        assertEquals(DayPart.AFTERNOON, DayPart.of(at(12), zone))
        assertEquals(DayPart.EVENING, DayPart.of(at(18), zone))
        assertEquals(DayPart.EVENING, DayPart.of(at(23), zone))
        // Local, not UTC: 23:00 local in September is 21:00 UTC, and it is the evening here.
        assertEquals(DayPart.EVENING, DayPart.of(Instant.parse("2026-09-10T21:00:00Z"), zone))
    }

    /**
     * The correction holds out to where the record reaches and fades past it.
     *
     * It used to start fading at three hours, because the record was an analysis standing in for a
     * forecast error. Now that the twelve-hour bucket holds what the models really said twelve
     * hours out, applying it at twelve hours needs no discount; what still needs one is the stretch
     * past the last hour anyone checked.
     */
    @Test
    fun `the correction holds to where the record reaches, then fades`() {
        assertEquals(2.0, BiasCorrector.correctionAt(2.0, leadHours = 0), 1e-9)
        assertEquals(2.0, BiasCorrector.correctionAt(2.0, leadHours = 12), 1e-9)
        assertEquals(2.0, BiasCorrector.correctionAt(2.0, leadHours = BiasCorrector.FULL_STRENGTH_HOURS), 1e-9)
        assertEquals(0.0, BiasCorrector.correctionAt(2.0, leadHours = BiasCorrector.NO_STRENGTH_HOURS), 1e-9)
        assertEquals(0.0, BiasCorrector.correctionAt(2.0, leadHours = 48), 1e-9)

        val middle = BiasCorrector.correctionAt(2.0, leadHours = 20)
        assertTrue("$middle should sit between", middle > 0.0 && middle < 2.0)
    }

    @Test
    fun `no measured bias means no correction, so a caller may always subtract`() {
        assertEquals(0.0, BiasCorrector.correctionAt(null, leadHours = 1), 0.0)
    }
}
