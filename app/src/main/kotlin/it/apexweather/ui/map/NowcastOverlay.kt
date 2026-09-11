package it.apexweather.ui.map

import android.graphics.Canvas
import android.graphics.Paint
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
    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || cells.isEmpty()) return
        val projection = map.projection
        val side = cellSidePx(projection, cells.first().lat)
        val half = side / 2f
        val point = android.graphics.Point()
        val bounds = map.boundingBox
        cells.forEach { cell ->
            // Off-screen cells are the majority once the reader zooms in, and projecting them is
            // the expensive part of this loop.
            if (cell.lat < bounds.latSouth || cell.lat > bounds.latNorth) return@forEach
            if (cell.lon < bounds.lonWest || cell.lon > bounds.lonEast) return@forEach
            val colour = PrecipColors.forRate(cell.mmPerHour) ?: return@forEach
            projection.toPixels(GeoPoint(cell.lat, cell.lon), point)
            paint.color = colour.toArgb(alpha)
            canvas.drawRect(point.x - half, point.y - half, point.x + half, point.y + half, paint)
        }
    }

    /** One kilometre of ground, in pixels, measured off the projection at this latitude. */
    private fun cellSidePx(projection: Projection, lat: Double): Float {
        val a = android.graphics.Point().also { projection.toPixels(GeoPoint(lat, 11.0), it) }
        val b = android.graphics.Point().also { projection.toPixels(GeoPoint(lat, 11.0 + ONE_KM_OF_LON), it) }
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
    }
}
