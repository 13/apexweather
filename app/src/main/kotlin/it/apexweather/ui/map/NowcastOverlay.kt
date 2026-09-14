package it.apexweather.ui.map

import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import it.apexweather.data.remote.NowcastKind
import it.apexweather.data.remote.NowcastStep
import org.osmdroid.util.GeoPoint
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

    /** [FrameLayers]' crossfade: 1 at full strength, fading to 0 as this frame gives way to the next. */
    var fade: Float = 1f

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || cells.isEmpty()) return
        val projection = map.projection
        val side = cellSidePx(projection, cells.first().lat, cellKm)
        val bounds = map.boundingBox
        val point = android.graphics.Point()
        // Project once, keep what is on screen and has a colour.
        val placed = cells.mapNotNull { cell ->
            if (cell.lat < bounds.latSouth || cell.lat > bounds.latNorth) return@mapNotNull null
            if (cell.lon < bounds.lonWest || cell.lon > bounds.lonEast) return@mapNotNull null
            val solid = if (cell.unconfirmed) null else PrecipColors.forRate(cell.mmPerHour)
            val possible = if (solid != null) null
                else (if (cell.unconfirmed) cell.mmPerHour else cell.upperMmPerHour)?.let(PrecipColors::forRate)
            val colour = solid ?: possible ?: return@mapNotNull null
            projection.toPixels(GeoPoint(cell.lat, cell.lon), point)
            val strength = if (solid != null) alpha else alpha * POSSIBLE_ALPHA_NUMERATOR / 10
            Triple(point.x.toFloat(), point.y.toFloat(), colour.toArgb((strength * colour.alpha * fade).toInt()))
        }
        if (placed.isEmpty()) return
        // One bitmap pixel per grid cell, drawn scaled with filtering: the edges soften and the grid
        // still reads as coarser than radar, where squares read as pixel blocks.
        val left = placed.minOf { it.first } - side / 2f
        val top = placed.minOf { it.second } - side / 2f
        val cols = ((placed.maxOf { it.first } - left) / side).toInt() + 2
        val rows = ((placed.maxOf { it.second } - top) / side).toInt() + 2
        val bitmap = createBitmap(cols, rows)
        placed.forEach { (x, y, argb) ->
            bitmap[((x - left) / side).toInt().coerceIn(0, cols - 1), ((y - top) / side).toInt().coerceIn(0, rows - 1)] = argb
        }
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + cols * side, top + rows * side), bitmapPaint)
        bitmap.recycle()
    }

    /** [km] of ground, in pixels, measured off the projection at this latitude. */
    private fun cellSidePx(projection: Projection, lat: Double, km: Double): Float {
        val a = android.graphics.Point().also { projection.toPixels(GeoPoint(lat, 11.0), it) }
        val b = android.graphics.Point().also { projection.toPixels(GeoPoint(lat, 11.0 + ONE_KM_OF_LON * km), it) }
        return max(1f, abs(b.x - a.x).toFloat())
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
