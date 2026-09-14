package it.apexweather.ui.map

import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import it.apexweather.data.remote.NowcastKind
import it.apexweather.data.remote.NowcastStep
import org.osmdroid.util.PointL
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.max

/**
 * The forecast rain, drawn as the grid it is.
 *
 * Cells are drawn as a filtered bitmap, one pixel per grid cell — soft edges, still visibly
 * coarser than the radar: it is a 1 km model saying where the rain will be, and reading as a grid
 * is an honest statement of what it knows, even once the hard edges are gone. It is drawn under
 * the same alpha the radar carries, so the relief the reader is placing the rain against stays
 * visible through both.
 *
 * The cell size is computed from the projection each draw rather than stored, so it stays one
 * kilometre of ground at every zoom.
 */
class NowcastOverlay(step: NowcastStep, private val alpha: Int) : Overlay() {

    private val cells = step.cells

    /**
     * How wide a cell is on the ground, which is not the same for both halves of the forecast.
     *
     * INCA's grid is a kilometre and AROME's is two and a half. Drawing every cell a kilometre wide
     * would leave the outlook as a field of dots with more gap than rain — the grid points are that
     * far apart — and the gaps would read as dry ground rather than as a coarser picture.
     */
    private val cellKm = if (step.kind == NowcastKind.OUTLOOK) OUTLOOK_CELL_KM else NOWCAST_CELL_KM
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Reused across draws rather than allocated fresh each time: a frame redraws many times a
     * second while it is the one on screen, and a bitmap the size of a few dozen pixels is cheap to
     * keep and expensive to keep re-creating. Reallocated only when the grid on screen changes
     * shape (a pan, a zoom, a new step); otherwise just erased and repainted.
     *
     * Freed in [onDetach], not before: that runs once, when the whole `MapView` is torn down, and
     * an overlay simply dropped from the map's overlay list — the far more common case, on every
     * step change — is released by garbage collection instead, taking this bitmap with it.
     */
    private var buffer: android.graphics.Bitmap? = null

