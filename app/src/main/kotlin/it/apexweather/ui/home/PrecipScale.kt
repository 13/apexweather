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

    /**
     * The same track, for an hour that arrives frozen: ten centimetres of snow fills it.
     *
     * Ten, so that the two scales meet at roughly the same weather. A centimetre of ordinary snow
     * melts down to about a millimetre of water, so ten centimetres and ten millimetres are the same
     * hour twice, and a full bar goes on meaning "as much as this gets" in either season.
     *
     * The alternative was to leave the bar on the water equivalent and change only the number
     * underneath, which has the advantage of one scale for the whole strip and the fatal defect that
     * an hour bringing four centimetres of snow would draw a fifth of the track. Four centimetres is
     * not a fifth of anything a reader here cares about; it is the difference between driving and
     * not. The bar says how much weather, and snow at the same water equivalent is more weather.
     */
    const val FULL_SCALE_CM = 10.0

    /**
     * Half a centimetre. Below it the hour is frozen drizzle and the millimetres are the more honest
     * number, so the bar falls back to them rather than printing "0,2 cm" of nothing.
     */
    const val MIN_PRINTED_CM = 0.5

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

    /** The same, against [FULL_SCALE_CM], for an hour drawn as snow. */
    fun snowFillFraction(cm: Double): Float =
        sqrt((cm / FULL_SCALE_CM).coerceIn(0.0, 1.0)).toFloat()

    /** True once there is enough rain to draw a bar and print a number for it. */
    fun hasAmount(mm: Double): Boolean = mm >= MIN_PRINTED_MM

    /**
     * Whether this hour is better told in centimetres of snow than in millimetres of water.
     *
     * Both halves are required. The condition, because a model publishing a trace of snowfall on a
     * rainy hour must not turn the column white; and the amount, because there is no point printing
     * a centimetre figure smaller than the number it is measured to.
     */
    fun showsSnow(snowCm: Double?, condition: Condition): Boolean =
        condition.isFrozen && snowCm != null && snowCm >= MIN_PRINTED_CM

    /**
     * Colour of the fill. It repeats what the height says, which is deliberate — it is what makes a
     * heavy hour findable while scrolling — but frozen precipitation is never painted as rain,
     * whatever the amount.
     */
    fun fillColor(mm: Double, condition: Condition): Color = when {
        condition.isFrozen -> FROZEN
        mm < MODERATE_FROM_MM -> LIGHT
        mm < HEAVY_FROM_MM -> MODERATE
        else -> HEAVY
    }
}
