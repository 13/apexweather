package it.apexweather.ui.compare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.Source
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.SourceColors
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Lines per source, thick white consensus line, optional shaded band, over the window the state
 * chose. Touching the chart selects an hour; the values themselves are shown by the caller, outside
 * the canvas, where a finger does not cover them.
 */
@Composable
fun MultiLineChart(
    series: Map<Source, List<SeriesPoint>>,
    consensus: List<SeriesPoint>,
    band: List<BandPoint>,
    window: CompareWindow,
    unitLabel: String,
    modifier: Modifier = Modifier,
    /** True for variables that cannot go below zero (precipitation, wind): keeps the axis from showing negatives. */
    nonNegative: Boolean = false,
    /** Names the plotted variable for the spoken summary; the chart itself is only lines. */
    variableName: String = "",
    /** Drawn as a marker when it falls inside the window, so today's chart says where the present is. */
    now: Instant? = null,
    /** The hour the reader has picked, or null while nothing is picked. */
    selectedHour: Instant? = null,
    onSelectHour: (Instant) -> Unit = {},
) {
    val formats = LocalFormats.current
    val haptics = LocalHapticFeedback.current
    val labelPaint = remember {
        android.graphics.Paint().apply { color = android.graphics.Color.argb(160, 255, 255, 255); textSize = 28f; isAntiAlias = true }
    }

    val values = series.values.flatten().map { it.value } + consensus.map { it.value }
    val all = values + band.flatMap { listOf(it.min, it.max) }
    val lo = if (all.isEmpty()) 0.0 else if (nonNegative) floor(all.min()).coerceAtLeast(0.0) else floor(all.min() - 1)
    val hi = if (all.isEmpty()) 1.0 else ceil(all.max() + 1).coerceAtLeast(lo + 2)
    val steps = 4
    val yLabels = remember(lo, hi, unitLabel) { (0..steps).map { s -> "${(lo + (hi - lo) * s / steps).toInt()}$unitLabel" } }

    // A canvas of lines says nothing to a screen reader, so the chart carries its own summary:
    // what is plotted, over how long, across what range, and how many models are in it.
    val models = pluralStringResource(R.plurals.compare_chart_desc_models, series.size, series.size)
    val summary = if (values.isEmpty()) variableName else pluralStringResource(
        R.plurals.compare_chart_desc, window.hours.toInt(), variableName, window.hours,
        "${values.min().roundToInt()}$unitLabel", "${values.max().roundToInt()}$unitLabel", models,
    )
    val spokenSelection = selectedHour?.let { Format.time(it, SouthTyrol.ZONE, formats) }

    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val geometry = remember(width, height, window, lo, hi, yLabels) {
            ChartGeometry(
                left = 8f + yLabels.maxOf { labelPaint.measureText(it) },
                right = width - 12f,
                top = 12f,
                bottom = height - 36f,
                from = window.from,
                hours = window.hours,
                lo = lo,
                hi = hi,
            )
        }

        Canvas(
            Modifier.fillMaxSize()
                .testTag("compare_chart")
                .semantics {
                    contentDescription = summary
                    spokenSelection?.let { stateDescription = it }
                }
                // Consuming the events is what stops the enclosing lazy list from taking the drag
                // and scrolling the page instead of scrubbing the chart.
                .pointerInput(geometry) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var last = geometry.hourAt(down.position.x)
                        onSelectHour(last)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val hour = geometry.hourAt(change.position.x)
                            if (hour != last) {
                                last = hour
                                onSelectHour(hour)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                },
        ) {
            if (all.isEmpty()) return@Canvas

            for (s in 0..steps) {
                val v = lo + (hi - lo) * s / steps
                drawLine(Color.White.copy(alpha = 0.10f), Offset(geometry.left, geometry.y(v)), Offset(geometry.right, geometry.y(v)), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(yLabels[s], 4f, geometry.y(v) + 10f, labelPaint)
            }

            var t = window.from
            while (!t.isAfter(geometry.until)) {
                val z = t.atZone(SouthTyrol.ZONE)
                val label = if (z.hour == 0) Format.weekday(z.toLocalDate(), formats) else "${z.hour}h"
                drawContext.canvas.nativeCanvas.drawText(label, geometry.x(t) - 12f, size.height - 8f, labelPaint)
                drawLine(Color.White.copy(alpha = 0.06f), Offset(geometry.x(t), geometry.top), Offset(geometry.x(t), geometry.bottom), strokeWidth = 1f)
                t = t.plusSeconds(geometry.tickHours * 3600)
            }

            if (now != null && geometry.contains(now)) {
                drawLine(
                    Color.White.copy(alpha = 0.35f),
                    Offset(geometry.x(now), geometry.top), Offset(geometry.x(now), geometry.bottom),
                    strokeWidth = 2f,
                )
            }

            if (band.size >= 2) {
                val p = Path().apply {
                    moveTo(geometry.x(band[0].time), geometry.y(band[0].max))
                    band.forEach { lineTo(geometry.x(it.time), geometry.y(it.max)) }
                    band.reversed().forEach { lineTo(geometry.x(it.time), geometry.y(it.min)) }
                    close()
                }
                drawPath(p, Color.White.copy(alpha = 0.10f))
            }
            series.forEach { (source, pts) ->
                if (pts.size < 2) return@forEach
                val p = Path().apply { moveTo(geometry.x(pts[0].time), geometry.y(pts[0].value)); pts.drop(1).forEach { lineTo(geometry.x(it.time), geometry.y(it.value)) } }
                drawPath(p, SourceColors.of(source), style = Stroke(width = 2.5f))
            }
            if (consensus.size >= 2) {
                val p = Path().apply { moveTo(geometry.x(consensus[0].time), geometry.y(consensus[0].value)); consensus.drop(1).forEach { lineTo(geometry.x(it.time), geometry.y(it.value)) } }
                drawPath(p, SourceColors.consensus, style = Stroke(width = 5f))
            }

            if (selectedHour != null && geometry.contains(selectedHour)) {
                val cx = geometry.x(selectedHour)
                drawLine(Color.White.copy(alpha = 0.55f), Offset(cx, geometry.top), Offset(cx, geometry.bottom), strokeWidth = 1.5f)
                series.forEach { (source, pts) ->
                    pts.firstOrNull { it.time == selectedHour }?.let {
                        drawCircle(SourceColors.of(source), radius = 5f, center = Offset(cx, geometry.y(it.value)))
                    }
                }
                consensus.firstOrNull { it.time == selectedHour }?.let {
                    drawCircle(SourceColors.consensus, radius = 7f, center = Offset(cx, geometry.y(it.value)))
                }
            }
        }
    }
}
