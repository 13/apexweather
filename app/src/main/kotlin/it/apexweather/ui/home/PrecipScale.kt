package it.apexweather.ui.home

import androidx.compose.ui.graphics.Color
import it.apexweather.domain.model.Condition

/**
 * What the precipitation bar under each hour of the 48-hour strip draws.
 *
 * Height is probability and colour is intensity, because the two used to be one number. The bar was
 * millimetres on a fixed 0-5 mm scale, so an hour certain to bring 0,4 mm drew 1.8 dp of bar under a
 * caption reading 100 %: the reader saw a number saying *certain* above a bar saying *nothing*, and
 * nothing on the card said which of the two the bar was.
 *
 * Probability fills a track that is always the same size, so every hour is comparable and a small
 * chance reads as small rather than as absent. The millimetres moved to the caption, where they are
 * a different fact from the one the bar carries rather than a second drawing of the same one.
 *
 * Kept out of the composable so it can be tested without a screen.
 */
object PrecipScale {

    /** Below this a chance is not worth drawing, and the track is left empty. */
    const val MIN_DRAWN_PERCENT = 5

    /** Below this there is no amount to print. Absence must never read as a zero. */
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
    fun fillFraction(probPercent: Int): Float = (probPercent / 100f).coerceIn(0f, 1f)

    fun isDrawn(probPercent: Int): Boolean = probPercent >= MIN_DRAWN_PERCENT

    fun hasAmount(mm: Double): Boolean = mm >= MIN_PRINTED_MM

    fun fillColor(mm: Double, condition: Condition): Color = when {
        condition == Condition.SNOW || condition == Condition.HEAVY_SNOW || condition == Condition.SLEET -> FROZEN
        mm < MODERATE_FROM_MM -> LIGHT
        mm < HEAVY_FROM_MM -> MODERATE
        else -> HEAVY
    }
}
