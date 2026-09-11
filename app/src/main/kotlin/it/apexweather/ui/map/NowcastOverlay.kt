package it.apexweather.ui.map

import android.graphics.Canvas
import android.graphics.Paint
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
 * One filled square per grid point rather than a smoothed field, because a smoothed field would
 * look like radar and this is not radar: it is a 1 km model saying where the rain will be, and the
 * blockiness is an honest statement of what it knows. It is drawn under the same alpha the radar
 * carries, so the relief the reader is placing the rain against stays visible through both.
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
    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || cells.isEmpty()) return
        val projection = map.projection
        val side = cellSidePx(projection, cells.first().lat, cellKm)
        val half = side / 2f
        val point = android.graphics.Point()
        val bounds = map.boundingBox
        cells.forEach { cell ->
            // Off-screen cells are the majority once the reader zooms in, and projecting them is
            // the expensive part of this loop.
            if (cell.lat < bounds.latSouth || cell.lat > bounds.latNorth) return@forEach
            if (cell.lon < bounds.lonWest || cell.lon > bounds.lonEast) return@forEach
            // The middle of the ensemble where it has rain, and otherwise the wetter end of it at a
            // fraction of the strength: cells the median calls dry and the ninetieth percentile
            // calls wet are where the rain *might* reach, and they are not the same claim. Drawn
            // pale rather than not at all, because "the median says no" is not "no".
            val solid = PrecipColors.forRate(cell.mmPerHour)
            val possible = if (solid != null) null else cell.upperMmPerHour?.let(PrecipColors::forRate)
            val colour = solid ?: possible ?: return@forEach
            projection.toPixels(GeoPoint(cell.lat, cell.lon), point)
            paint.color = colour.toArgb(if (solid != null) alpha else alpha * POSSIBLE_ALPHA_NUMERATOR / 10)
            canvas.drawRect(point.x - half, point.y - half, point.x + half, point.y + half, paint)
        }
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
