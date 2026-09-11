package it.apexweather.ui.map

import androidx.compose.ui.graphics.Color

/**
 * The one intensity ramp the map speaks in, for the radar behind and the forecast in front.
 *
 * The radar tiles are painted by RainViewer in its colour scheme 4, which the app does not choose
 * the stops of. These are that scheme's own colours, read off live tiles over the eastern Alps on
 * 2026-09-11 — light blue through deep blue for rain, then orange and red where it is heavy. The
 * forecast overlay is drawn by this app, so it is given the same colours deliberately: two rain
 * layers on one map that disagreed about what blue means would be unreadable.
 *
 * The stops carry **millimetres per hour**, because that is what the forecast publishes and what a
 * reader can act on. They are not claimed as a translation of the radar's own scale: RainViewer does
 * not publish the reflectivity its scheme 4 maps each colour to, so the legend says light, moderate
 * and heavy rather than putting a number against a colour the app did not choose.
 */
object PrecipColors {

    /** Rate in mm/h, and the colour at or above it. Ordered. */
    private val STOPS = listOf(
        0.1 to Color(0xFF88DDEE),
        0.5 to Color(0xFF36BAE5),
        1.0 to Color(0xFF00A3E0),
        2.0 to Color(0xFF0088BF),
        4.0 to Color(0xFF005B8E),
        8.0 to Color(0xFFFF4400),
        16.0 to Color(0xFFC10000),
        32.0 to Color(0xFF5D0000),
    )

    /** What the legend draws, light to heavy. */
    val RAMP: List<Color> = STOPS.map { it.second }

    /** The colour for a rate in millimetres per hour. Below the first stop nothing is drawn. */
    fun forRate(mmPerHour: Double): Color? {
        if (mmPerHour < STOPS.first().first) return null
        return STOPS.last { mmPerHour >= it.first }.second
    }
}