    /** [FrameLayers]' crossfade: 1 at full strength, fading to 0 as this frame gives way to the next. */
    var fade: Float = 1f

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || cells.isEmpty()) return
        val projection = map.projection
        val side = cellSidePx(projection, cells.first().lat, cellKm)
        val bounds = map.boundingBox
        val projected = PointL()
        // Project once, keep what is on screen and has a colour.
        val placed = cells.mapNotNull { cell ->
            if (cell.lat < bounds.latSouth || cell.lat > bounds.latNorth) return@mapNotNull null
            if (cell.lon < bounds.lonWest || cell.lon > bounds.lonEast) return@mapNotNull null
            val solid = if (cell.unconfirmed) null else PrecipColors.forRate(cell.mmPerHour)
            val possible = if (solid != null) null
                else (if (cell.unconfirmed) cell.mmPerHour else cell.upperMmPerHour)?.let(PrecipColors::forRate)
            val colour = solid ?: possible ?: return@mapNotNull null
            val (x, y) = projectPrecise(projection, cell.lat, cell.lon, projected)
            val strength = if (solid != null) alpha else alpha * POSSIBLE_ALPHA_NUMERATOR / 10
            Triple(x, y, colour.toArgb((strength * colour.alpha * fade).toInt()))
        }
        if (placed.isEmpty()) return
        // One bitmap pixel per grid cell, drawn scaled with filtering: the edges soften and the grid
        // still reads as coarser than radar, where squares read as pixel blocks. A transparent
        // border cell on every side (not only the far and bottom ones) is what lets the filter
        // fade the edge out rather than clip it.
        val layout = bitmapLayout(placed.map { it.first }, placed.map { it.second }, side)
        val bitmap = buffer.let { existing ->
            if (existing != null && existing.width == layout.cols && existing.height == layout.rows) {
                existing.eraseColor(android.graphics.Color.TRANSPARENT)
                existing
            } else {
                existing?.recycle()
                createBitmap(layout.cols, layout.rows).also { buffer = it }
            }
        }
        placed.forEach { (x, y, argb) ->
            bitmap[layout.column(x).coerceIn(0, layout.cols - 1), layout.row(y).coerceIn(0, layout.rows - 1)] = argb
        }
        canvas.drawBitmap(
            bitmap,
            null,
            android.graphics.RectF(layout.left, layout.top, layout.left + layout.cols * side, layout.top + layout.rows * side),
            bitmapPaint,
        )
    }

    /**
     * `Overlay.onDetach` runs once, at whole-`MapView` teardown (`MapView.onDetach()`), not every
     * time this particular overlay is dropped from the overlay list — [FrameLayers] replaces the
     * on-screen overlay on every step change far more often than the map itself is torn down, and
     * an overlay removed that way is simply released by garbage collection, [buffer] with it. This
     * is the backstop for the one case that matters: the map view itself going away.
     */
    override fun onDetach(mapView: MapView?) {
        buffer?.recycle()
        buffer = null
        super.onDetach(mapView)
    }

    /** [km] of ground, in pixels, measured off the projection at this latitude. */
    private fun cellSidePx(projection: Projection, lat: Double, km: Double): Float {
        val reuse = PointL()
        val (ax, _) = projectPrecise(projection, lat, 11.0, reuse)
        val (bx, _) = projectPrecise(projection, lat, 11.0 + ONE_KM_OF_LON * km, reuse)
        return max(1f, abs(bx - ax))
    }

    /**
     * [lat]/[lon] as a screen pixel at full precision, in place of [Projection.toPixels]' own
     * int-truncated one.
     *
     * `toPixels` throws away the sub-pixel fraction before this overlay ever sees it — harmless
     * for a single marker, but this overlay can place hundreds of grid cells a few pixels apart,
     * and two cells whose true positions differ by less than a pixel then round to the exact same
     * integer pixel and collide in [bitmapLayout]'s bitmap: measured at 61 of 801 placed cells at
     * zoom 9 against a recorded field with rain in it (`map-2026-09-14/inca-0500Z.json`,
     * `NowcastOverlayScreenshotTest`). `toProjectedPixels` gives a zoom-independent, high-precision
     * Mercator position; dividing it by the projection's own zoom scale
     * ([Projection.getProjectedPowerDifference]) and adding its own screen offset
     * ([Projection.getOffsetX]/`getOffsetY`) is exactly the arithmetic
     * `Projection.getLongPixelsFromProjected` does internally before its own final `(long)` cast —
     * recovering the fraction osmdroid already computed and then discarded, using only its own
     * public API. Skips the wraparound correction that method also applies, because a single
     * place's forecast box never nears the antimeridian.
     */
    private fun projectPrecise(projection: Projection, lat: Double, lon: Double, reuse: PointL): Pair<Float, Float> {
        projection.toProjectedPixels(lat, lon, reuse)
        val power = projection.projectedPowerDifference
        return (reuse.x / power + projection.offsetX).toFloat() to (reuse.y / power + projection.offsetY).toFloat()
    }

    private fun androidx.compose.ui.graphics.Color.toArgb(alpha: Int): Int =
        (alpha shl 24) or
            ((red * 255).toInt() shl 16) or
            ((green * 255).toInt() shl 8) or
            (blue * 255).toInt()

    private companion object {
        /** A kilometre of longitude at about 46,6 degrees north. */
        const val ONE_KM_OF_LON = 0.01306

        /**
         * How much of the usual strength a "might reach here" cell is drawn at, in tenths.
         *
         * Faint enough that the shape the median actually forecasts stays the thing you see, strong
         * enough to notice that the rain has somewhere it could go.
         */
        const val POSSIBLE_ALPHA_NUMERATOR = 4

        /** INCA's grid. */
        const val NOWCAST_CELL_KM = 1.0

        /** AROME's, for the hours past INCA's reach. */
        const val OUTLOOK_CELL_KM = 2.5
    }
}

/**
 * Where the bitmap's corner sits on screen, and how many cells wide and tall it is.
 *
 * [column] and [row] are the inverse of that placement: which pixel of the bitmap a screen point
 * falls into. Both sides of the same arithmetic live here so they cannot drift apart — the grid
 * placement was once computed in [NowcastOverlay.draw] and the pixel index recomputed beside it,
 * and a change to one without the other is exactly how a `-2f` becomes a `-1f` unnoticed.
 */
internal data class BitmapLayout(val left: Float, val top: Float, val cols: Int, val rows: Int, val side: Float) {
    fun column(x: Float) = ((x - left) / side).toInt()
    fun row(y: Float) = ((y - top) / side).toInt()
}

/**
 * Lays a grid of cell centres out on a bitmap with a transparent cell of margin on every side, not
 * only the far and bottom ones.
 *
 * The first version placed the nearest cell's centre in column/row 0 — half a cell of margin
 * before it and a full cell after — which gave the filtered edge somewhere to fade into on the far
 * and bottom sides and nowhere on the near and top ones, so the softening the whole feature was
 * for was only ever visible on two of the four edges. Shifting `left`/`top` out by one more full
 * `side` moves the nearest cell into column/row 1 and opens an equal margin on every side.
 */
internal fun bitmapLayout(xs: List<Float>, ys: List<Float>, side: Float): BitmapLayout {
    val left = xs.min() - side / 2f - side
    val top = ys.min() - side / 2f - side
    val cols = ((xs.max() - left) / side).toInt() + 2
    val rows = ((ys.max() - top) / side).toInt() + 2
    return BitmapLayout(left, top, cols, rows, side)
}
