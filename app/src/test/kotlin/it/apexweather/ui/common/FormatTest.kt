package it.apexweather.ui.common

import it.apexweather.data.WindUnit
import it.apexweather.domain.DorfTirol
import org.junit.Assert.assertEquals
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
    @Test fun weekday() = assertEquals("Di.", Format.weekday(LocalDate.of(2026, 9, 8), Locale.GERMAN))
}
