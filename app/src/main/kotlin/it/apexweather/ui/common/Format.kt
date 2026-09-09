package it.apexweather.ui.common

import it.apexweather.data.WindUnit
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How a number or a time is written depends on two ambient things: the reader's language, and
 * whether their phone is set to a 24-hour clock. Both are passed in explicitly so these stay pure
 * functions — [LocalFormats] carries them for composables, and the widget builds its own.
 */
data class Formats(val locale: Locale, val use24Hour: Boolean) {
    private val decimal: NumberFormat = NumberFormat.getInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    private val integer: NumberFormat = NumberFormat.getIntegerInstance(locale)

    internal fun oneDecimal(v: Double): String = decimal.format(v)
    internal fun whole(v: Int): String = integer.format(v)
}

object Format {
    fun temp(c: Double, f: Formats): String {
        val r = c.roundToInt()
        // -0 is a rounding artefact, never something to show.
        return "${f.whole(if (r == 0) 0 else r)}°"
    }

    fun tempDecimal(c: Double, f: Formats): String = "${f.oneDecimal(c)}°"

    fun wind(kmh: Double, unit: WindUnit, f: Formats): String = when (unit) {
        WindUnit.KMH -> "${f.whole(unit.fromKmh(kmh).roundToInt())} km/h"
        WindUnit.MS -> "${f.oneDecimal(unit.fromKmh(kmh))} m/s"
    }

    fun windUnitLabel(unit: WindUnit): String = when (unit) {
        WindUnit.KMH -> " km/h"
        WindUnit.MS -> " m/s"
    }

    fun mm(mm: Double, f: Formats): String = when {
        abs(mm) < 0.05 -> "${f.whole(0)} mm"
        mm < 10 -> "${f.oneDecimal(mm)} mm"
        else -> "${f.whole(mm.roundToInt())} mm"
    }

    /** Just the hour, for the columns of the hourly strip. */
    fun hour(t: Instant, zone: ZoneId, f: Formats): String =
        DateTimeFormatter.ofPattern(if (f.use24Hour) "HH" else "h a", f.locale).format(t.atZone(zone))

    fun time(t: Instant, zone: ZoneId, f: Formats): String =
        DateTimeFormatter.ofPattern(if (f.use24Hour) "HH:mm" else "h:mm a", f.locale).format(t.atZone(zone))

    /**
     * A timestamp that answers "how old is this". Within today it is the clock time alone; older
     * than that it carries its date, because data from three days ago must not be able to read as a
     * plausible time this afternoon.
     */
    fun timestamp(t: Instant, zone: ZoneId, now: Instant, f: Formats): String {
        val day = t.atZone(zone).toLocalDate()
        return if (day == now.atZone(zone).toLocalDate()) time(t, zone, f)
        else "${dayMonth(day, f)} ${time(t, zone, f)}"
    }

    fun weekday(d: LocalDate, f: Formats): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, f.locale)

    fun weekdayFull(d: LocalDate, f: Formats): String = d.dayOfWeek.getDisplayName(TextStyle.FULL, f.locale)

    /** Localised rather than the German "d. MMM": Italian wants "9 set", English "9 Sept". */
    fun dayMonth(d: LocalDate, f: Formats): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(f.locale).format(d)
}
