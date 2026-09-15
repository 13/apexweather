package it.apexweather.domain

import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class VerificationHistoryTest {
    private val rome: ZoneId = ZoneId.of("Europe/Rome")

    private fun row(iso: String, total: Double?, temp: Double? = 15.0) = StationHistoryRow(
        time = Instant.parse(iso), observedC = temp, observedWindKmh = 7.2, precipTodayMm = total,
        temps = mapOf(LeadBucket.SIX to mapOf(Source.ICON_D2 to 14.0)),
        rain = mapOf(LeadBucket.SIX to mapOf(Source.ICON_D2 to 0.4)),
        wind = mapOf(LeadBucket.SIX to mapOf(Source.ICON_D2 to 9.0), LeadBucket.NOW to mapOf(Source.GFS to 5.0)),
    )

    @Test
    fun `an hour's rain is the difference of two daily totals`() {
        // 12:00 and 13:00 local on 2026-09-10 (CEST = UTC+2).
        val hours = VerificationHistory.hours(listOf(row("2026-09-10T10:00:00Z", 1.0), row("2026-09-10T11:00:00Z", 1.6)), rome)
        assertNull("no previous hour", hours[0].observedRainMm)
        assertEquals(0.6, hours[1].observedRainMm!!, 1e-9)
    }

    @Test
    fun `the first hour of a local day is its own total`() {
        // 00:00 local on 2026-09-11 is 22:00Z the day before.
        val hours = VerificationHistory.hours(listOf(row("2026-09-10T21:00:00Z", 5.0), row("2026-09-10T22:00:00Z", 0.2)), rome)
        assertEquals(0.2, hours[1].observedRainMm!!, 1e-9)
    }

    @Test
    fun `float noise in the daily totals rounds to a clean hundredth`() {
        // 0.3 - 0.2 is 0.09999999999999998 as a raw Double subtraction, not the 0.1 the station meant.
        val hours = VerificationHistory.hours(listOf(row("2026-09-10T10:00:00Z", 0.2), row("2026-09-10T11:00:00Z", 0.3)), rome)
        assertEquals(0.1, hours[1].observedRainMm!!, 0.0)
    }

    @Test
    fun `a missing hour or a falling total is not scored for rain`() {
        val gap = VerificationHistory.hours(listOf(row("2026-09-10T10:00:00Z", 1.0), row("2026-09-10T12:00:00Z", 1.6)), rome)
        assertNull(gap[1].observedRainMm)
        val reset = VerificationHistory.hours(listOf(row("2026-09-10T10:00:00Z", 1.0), row("2026-09-10T11:00:00Z", 0.4)), rome)
        assertNull(reset[1].observedRainMm)
    }

    /** 2026-03-29 02:00 local does not exist; 01:00Z is 03:00 CEST and still the same local day. */
    @Test
    fun `the spring clock change keeps the day together`() {
        val hours = VerificationHistory.hours(listOf(row("2026-03-29T00:00:00Z", 0.5), row("2026-03-29T01:00:00Z", 0.9)), rome)
        assertEquals(0.4, hours[1].observedRainMm!!, 1e-9)
    }

    /** 2026-10-25 02:00 local happens twice; both are the same local day. */
    @Test
    fun `the autumn clock change keeps the day together`() {
        val hours = VerificationHistory.hours(listOf(row("2026-10-25T00:00:00Z", 0.5), row("2026-10-25T01:00:00Z", 0.7)), rome)
        assertEquals(0.2, hours[1].observedRainMm!!, 1e-9)
    }

    @Test
    fun `the three quantities meet per lead and source`() {
        val hour = VerificationHistory.hours(listOf(row("2026-09-10T10:00:00Z", 0.0)), rome).single()
        assertEquals(Predicted(14.0, 0.4, 9.0), hour.predicted.getValue(LeadBucket.SIX).getValue(Source.ICON_D2))
        assertEquals(Predicted(null, null, 5.0), hour.predicted.getValue(LeadBucket.NOW).getValue(Source.GFS))
        assertEquals(7.2, hour.observedWindKmh!!, 1e-9)
    }
}
