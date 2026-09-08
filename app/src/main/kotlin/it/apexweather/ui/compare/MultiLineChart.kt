package it.apexweather.ui.compare

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.Source
import it.apexweather.ui.common.SourceColors
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor

/** Lines per source, thick white consensus line, optional shaded band. X axis = time over the 72 h window. */
@Composable
fun MultiLineChart(
    series: Map<Source, List<SeriesPoint>>,
    consensus: List<SeriesPoint>,
    band: List<BandPoint>,
    from: Instant,
    hours: Long,
    unitLabel: String,
    /** True for variables that cannot go below zero (precipitation, wind): keeps the axis from showing negatives. */
    nonNegative: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val labelPaint = remember {
        android.graphics.Paint().apply { color = android.graphics.Color.argb(160, 255, 255, 255); textSize = 28f; isAntiAlias = true }
    }
    Canvas(modifier.testTag("compare_chart")) {
        val all = series.values.flatten().map { it.value } + consensus.map { it.value } + band.flatMap { listOf(it.min, it.max) }
        if (all.isEmpty()) return@Canvas
        val lo = if (nonNegative) floor(all.min()).coerceAtLeast(0.0) else floor(all.min() - 1)
        val hi = ceil(all.max() + 1).coerceAtLeast(lo + 2)
        val steps = 4
        val yLabels = (0..steps).map { s -> "${(lo + (hi - lo) * s / steps).toInt()}$unitLabel" }
        val left = 8f + yLabels.maxOf { labelPaint.measureText(it) }
        val right = size.width - 12f; val top = 12f; val bottom = size.height - 36f
        val spanMs = hours * 3_600_000.0
        fun x(t: Instant) = left + ((t.toEpochMilli() - from.toEpochMilli()) / spanMs * (right - left)).toFloat()
        fun y(v: Double) = bottom - ((v - lo) / (hi - lo) * (bottom - top)).toFloat()

        // grid + y labels
        for (s in 0..steps) {
            val v = lo + (hi - lo) * s / steps
            drawLine(Color.White.copy(alpha = 0.10f), Offset(left, y(v)), Offset(right, y(v)), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(yLabels[s], 4f, y(v) + 10f, labelPaint)
        }
        // x labels every 12 h
        var t = from
        while (!t.isAfter(from.plusSeconds(hours * 3600))) {
            val z = t.atZone(DorfTirol.ZONE)
            val label = if (z.hour == 0) z.dayOfWeek.name.take(2) else "${z.hour}h"
            drawContext.canvas.nativeCanvas.drawText(label, x(t) - 12f, size.height - 8f, labelPaint)
            drawLine(Color.White.copy(alpha = 0.06f), Offset(x(t), top), Offset(x(t), bottom), strokeWidth = 1f)
            t = t.plusSeconds(12 * 3600)
        }

        if (band.size >= 2) {
            val p = Path().apply {
                moveTo(x(band[0].time), y(band[0].max))
                band.forEach { lineTo(x(it.time), y(it.max)) }
                band.reversed().forEach { lineTo(x(it.time), y(it.min)) }
                close()
            }
            drawPath(p, Color.White.copy(alpha = 0.10f))
        }
        series.forEach { (source, pts) ->
            if (pts.size < 2) return@forEach
            val p = Path().apply { moveTo(x(pts[0].time), y(pts[0].value)); pts.drop(1).forEach { lineTo(x(it.time), y(it.value)) } }
            drawPath(p, SourceColors.of(source), style = Stroke(width = 2.5f))
        }
        if (consensus.size >= 2) {
            val p = Path().apply { moveTo(x(consensus[0].time), y(consensus[0].value)); consensus.drop(1).forEach { lineTo(x(it.time), y(it.value)) } }
            drawPath(p, SourceColors.consensus, style = Stroke(width = 5f))
        }
    }
}
