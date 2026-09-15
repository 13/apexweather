package it.apexweather.ui.compare

import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * [sheetStamp] against [Format.dayTime] and [Format.timestamp] themselves, not hard-coded strings —
 * the point under test is which one gets picked, not what German renders as.
 */
class SheetStampTest {
    private val formats = Formats(Locale.GERMAN, use24Hour = true)
    private val now = Instant.parse("2026-09-15T12:00:00Z")

    @Test
    fun aFewHoursAgoReadsAsDayTime() {
        val t = now.minus(Duration.ofHours(2))
        assertEquals(Format.dayTime(t, SouthTyrol.ZONE, now, formats), sheetStamp(t, now, formats))
    }

    @Test
    fun fiveDaysAgoStillReadsAsDayTime() {
        val t = now.minus(Duration.ofDays(5))
        assertEquals(Format.dayTime(t, SouthTyrol.ZONE, now, formats), sheetStamp(t, now, formats))
    }

    @Test
    fun thirtyDaysAgoReadsAsTheDatedTimestamp() {
        val t = now.minus(Duration.ofDays(30))
        assertEquals(Format.timestamp(t, SouthTyrol.ZONE, now, formats), sheetStamp(t, now, formats))
    }

    /** sheetStamp does not clamp itself; a caller that does (minOf(t, now)) gets dayTime's "now". */
    @Test
    fun aFutureTimeIsWhateverTheCallerClampedItTo() {
        val future = now.plus(Duration.ofHours(3))
        val clamped = minOf(future, now)
        assertEquals(now, clamped)
        assertEquals(Format.dayTime(clamped, SouthTyrol.ZONE, now, formats), sheetStamp(clamped, now, formats))
    }
}
