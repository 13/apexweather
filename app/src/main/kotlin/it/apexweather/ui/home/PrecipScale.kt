package it.apexweather.ui.home

import androidx.compose.ui.graphics.Color
import it.apexweather.domain.model.Condition
import kotlin.math.sqrt

/**
 * What the precipitation bar under each hour of the 48-hour strip draws.
 *
 * The bar is millimetres, the number under it is the same millimetres, and the number under that is
 * the probability. Three facts, in the order a reader wants them: how much, exactly how much, and
 * how sure.
 *
 * **The scale is fixed and square-rooted.** Two other scales were considered and are worse:
 *
 * - *Linear against a fixed maximum* is what this was, and 0-5 mm linear drew 1.8 dp of bar for an
 *   hour certain to bring 0,4 mm. Real hours here are mostly under a millimetre, so a linear scale
 *   spends its whole height on rain that hardly ever falls.
 * - *Scaling to the largest hour on screen* always fills the track, which looks well used and lies:
 *   the same height would mean different rain on different days, and a dry day would magnify 0,2 mm
 *   to full height and read as a downpour.
 *
 * The square root of the fraction of [FULL_SCALE_MM] keeps both properties that matter. A given
 * height always means the same rain, and the bottom of the range is stretched enough that drizzle is
 * visible: 0,2 mm draws 14 % of the track, 1 mm a third, 10 mm all of it. Past the cap the bar is
 * simply full, and the millimetres printed directly underneath say the rest.
 */
object PrecipScale {

    /** A full bar. Ten millimetres in an hour is a downpour; more just stays full. */
    const val FULL_SCALE_MM = 10.0

    /** Below this there is no amount to draw and none to print. Absence must never read as zero. */
    const val MIN_PRINTED_MM = 0.1

    /** Millimetres in the hour at which the fill steps up a shade. */
    private const val MODERATE_FROM_MM = 0.5
    private const val HEAVY_FROM_MM = 2.5

    val TRACK = Color(0x1FFFFFFF)
    val LIGHT = Color(0xFF7FB2FF)
    val MODERATE = Color(0xFF4A90E8)
    val HEAVY = Color(0xFF3457C8)
    val FROZEN = Color(0xFFDDEBFF)

    /** How much of the track to fill, 0..1. */
    fun fillFraction(mm: Double): Float =
        sqrt((mm / FULL_SCALE_MM).coerceIn(0.0, 1.0)).toFloat()

    /** True once there is enough rain to draw a bar and print a number for it. */
    fun hasAmount(mm: Double): Boolean = mm >= MIN_PRINTED_MM

    /**
     * Colour of the fill. It repeats what the height says, which is deliberate — it is what makes a
     * heavy hour findable while scrolling — but frozen precipitation is never painted as rain,
     * whatever the amount.
     */
    fun fillColor(mm: Double, condition: Condition): Color = when {
        condition == Condition.SNOW || condition == Condition.HEAVY_SNOW || condition == Condition.SLEET -> FROZEN
        mm < MODERATE_FROM_MM -> LIGHT
        mm < HEAVY_FROM_MM -> MODERATE
        else -> HEAVY
    }
}
