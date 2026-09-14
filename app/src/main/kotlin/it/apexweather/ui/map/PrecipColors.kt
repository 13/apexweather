package it.apexweather.ui.map

import androidx.compose.ui.graphics.Color
import it.apexweather.domain.RadarAtPlace

/**
 * The one intensity ramp the map speaks in, for the radar behind and the forecast in front.
 *
 * The radar tiles arrive in RainViewer's **Universal Blue** scheme, and RainViewer publishes what
 * every colour of it means in dBZ (see `RadarColorTable`). These stops are that scheme's colours at
 * 15, 18, 20, 23, 29, 45, 50 and 54 dBZ, and each is placed at the rate Marshall–Palmer gives for
 * its dBZ. They used to carry guessed rates — 0,1 mm/h for the first blue, 8 for orange — which put
 * the forecast about three times wetter than the radar at the same colour: on 2026-09-14 INCA's
 * drizzle over Dorf Tirol arrived painted like the radar's rain.
 *
 * Marshall–Palmer is the stratiform relation and an approximation, so the legend stays in words.
 */
object PrecipColors {

    /** Below this a forecast cell is not drawn at all. */
    const val DRAWN_FROM_MM = 0.1

    /** 15 dBZ: the first colour the radar draws as rain. */
    const val RAIN_FROM_MM = 0.3

    /** 29 dBZ. */
    const val MODERATE_FROM_MM = 2.4

    /** 45 dBZ, where the radar turns orange. */
    const val HEAVY_FROM_MM = 24.0

    /** The radar's 0-14 dBZ wash, at 10 dBZ: an echo, not rain. */
    val SUB_RAIN = Color(0x96CEC087)

    private val STOPS: List<Pair<Double, Color>> = listOf(
        15 to Color(0xFF88DDEE),
        18 to Color(0xFF36BAE5),
        20 to Color(0xFF00A3E0),
        23 to Color(0xFF0088BF),
        29 to Color(0xFF005B8E),
        45 to Color(0xFFFF4400),
        50 to Color(0xFFC10000),
        54 to Color(0xFF5D0000),
    ).map { (dbz, colour) -> RadarAtPlace.rateOf(dbz) to colour }

    /** What the legend draws, light to heavy. Rain only; the wash is not on it. */
    val RAMP: List<Color> = STOPS.map { it.second }

    fun isRain(mmPerHour: Double): Boolean = mmPerHour >= STOPS.first().first

    /** The colour for a rate in millimetres per hour: nothing below [DRAWN_FROM_MM], the wash below rain. */
    fun forRate(mmPerHour: Double): Color? = when {
        mmPerHour < DRAWN_FROM_MM -> null
        !isRain(mmPerHour) -> SUB_RAIN
        else -> STOPS.last { mmPerHour >= it.first }.second
    }
}
