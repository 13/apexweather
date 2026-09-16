package it.apexweather.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import it.apexweather.data.remote.NowcastExtent
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
    private val extent = step.extent
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val layer = android.graphics.RectF()

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

    /** [buffer]'s pixels, read out for [fillHoles] and written back; resized whenever it is. */
    private var pixels: IntArray? = null

    /** [FrameLayers]' crossfade: 1 at full strength, fading to 0 as this frame gives way to the next. */
    var fade: Float = 1f

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || cells.isEmpty()) return
        val strength = (255 * fade).toInt().coerceIn(0, 255)
        if (strength == 0) return
        val projection = map.projection
        // Region-wide that is some 18 000 cells a step, and the map redraws many times a second
        // while playing and on every frame of a crossfade. Placing them is only worth redoing when
        // the map has moved; the fade is the paint's alpha, not baked into the pixels.
        val view = ViewKey(projection.zoomLevel, projection.offsetX, projection.offsetY, map.width, map.height)
        if (view != renderedFor) {
            render(map, projection)
            renderedFor = view
        }
        val bitmap = buffer?.takeIf { hasContent } ?: return
        bitmapPaint.alpha = strength
        val edge = edgeRect
        if (edge == null) {
            canvas.drawBitmap(bitmap, null, target, bitmapPaint)
            return
        }
        // Faded out at the edge of the grid over the radar's own width, so the two layers end the
        // same way. The forecast used to be asked for a box round the place, and on a wet day the
        // rain filled it: drawn to the edge, that was a hard-edged square over the valley
        // (2026-09-16, Meran).
        val feather = RadarOverlay.FEATHER_DP * map.context.resources.displayMetrics.density
        layer.set(
            maxOf(target.left, 0f), maxOf(target.top, 0f),
            minOf(target.right, map.width.toFloat()), minOf(target.bottom, map.height.toFloat()),
        )
        if (layer.isEmpty) return
        val saved = canvas.saveLayer(layer, null)
        canvas.drawBitmap(bitmap, null, target, bitmapPaint)
        fadeEdge(canvas, edge.left, 0f, edge.left + feather, 0f)
        fadeEdge(canvas, edge.right, 0f, edge.right - feather, 0f)
        fadeEdge(canvas, 0f, edge.top, 0f, edge.top + feather)
        fadeEdge(canvas, 0f, edge.bottom, 0f, edge.bottom - feather)
        canvas.restoreToCount(saved)
    }

    private data class ViewKey(val zoom: Double, val offsetX: Long, val offsetY: Long, val width: Int, val height: Int)

    /** The map position [buffer] was last painted for. */
    private var renderedFor: ViewKey? = null
    private var hasContent = false
    private val target = android.graphics.RectF()
    private var edgeRect: android.graphics.RectF? = null

    /** Paints the cells on screen into [buffer] and works out where it and the grid's edge go. */
    private fun render(map: MapView, projection: Projection) {
        hasContent = false
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
            Triple(x, y, colour.toArgb((strength * colour.alpha).toInt()))
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
        // INCA's grid is its own (evidently rotated) projection resampled to lat/lon — measured
        // against a recorded field with rain in it, no two of its 1 638 points share a latitude or
        // a longitude — so even placed at full sub-pixel precision it cannot tile this raster
        // exactly: a handful of interior pixels end up with no cell close enough to claim them, and
        // print as small dark dimples once bilinear filtering blends them against the transparent
        // background. `fillHoles` closes only the ones surrounded enough to be obvious, leaving the
        // padding ring (which never has three filled orthogonal neighbours) untouched.
        val pixelCount = layout.cols * layout.rows
        val pixelBuffer = pixels.let { existing ->
            if (existing != null && existing.size == pixelCount) existing else IntArray(pixelCount).also { pixels = it }
        }
        bitmap.getPixels(pixelBuffer, 0, layout.cols, 0, 0, layout.cols, layout.rows)
        fillHoles(pixelBuffer, layout.cols, layout.rows)
        bitmap.setPixels(pixelBuffer, 0, layout.cols, 0, 0, layout.cols, layout.rows)
        target.set(layout.left, layout.top, layout.left + layout.cols * side, layout.top + layout.rows * side)
        edgeRect = extent?.let { gridRect(projection, it, side) }
        hasContent = true
    }

    /** The grid's outer edge on screen: its extreme points plus half a cell, since they are centres. */
    private fun gridRect(projection: Projection, e: NowcastExtent, side: Float): android.graphics.RectF {
        val reuse = PointL()
        val (left, top) = projectPrecise(projection, e.north, e.west, reuse)
        val (right, bottom) = projectPrecise(projection, e.south, e.east, reuse)
        return android.graphics.RectF(left - side / 2f, top - side / 2f, right + side / 2f, bottom + side / 2f)
    }

    private fun fadeEdge(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        maskPaint.shader = LinearGradient(x0, y0, x1, y1, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(layer, maskPaint)
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
        pixels = null
        super.onDetach(mapView)
    }

    /** [km] of ground, in pixels, measured off the projection at this latitude. */
    private fun cellSidePx(projection: Projection, lat: Double, km: Double): Float {
        val reuse = PointL()
        val (ax, _) = projectPrecise(projection, lat, 11.0, reuse)
        val (bx, _) = projectPrecise(projection, lat, 11.0 + ONE_KM_OF_LON * km, reuse)
        return max(1f, abs(bx - ax))
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

/**
 * Fills a transparent pixel with the average of its orthogonal neighbours, where at least three
 * of the (up to four) that exist already have a colour.
 *
 * Three of four is "obviously surrounded": a real edge or a thin outline never has more than two
 * filled orthogonal neighbours, so this never smears one — it only closes a pixel that reads as a
 * hole in what is otherwise a solid field. [argb] is row-major, `cols` wide and `rows` tall,
 * matching `Bitmap.getPixels`' own layout exactly so it can be read out of and written back into a
 * bitmap with no reshaping. Neighbours are read from a copy of [argb] taken once at the start, so
 * one fill is never built from another fill made earlier in the same pass — every pixel in a call
 * sees the same picture the call began with. A border pixel simply has fewer neighbours to ask;
 * nothing outside the array is invented to make up the other one or two.
 *
 * Each channel — alpha, red, green, blue — is averaged on its own, never as one packed `Int`,
 * because an `Int` average would blend the channels' bits into each other.
 *
 * **This cannot tell a tiling gap from a genuinely dry kilometre, because both arrive the same
 * way.** A transparent pixel here means either the rotated native grid had no cell close enough to
 * claim it, or the nearest cell really was dry — `NowcastMapper` drops dry cells before
 * `NowcastOverlay` ever sees them, so both a tiling gap and a real dry cell reach this function as
 * the identical, unlabelled nothing. A genuinely dry kilometre enclosed by rain would be painted
 * over exactly like a gap. At this grid a single enclosed dry cell is below what the forecast
 * resolves in the first place, so this is accepted rather than fixed; a dry corridor two or more
 * pixels wide is never filled, since neither of its two transparent rows ever has three filled
 * orthogonal neighbours.
 *
 * Returns how many pixels were filled.
 */
internal fun fillHoles(argb: IntArray, cols: Int, rows: Int): Int {
    val source = argb.copyOf()
    var filled = 0
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val i = row * cols + col
            if (source[i] ushr 24 != 0) continue
            var count = 0
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            fun consider(neighbour: Int) {
                if (neighbour ushr 24 == 0) return
                count++
                a += neighbour ushr 24
                r += (neighbour ushr 16) and 0xFF
                g += (neighbour ushr 8) and 0xFF
                b += neighbour and 0xFF
            }
            if (row > 0) consider(source[i - cols])
            if (row < rows - 1) consider(source[i + cols])
            if (col > 0) consider(source[i - 1])
            if (col < cols - 1) consider(source[i + 1])
            if (count >= 3) {
                argb[i] = ((a / count) shl 24) or (((r / count) and 0xFF) shl 16) or (((g / count) and 0xFF) shl 8) or ((b / count) and 0xFF)
                filled++
            }
        }
    }
    return filled
}

