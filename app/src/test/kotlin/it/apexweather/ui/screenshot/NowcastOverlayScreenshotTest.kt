package it.apexweather.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import it.apexweather.Fixtures
import it.apexweather.data.remote.NowcastMapper
import it.apexweather.data.remote.NowcastResponse
import it.apexweather.data.remote.NowcastStep
import it.apexweather.domain.DORF_TIROL
import it.apexweather.ui.map.NowcastOverlay
import it.apexweather.ui.map.PrecipColors
import it.apexweather.ui.map.bitmapLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/**
 * `NowcastOverlay` painted against a real, recorded field of rain, at the two zooms Task 12's
 * phone check covers.
 *
 * `map-2026-09-14/inca-0500Z.json` is this morning's INCA run, recorded live rather than
 * synthesised, and unlike the day this task was first implemented it has rain in it: 05:45Z carries
 * 0,96 mm/h at the cell nearest Dorf Tirol and 801 wet cells across the box, so there is an actual
 * field to look at rather than an early return on an empty `placed` list.
 *
 * **Collisions were measured, not assumed away.** The obvious next question after adding
 * [bitmapLayout] was whether two grid cells could round into the same bitmap pixel — 61 of the
 * 801 do at zoom 9, because INCA's grid is its own projection resampled to lat/lon rather than an
 * axis-aligned rectangle, and one scalar pixel width cannot place every cell exactly. Shrinking
 * that width to spread them out (the brief's own suggested `side * 0.9f`, then `0.8f`, the first
 * value that reached zero) was tried and rejected on the evidence of the recorded PNG: it opens a
 * regular lattice of empty bitmap pixels through the field's *interior*, which showed up as a
 * visible mesh of dark seams cutting across the rain rather than a soft edge around it — worse
 * than the coincidence it was meant to fix. A collision between two adjacent, similarly-coloured
 * cells is invisible in the rendered field, so [collisions] measures it and asserts it stays the
 * small minority it was measured at, rather than chasing zero at the cost of the image.
 *
 * Record after a deliberate change with `./gradlew :app:recordRoborazziDebug`, and look at the
 * result before committing it — a golden nobody looked at proves nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class NowcastOverlayScreenshotTest {

    private val step: NowcastStep = NowcastMapper.map(
        Fixtures.json.decodeFromString(NowcastResponse.serializer(), Fixtures.read("map-2026-09-14/inca-0500Z.json")),
    ).steps.first { it.time == Instant.parse("2026-09-14T05:45:00Z") }

    private fun mapAt(zoom: Double, size: Int = SIZE): MapView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val map = MapView(context)
        map.setUseDataConnection(false)
        val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        map.measure(spec, spec)
        map.layout(0, 0, size, size)
        map.controller.setZoom(zoom)
        map.controller.setCenter(GeoPoint(DORF_TIROL.lat, DORF_TIROL.lon))
        return map
    }

    private fun render(map: MapView, size: Int = SIZE): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // A dark background, as the map's own basemap is: a transparent bitmap drawn over white
        // would not show whether the corners of its border cells are really transparent.
        canvas.drawColor(Color.rgb(20, 24, 36))
        NowcastOverlay(step, ALPHA).draw(canvas, map, false)
        return bitmap
    }

    /**
     * Reproduces `NowcastOverlay.cellSidePx` for INCA's one-kilometre grid, because that method is
     * private to the overlay and this assertion needs the same pixel width its own bitmap maths
     * used, not an independent guess at it.
     */
    private fun cellSidePx(projection: Projection, lat: Double): Float {
        val a = Point().also { projection.toPixels(GeoPoint(lat, 11.0), it) }
        val b = Point().also { projection.toPixels(GeoPoint(lat, 11.0 + ONE_KM_OF_LON), it) }
        return max(1f, abs(b.x - a.x).toFloat())
    }

    /**
     * How many of the cells [NowcastOverlay] would actually place on screen land in the same
     * bitmap pixel as another one — the thing "holes in a field of rain" looks like from the data
     * side, since two cells sharing a slot means one of their colours silently overwrote the other.
     */
    private fun collisions(map: MapView): Pair<Int, Int> {
        val projection = map.projection
        val bounds = map.boundingBox
        val side = cellSidePx(projection, step.cells.first().lat)
        val point = Point()
        val placed = step.cells.mapNotNull { cell ->
            if (cell.lat < bounds.latSouth || cell.lat > bounds.latNorth) return@mapNotNull null
            if (cell.lon < bounds.lonWest || cell.lon > bounds.lonEast) return@mapNotNull null
            val solid = if (cell.unconfirmed) null else PrecipColors.forRate(cell.mmPerHour)
            val possible = if (solid != null) null
                else (if (cell.unconfirmed) cell.mmPerHour else cell.upperMmPerHour)?.let(PrecipColors::forRate)
            if (solid == null && possible == null) return@mapNotNull null
            projection.toPixels(GeoPoint(cell.lat, cell.lon), point)
            point.x.toFloat() to point.y.toFloat()
        }
        if (placed.isEmpty()) return 0 to 0
        val layout = bitmapLayout(placed.map { it.first }, placed.map { it.second }, side)
        val slots = placed.map { (x, y) -> layout.column(x) to layout.row(y) }
        return placed.size to (slots.size - slots.toSet().size)
    }

    @Test
    fun `zoom 9, the whole recorded field, soft on every edge and collisions a small minority`() {
        val map = mapAt(9.0)
        render(map).captureRoboImage("src/test/screenshots/map_nowcast_z9.png")
        val (placed, collided) = collisions(map)
        println("zoom 9: $placed cells placed, $collided collisions")
        assertCollisionsStayRare(placed, collided)
    }

    @Test
    fun `zoom 13, magnified, collisions still a small minority`() {
        val map = mapAt(13.0)
        render(map).captureRoboImage("src/test/screenshots/map_nowcast_z13.png")
        val (placed, collided) = collisions(map)
        println("zoom 13: $placed cells placed, $collided collisions")
        assertCollisionsStayRare(placed, collided)
    }

    /**
     * A regression guard, not a claim that collisions cannot happen: see the class doc for why
     * zero was tried and rejected. [COLLISION_FRACTION_CEILING] is double the 7,6 % measured at
     * zoom 9 (0 % at zoom 13) against this fixture, room enough for a different recorded field
     * without room enough to hide the old asymmetric-margin bug, which clipped far more than a
     * tenth of the field.
     */
    private fun assertCollisionsStayRare(placed: Int, collided: Int) {
        org.junit.Assert.assertTrue("$placed cells placed but none had a colour to check", placed > 0)
        org.junit.Assert.assertTrue(
            "$collided of $placed placed cells shared a bitmap slot with another, more than the ${(COLLISION_FRACTION_CEILING * 100).toInt()} % ceiling",
            collided <= placed * COLLISION_FRACTION_CEILING,
        )
    }

    private companion object {
        const val SIZE = 800
        const val ALPHA = 130
        const val ONE_KM_OF_LON = 0.01306
        const val COLLISION_FRACTION_CEILING = 0.15
    }
}
