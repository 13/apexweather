package it.apexweather.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import org.osmdroid.util.PointL
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
 * [bitmapLayout] was whether two grid cells could round into the same bitmap pixel — 61 of the 801
 * did at zoom 9, against `Projection.toPixels`' int-truncated pixel position. Shrinking `side`
 * to spread cells out (the brief's own suggested `side * 0.9f`, then `0.8f`, the first value that
 * reached zero) was tried and rejected on the evidence of the recorded PNG: it opens a regular
 * lattice of empty bitmap pixels through the field's *interior*, a visible mesh of dark seams
 * cutting across the rain — worse than the coincidence it was meant to fix. The real cause was
 * cheaper to fix than to work around: `Projection.toPixels` truncates to an `Int` before this
 * overlay ever sees the position, and INCA's grid is its own projection resampled to lat/lon
 * (measured against this fixture: no two of its 1 638 points share a latitude or a longitude), so
 * two genuinely distinct cells less than a pixel apart truncated to the same integer. `projectPrecise`
 * (mirroring `NowcastOverlay`'s own private method of the same name) uses `toProjectedPixels`'
 * zoom-independent, high-precision Mercator position instead, converted to a full-precision screen
 * float with the projection's own scale and offset rather than its own truncating `toPixels` — and
 * [collisions] now measures **zero** at both zoom 9 (801 placed) and zoom 13 (104 placed).
 *
 * **Zero collisions was not the whole story.** Looking at the recorded PNG after that fix (not
 * only trusting the count) found a different, smaller defect it had uncovered rather than caused:
 * about seven single-pixel dark dimples inside the zoom-9 field, where the raster simply had no
 * cell close enough to claim a given interior pixel — an inherent consequence of tiling a rotated
 * native grid onto an axis-aligned one, not a truncation bug. `NowcastOverlay.fillHoles` closes a
 * transparent pixel with the average of its orthogonal neighbours wherever at least three of them
 * already have a colour, which [dimples] checks for directly rather than trusting the collision
 * count alone: a non-background pixel whose luminance is more than 18 below the mean of its eight
 * neighbours, with at least three non-background orthogonal ones — the same criterion used to spot
 * them by eye in the first place.
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
        canvas.drawColor(BACKGROUND)
        NowcastOverlay(step, ALPHA).draw(canvas, map, false)
        return bitmap
    }

    /**
     * Reproduces `NowcastOverlay.cellSidePx`/`projectPrecise` for INCA's one-kilometre grid,
     * because those are private to the overlay and this assertion needs the same sub-pixel
     * placement its own bitmap maths uses, not an independent guess at it — `toPixels`' own
     * int-truncated pixel would under-count exactly the collisions this test exists to catch.
     */
    private fun projectPrecise(projection: Projection, lat: Double, lon: Double, reuse: PointL): Pair<Float, Float> {
        projection.toProjectedPixels(lat, lon, reuse)
        val power = projection.projectedPowerDifference
        return (reuse.x / power + projection.offsetX).toFloat() to (reuse.y / power + projection.offsetY).toFloat()
    }

    private fun cellSidePx(projection: Projection, lat: Double, reuse: PointL): Float {
        val (ax, _) = projectPrecise(projection, lat, 11.0, reuse)
        val (bx, _) = projectPrecise(projection, lat, 11.0 + ONE_KM_OF_LON, reuse)
        return max(1f, abs(bx - ax))
    }

    /**
     * How many of the cells [NowcastOverlay] would actually place on screen land in the same
     * bitmap pixel as another one — the thing "holes in a field of rain" looks like from the data
     * side, since two cells sharing a slot means one of their colours silently overwrote the other.
     */
    private fun collisions(map: MapView): Pair<Int, Int> {
        val projection = map.projection
        val bounds = map.boundingBox
        val reuse = PointL()
        val side = cellSidePx(projection, step.cells.first().lat, reuse)
        val placed = step.cells.mapNotNull { cell ->
            if (cell.lat < bounds.latSouth || cell.lat > bounds.latNorth) return@mapNotNull null
            if (cell.lon < bounds.lonWest || cell.lon > bounds.lonEast) return@mapNotNull null
            val solid = if (cell.unconfirmed) null else PrecipColors.forRate(cell.mmPerHour)
            val possible = if (solid != null) null
                else (if (cell.unconfirmed) cell.mmPerHour else cell.upperMmPerHour)?.let(PrecipColors::forRate)
            if (solid == null && possible == null) return@mapNotNull null
            projectPrecise(projection, cell.lat, cell.lon, reuse)
        }
        if (placed.isEmpty()) return 0 to 0
        val layout = bitmapLayout(placed.map { it.first }, placed.map { it.second }, side)
        val slots = placed.map { (x, y) -> layout.column(x) to layout.row(y) }
        return placed.size to (slots.size - slots.toSet().size)
    }

    /** ITU-R BT.601 luminance; unweighted would call a saturated blue as bright as a saturated green. */
    private fun luminance(color: Int): Double {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /**
     * How many pixels in [bitmap] read as a dark dimple: not the background, at least three of its
     * four orthogonal neighbours are not the background either (so it sits inside filled territory
     * rather than at an edge), and its own luminance is more than 18 below the mean of all eight
     * neighbours around it — noticeably darker than the field it is surrounded by. The interior
     * only, since a pixel on the canvas edge has fewer than eight neighbours and the field is never
     * drawn out to the canvas edge in these fixtures.
     */
    private fun dimples(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        fun at(x: Int, y: Int) = pixels[y * width + x]
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val centre = at(x, y)
                if (centre == BACKGROUND) continue
                val orthogonal = listOf(at(x - 1, y), at(x + 1, y), at(x, y - 1), at(x, y + 1))
                if (orthogonal.count { it != BACKGROUND } < 3) continue
                val neighbours = listOf(
                    at(x - 1, y - 1), at(x, y - 1), at(x + 1, y - 1),
                    at(x - 1, y), at(x + 1, y),
                    at(x - 1, y + 1), at(x, y + 1), at(x + 1, y + 1),
                )
                val meanLuminance = neighbours.sumOf(::luminance) / neighbours.size
                if (meanLuminance - luminance(centre) > DIMPLE_LUMINANCE_DROP) count++
            }
        }
        return count
    }

    @Test
    fun `zoom 9, the whole recorded field, soft on every edge, no cell overwriting another, no dimples`() {
        val map = mapAt(9.0)
        val bitmap = render(map)
        bitmap.captureRoboImage("src/test/screenshots/map_nowcast_z9.png")
        val (placed, collided) = collisions(map)
        println("zoom 9: $placed cells placed, $collided collisions")
        assertNoCollisions(placed, collided)
        val dimpleCount = dimples(bitmap)
        println("zoom 9: $dimpleCount dimples")
        assertNoDimples(dimpleCount)
    }

    @Test
    fun `zoom 13, magnified, still no cell overwriting another, no dimples`() {
        val map = mapAt(13.0)
        val bitmap = render(map)
        bitmap.captureRoboImage("src/test/screenshots/map_nowcast_z13.png")
        val (placed, collided) = collisions(map)
        println("zoom 13: $placed cells placed, $collided collisions")
        assertNoCollisions(placed, collided)
        val dimpleCount = dimples(bitmap)
        println("zoom 13: $dimpleCount dimples")
        assertNoDimples(dimpleCount)
    }

    private fun assertNoCollisions(placed: Int, collided: Int) {
        org.junit.Assert.assertTrue("$placed cells placed but none had a colour to check", placed > 0)
        org.junit.Assert.assertEquals("$collided of $placed placed cells shared a bitmap slot with another", 0, collided)
    }

    private fun assertNoDimples(dimpleCount: Int) {
        org.junit.Assert.assertEquals("$dimpleCount pixels read as a dark dimple in the interior", 0, dimpleCount)
    }

    private companion object {
        const val SIZE = 800
        const val ALPHA = 130
        const val ONE_KM_OF_LON = 0.01306

        /** How far below its neighbourhood's mean luminance a pixel has to sit to read as a dimple. */
        const val DIMPLE_LUMINANCE_DROP = 18.0

        /** Matches `render`'s canvas fill; a pixel exactly this colour was never touched by the overlay. */
        val BACKGROUND = Color.rgb(20, 24, 36)
    }
}