/**
 * [lat]/[lon] as a screen pixel at full precision, in place of [Projection.toPixels]' own
 * int-truncated one.
 *
 * `toPixels` throws away the sub-pixel fraction before an overlay ever sees it — harmless
 * for a single marker, but the forecast overlay can place hundreds of grid cells a few pixels apart,
 * and two cells whose true positions differ by less than a pixel then round to the exact same
 * integer pixel and collide in [bitmapLayout]'s bitmap: measured at 61 of 801 placed cells at
 * zoom 9 against a recorded field with rain in it (`map-2026-09-14/inca-0500Z.json`,
 * `NowcastOverlayScreenshotTest`). `toProjectedPixels` gives a zoom-independent, high-precision
 * Mercator position; dividing it by the projection's own zoom scale
 * ([Projection.getProjectedPowerDifference]) and adding its own screen offset
 * ([Projection.getOffsetX]/`getOffsetY`) is exactly the arithmetic
 * `Projection.getLongPixelsFromProjected` does internally before its own final `(long)` cast —
 * recovering the fraction osmdroid already computed and then discarded, using only its own
 * public API. Skips the wraparound correction that method also applies, because the province
 * never nears the antimeridian. [RadarOverlay] places its tile corners with it too.
 */
internal fun projectPrecise(projection: Projection, lat: Double, lon: Double, reuse: PointL): Pair<Float, Float> {
    projection.toProjectedPixels(lat, lon, reuse)
    val power = projection.projectedPowerDifference
    return (reuse.x / power + projection.offsetX).toFloat() to (reuse.y / power + projection.offsetY).toFloat()
}
