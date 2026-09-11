package it.apexweather.ui.compare

import it.apexweather.ui.common.Formats
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The chart's vertical axis: where it starts, where it ends, and how far apart its ticks sit.
 *
 * The step is chosen from a ladder of round numbers rather than by dividing the range into four,
 * because a tick has to be a number a reader recognises — and, more than that, because two ticks
 * must never read the same. A day on which no model forecasts a drop of rain used to run the axis
 * from 0 to 2 in four steps and round every tick to a whole number, which drew gridlines labelled
 * "0", "0", "1", "1", "2" with the flat line at zero running between the two zeros.
 */
data class ChartAxis(val lo: Double, val step: Double, val steps: Int) {
    val hi: Double get() = lo + step * steps

    fun ticks(): List<Double> = (0..steps).map { lo + step * it }

    /**
     * A tick as the reader writes numbers: as many decimals as the step needs and no more, so a
     * half-millimetre axis reads "0,5" and a ten-degree one does not read "10,0".
     */
    fun label(value: Double, formats: Formats): String =
        if (step < 1.0) formats.oneDecimal(value) else formats.whole(value.roundToInt())

    companion object {
        /** Round numbers a reader recognises, scaled by powers of ten. */
        private val LADDER = listOf(0.5, 1.0, 2.0, 2.5, 5.0)

        /**
         * An axis holding every one of [values]. [nonNegative] is true for the quantities that
         * cannot go below zero — precipitation and wind — whose axis starts there rather than
         * inventing a negative floor.
         */
        fun of(values: List<Double>, nonNegative: Boolean, steps: Int = 4): ChartAxis {
            if (values.isEmpty()) return ChartAxis(0.0, 1.0, steps)
            val min = values.min()
            val max = values.max()
            // A degree of headroom keeps a line off the top edge; a non-negative quantity keeps its
            // floor at zero, so an hour of drizzle is not drawn halfway up a chart.
            val lo = if (nonNegative) floor(min).coerceAtLeast(0.0) else floor(min - 1)
            val reach = if (nonNegative) max else max + 1
            // Never finer than half a unit: at 0,04 mm of drizzle a tenth-of-a-millimetre axis
            // labelled every tick "0,0", and the app writes anything under 0,05 mm as zero anyway.
            var step = niceStep((reach - lo) / steps).coerceAtLeast(MIN_STEP)
            // The ladder rounds down as often as up, so grow it until the axis actually reaches the
            // largest value rather than cutting the line off at the top.
            while (lo + step * steps < reach) step = niceStep(step * 1.0001)
            return ChartAxis(lo, step, steps)
        }

        /** The smallest round number at least as large as [raw]; never zero, or the axis is flat. */
        private fun niceStep(raw: Double): Double {
            if (raw <= 0.0 || !raw.isFinite()) return LADDER.first()
            val magnitude = 10.0.pow(floor(log10(raw)))
            LADDER.forEach { rung ->
                val candidate = rung * magnitude
                if (candidate >= raw - EPSILON) return candidate
            }
            return ceil(raw / (10.0 * magnitude)) * 10.0 * magnitude
        }

        /** Wide enough to absorb the error of a division, narrow enough to never skip a rung. */
        private const val EPSILON = 1e-9

        /** The finest step any of these quantities is worth drawing, and the one the labels can write. */
        private const val MIN_STEP = 0.5
    }
}
