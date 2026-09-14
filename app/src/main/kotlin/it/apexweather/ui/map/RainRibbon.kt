package it.apexweather.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

object RibbonColors {
    /** The radar's own mid blue, 18 dBZ: what was seen. */
    val OBSERVED = Color(0xFF36BAE5)

    /** Forecast chrome only. Never a rain colour: the map's rain is always [PrecipColors]. */
    val FORECAST = Color(0xFFFFC861)
}

/** The bar under an x position, or null where there is nothing to point at. */
internal fun ribbonIndexAt(x: Float, width: Int, count: Int): Int? =
    if (count <= 0 || width <= 0) null else (x / width * count).toInt().coerceIn(0, count - 1)

/**
 * Which labels actually get drawn, so two of them never touch.
 *
 * A label's rect is its centre clamped exactly as [RibbonLabels] places it — width either side,
 * kept inside `0..totalWidth`. [priority] is tried first regardless of where it sits, because
 * "jetzt" is worth more than whichever hour would otherwise occupy that spot; everything else is
 * then tried left to right, and a label is kept only if its rect, widened by [gapPx] on each side,
 * misses every rect already kept — so two labels are dropped for touching, not only for
 * overlapping.
 */
internal fun visibleLabelSlots(centres: List<Float>, widths: List<Int>, priority: Int?, gapPx: Int, totalWidth: Int): List<Int> {
    fun left(i: Int): Float {
        val w = widths[i].toFloat()
        return (centres[i] - w / 2f).coerceIn(0f, (totalWidth - w).coerceAtLeast(0f))
    }
    val order = if (priority != null && priority in centres.indices) {
        listOf(priority) + centres.indices.filter { it != priority }
    } else {
        centres.indices.toList()
    }
    val kept = mutableListOf<Int>()
    val keptRanges = mutableListOf<ClosedFloatingPointRange<Float>>()
    for (i in order) {
        val l = left(i)
        val r = l + widths[i]
        val widened = (l - gapPx)..(r + gapPx)
        val collides = keptRanges.any { widened.start < it.endInclusive && it.start < widened.endInclusive }
        if (!collides) {
            kept += i
            keptRanges += l..r
        }
    }
    return kept.sorted()
}

/**
 * The map's timeline as rain at the reader's place: a bar per step, so the answer to "when does it
 * reach me" is on screen before anything plays.
 *
 * Solid blue is what the radar saw, amber hatching what a model expects, a dashed outline what a
 * model expects and the radar does not confirm. It behaves as a slider for accessibility, and
 * releasing a drag within a step of "jetzt" snaps to it.
 */
