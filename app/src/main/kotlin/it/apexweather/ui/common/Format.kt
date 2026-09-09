package it.apexweather.ui.common

import it.apexweather.data.WindUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Format {
    fun temp(c: Double): String {
        val r = c.roundToInt()
        return if (r == 0) "0°" else "$r°"
    }
    fun tempDecimal(c: Double): String = String.format(Locale.ROOT, "%.1f°", c)
    fun wind(kmh: Double, unit: WindUnit): String = when (unit) {
        WindUnit.KMH -> "${unit.fromKmh(kmh).roundToInt()} km/h"
        WindUnit.MS -> String.format(Locale.ROOT, "%.1f m/s", unit.fromKmh(kmh))
    }
    fun windUnitLabel(unit: WindUnit): String = when (unit) {
        WindUnit.KMH -> " km/h"
        WindUnit.MS -> " m/s"
    }
    fun mm(mm: Double): String = when {
        abs(mm) < 0.05 -> "0 mm"
        mm < 10 -> String.format(Locale.ROOT, "%.1f mm", mm)
        else -> "${mm.roundToInt()} mm"
    }
    fun hour(t: Instant, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH").format(t.atZone(zone))
    fun time(t: Instant, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH:mm").format(t.atZone(zone))
    fun weekday(d: LocalDate, locale: Locale): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    fun weekdayFull(d: LocalDate, locale: Locale): String = d.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    fun dayMonth(d: LocalDate, locale: Locale): String = DateTimeFormatter.ofPattern("d. MMM", locale).format(d)
}
