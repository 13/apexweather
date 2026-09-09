package it.apexweather.ui.common

import it.apexweather.data.WindUnit
import it.apexweather.domain.DorfTirol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

class FormatTest {
    private val german = Formats(Locale.GERMAN, use24Hour = true)
    private val english = Formats(Locale.UK, use24Hour = true)
    private val american = Formats(Locale.US, use24Hour = false)

    @Test fun temp() {
        assertEquals("21°", Format.temp(21.4, german))
        assertEquals("-3°", Format.temp(-2.6, german))
        // -0.2 rounds to a negative zero, which must never reach the screen.
        assertEquals("0°", Format.temp(-0.2, german))
    }

    /** The whole point of this file: German and Italian write a comma where English writes a point. */
    @Test fun `decimals follow the reader's language`() {
        assertEquals("21,4°", Format.tempDecimal(21.44, german))
        assertEquals("21.4°", Format.tempDecimal(21.44, english))
        assertEquals("0,3 mm", Format.mm(0.3, german))
        assertEquals("0.3 mm", Format.mm(0.3, english))
        assertEquals("3,4 m/s", Format.wind(12.3, WindUnit.MS, german))
        assertEquals("3.4 m/s", Format.wind(12.3, WindUnit.MS, english))
    }

    @Test fun wind() {
        assertEquals("12 km/h", Format.wind(12.3, WindUnit.KMH, german))
    }

    @Test fun mm() {
        assertEquals("0 mm", Format.mm(0.04, german))
        assertEquals("12 mm", Format.mm(12.4, german))
    }

    @Test fun `times follow the phone's clock setting`() {
        val t = Instant.parse("2026-09-08T12:20:00Z") // 14:20 in Europe/Rome
        assertEquals("14:20", Format.time(t, DorfTirol.ZONE, german))
        assertTrue("expected a 12-hour time, got ${Format.time(t, DorfTirol.ZONE, american)}",
            Format.time(t, DorfTirol.ZONE, american).startsWith("2:20"))
    }

    @Test fun hour() = assertEquals("14", Format.hour(Instant.parse("2026-09-08T12:00:00Z"), DorfTirol.ZONE, german))

    @Test fun weekday() = assertEquals("Di.", Format.weekday(LocalDate.of(2026, 9, 8), german))

    /** The pattern used to be the German "d. MMM" for every language. */
    @Test fun `dates are written the way each language writes them`() {
        val d = LocalDate.of(2026, 9, 8)
        assertNotEquals(Format.dayMonth(d, german), Format.dayMonth(d, english))
        assertTrue(Format.dayMonth(d, german).contains("8"))
        assertTrue(Format.dayMonth(d, english).contains("8"))
    }

    /** Data from days ago used to render as a bare clock time, indistinguishable from this afternoon. */
    @Test fun `timestamp is a clock time today and carries the date once it is older`() {
        val now = Instant.parse("2026-09-09T12:00:00Z")
        val earlierToday = Instant.parse("2026-09-09T06:30:00Z")
        val daysAgo = Instant.parse("2026-09-06T14:20:00Z")

        assertEquals(Format.time(earlierToday, DorfTirol.ZONE, german), Format.timestamp(earlierToday, DorfTirol.ZONE, now, german))

        val old = Format.timestamp(daysAgo, DorfTirol.ZONE, now, german)
        assertNotEquals(Format.time(daysAgo, DorfTirol.ZONE, german), old)
        assertTrue("an older timestamp must name its day: $old", old.contains("6"))
    }

    /**
     * The models do not agree on the 0 °C isotherm to better than a few hundred metres, so quoting
     * it to the metre would claim a precision nobody has.
     */
    @Test fun `a height is rounded to the nearest fifty metres`() {
        assertEquals("2.150", Format.metres(2137.0, german))
        assertEquals("2.150", Format.metres(2160.0, german))
        assertEquals("0", Format.metres(12.0, german))
    }

    @Test fun `a height is grouped the way the reader's language groups numbers`() {
        assertNotEquals(Format.metres(2150.0, german), Format.metres(2150.0, english))
    }

    @Test fun `an hour of the day follows the clock preference`() {
        val twelveHour = Formats(java.util.Locale.ENGLISH, use24Hour = false)
        assertEquals("07:00", Format.hourOfDay(7, german))
        assertTrue(Format.hourOfDay(7, twelveHour).contains("7"))
        assertNotEquals("07:00", Format.hourOfDay(7, twelveHour))
    }
}