@Composable
fun RainRibbon(
    bars: List<RibbonBar>,
    selected: Int,
    nowIndex: Int,
    labels: List<Pair<Int, String>>,
    stateDescription: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // The pointer handlers are installed once, so everything they read has to be the latest value.
    val currentSelected by rememberUpdatedState(selected)
    val currentBars by rememberUpdatedState(bars)
    val currentNow by rememberUpdatedState(nowIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val fontScale = LocalDensity.current.fontScale
    val barAreaHeight = (34 * fontScale).dp

    Column(
        modifier
            .fillMaxWidth()
            .testTag("map_ribbon")
            .semantics(mergeDescendants = true) {
                this.stateDescription = stateDescription
                if (bars.size >= 2) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        selected.toFloat(), 0f..(bars.size - 1).toFloat(), steps = (bars.size - 2).coerceAtLeast(0),
                    )
                    setProgress { value -> onSelect(value.roundToInt().coerceIn(0, bars.size - 1)); true }
                }
            },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(barAreaHeight)
                .pointerInput(Unit) {
                    detectTapGestures { ribbonIndexAt(it.x, size.width, currentBars.size)?.let(currentOnSelect) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (currentNow >= 0 && abs(currentSelected - currentNow) <= 1) currentOnSelect(currentNow) },
                    ) { change, _ ->
                        val i = ribbonIndexAt(change.position.x, size.width, currentBars.size) ?: return@detectHorizontalDragGestures
                        if (i != currentSelected) {
                            if (currentBars.getOrNull(i)?.time?.epochSecond?.rem(3600) == 0L) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            currentOnSelect(i)
                        }
                    }
                },
        ) {
            if (bars.isEmpty()) return@Canvas
            val pitch = size.width / bars.size
            val barWidth = pitch * 0.72f
            val stub = 2.dp.toPx()
            val radius = CornerRadius(2.dp.toPx())
            bars.forEachIndexed { i, bar ->
                val left = i * pitch + (pitch - barWidth) / 2f
                val h = if (bar.mmPerHour < PrecipColors.DRAWN_FROM_MM) stub else (RibbonModel.fraction(bar.mmPerHour) * size.height).coerceAtLeast(stub)
                val top = size.height - h
                when {
                    bar.mmPerHour < PrecipColors.DRAWN_FROM_MM ->
                        drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(left, top), Size(barWidth, h), radius)
                    bar.unconfirmed -> drawRoundRect(
                        Color.White.copy(alpha = 0.6f), Offset(left, top), Size(barWidth, h), radius,
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                    )
                    bar.kind == BarKind.OBSERVED -> drawRoundRect(RibbonColors.OBSERVED, Offset(left, top), Size(barWidth, h), radius)
                    else -> {
                        drawRoundRect(RibbonColors.FORECAST.copy(alpha = 0.45f), Offset(left, top), Size(barWidth, h), radius)
                        var y = size.height - 1.dp.toPx()
                        while (y > top) {
                            drawLine(RibbonColors.FORECAST, Offset(left, y), Offset(left + barWidth, y), strokeWidth = 1.dp.toPx())
                            y -= 3.dp.toPx()
                        }
                    }
                }
                bar.upperMmPerHour?.takeIf { it > bar.mmPerHour && it >= PrecipColors.DRAWN_FROM_MM }?.let { upper ->
                    val capY = size.height - RibbonModel.fraction(upper) * size.height
                    drawLine(RibbonColors.FORECAST.copy(alpha = 0.6f), Offset(left, capY), Offset(left + barWidth, capY), strokeWidth = 1.dp.toPx())
                }
                if (i == selected) {
                    drawRoundRect(
                        Color.White, Offset(left - 1.5f.dp.toPx(), top - 1.5f.dp.toPx()),
                        Size(barWidth + 3.dp.toPx(), h + 3.dp.toPx()), radius, style = Stroke(1.5f.dp.toPx()),
                    )
                }
            }
            if (nowIndex in bars.indices) {
                val x = nowIndex * pitch + pitch / 2f
                drawLine(Color.White, Offset(x, -2.dp.toPx()), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        RibbonLabels(bars.size, labels, nowIndex)
    }
}

/**
 * Hour labels centred under their bars, each clamped inside the ribbon's width — but only the
 * ones [visibleLabelSlots] keeps: two labels that would touch at a wide font scale must not both
 * draw, and "jetzt" is the one of them worth keeping.
 */
@Composable
private fun RibbonLabels(count: Int, labels: List<Pair<Int, String>>, nowIndex: Int) {
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    Layout(
        content = {
            labels.forEach { (_, text) ->
                Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            if (count == 0 || labels.isEmpty()) return@layout
            val pitch = width.toFloat() / count
            val centres = labels.map { it.first * pitch + pitch / 2f }
            val widths = placeables.map { it.width }
            val priority = labels.indexOfFirst { it.first == nowIndex }.takeIf { it >= 0 }
            val visible = visibleLabelSlots(centres, widths, priority, gapPx, width).toSet()
            placeables.forEachIndexed { n, p ->
                if (n !in visible) return@forEachIndexed
                p.placeRelative((centres[n] - p.width / 2f).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}
