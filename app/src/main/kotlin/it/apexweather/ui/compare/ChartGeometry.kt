package it.apexweather.ui.compare

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Where the chart puts a time and a value on the canvas, and the inverse.
 *
 * This used to live inside the draw lambda, which meant nothing outside could answer "which hour is
 * under the finger". It is a pure function of the canvas size, the window and the value range, so
 * the drawing and the touch handling read the same one.
 */
data class ChartGeometry(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val from: Instant,
    val hours: Long,
    val lo: Double,
    val hi: Double,
) {
    private val spanMillis: Double = hours.coerceAtLeast(1) * 3_600_000.0
    private val plotWidth: Float = (right - left).coerceAtLeast(1f)
    private val valueSpan: Double = (hi - lo).takeIf { it > 0.0 } ?: 1.0

    /** The last instant on the axis. */
    val until: Instant get() = from.plus(hours, ChronoUnit.HOURS)

    fun x(t: Instant): Float =
        left + ((t.toEpochMilli() - from.toEpochMilli()) / spanMillis * plotWidth).toFloat()

    fun y(value: Double): Float =
        bottom - ((value - lo) / valueSpan * (bottom - top)).toFloat()

    fun contains(t: Instant): Boolean = !t.isBefore(from) && !t.isAfter(until)

    /**
     * The hour nearest a horizontal position, clamped to the window. Snapping to the hour rather
     * than to the pixel is what makes a value readable: the models only publish hours.
     */
    fun hourAt(px: Float): Instant {
        val fraction = ((px - left) / plotWidth).coerceIn(0f, 1f)
        val exact = from.plusMillis((fraction * spanMillis).toLong())
        val floor = exact.truncatedTo(ChronoUnit.HOURS)
        val snapped = if (Duration.between(floor, exact).toMinutes() >= 30) floor.plus(1, ChronoUnit.HOURS) else floor
        return snapped.coerceIn(from, until)
    }

    /**
     * How far apart the time labels sit. Twelve hours reads well across three days and leaves a
     * single day with three labels, two of them at the edges.
     */
    val tickHours: Long get() = if (hours <= 36) 3 else 12
}

private fun Instant.coerceIn(min: Instant, max: Instant): Instant = when {
    isBefore(min) -> min
    isAfter(max) -> max
    else -> this
}
