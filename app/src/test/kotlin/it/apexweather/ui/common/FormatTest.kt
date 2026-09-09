package it.apexweather.ui.common

import it.apexweather.data.WindUnit
import it.apexweather.domain.DorfTirol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

class FormatTest {
    @Test fun temp() { assertEquals("21°", Format.temp(21.4)); assertEquals("-3°", Format.temp(-2.6)); assertEquals("0°", Format.temp(-0.2)) }
    @Test fun tempDecimal() = assertEquals("21.4°", Format.tempDecimal(21.44))
    @Test fun wind() { assertEquals("12 km/h", Format.wind(12.3, WindUnit.KMH)); assertEquals("3.4 m/s", Format.wind(12.3, WindUnit.MS)) }
    @Test fun windUnitLabel() { assertEquals(" km/h", Format.windUnitLabel(WindUnit.KMH)); assertEquals(" m/s", Format.windUnitLabel(WindUnit.MS)) }
    @Test fun mm() { assertEquals("0 mm", Format.mm(0.04)); assertEquals("0.3 mm", Format.mm(0.3)); assertEquals("12 mm", Format.mm(12.4)) }
    @Test fun hour() = assertEquals("14", Format.hour(Instant.parse("2026-09-08T12:00:00Z"), DorfTirol.ZONE))
    @Test fun time() = assertEquals("14:20", Format.time(Instant.parse("2026-09-08T12:20:00Z"), DorfTirol.ZONE))
    /** Data from days ago used to render as a bare clock time, indistinguishable from this afternoon. */
    @Test fun `timestamp is a clock time today and carries the date once it is older`() {
        val now = Instant.parse("2026-09-09T12:00:00Z")
        val earlierToday = Instant.parse("2026-09-09T06:30:00Z")
        val daysAgo = Instant.parse("2026-09-06T14:20:00Z")

        assertEquals(Format.time(earlierToday, DorfTirol.ZONE), Format.timestamp(earlierToday, DorfTirol.ZONE, now, Locale.GERMAN))

        val old = Format.timestamp(daysAgo, DorfTirol.ZONE, now, Locale.GERMAN)
        assertNotEquals(Format.time(daysAgo, DorfTirol.ZONE), old)
        assertTrue("an older timestamp must name its day: $old", old.contains("6"))
    }

    @Test fun weekday() = assertEquals("Di.", Format.weekday(LocalDate.of(2026, 9, 8), Locale.GERMAN))
}
