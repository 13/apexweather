package it.apexweather.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import it.apexweather.RadarFixtures
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.DORF_TIROL
import it.apexweather.ui.map.FrameLayers
import it.apexweather.ui.map.RadarOverlay
import it.apexweather.ui.map.RadarTileKey
import it.apexweather.ui.map.RadarTileStore
import it.apexweather.ui.map.provinceTileRect
import it.apexweather.ui.map.radarTileRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.PointL
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor

/**
 * Where [RadarOverlay] puts the radar, and how its edge fades.
 *
 * The overlay draws zoom-7 tiles itself rather than through osmdroid's tile machinery, so nothing
 * else checks that a tile lands on the ground it pictures. Two references, because one is not
 * enough: osmdroid's own `Projection.getPixelFromTile` (to within its own int truncation), and the
 * exact position — the tile's Mercator pixel at this zoom plus the projection's offset, in doubles.
 * The first catches a tile in the wrong place; the second catches a placement that truncates to an
 * int, which stays within a pixel of `getPixelFromTile` because that truncates too.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class RadarOverlayTest {

    private val frame = RadarFrame(Instant.parse("2026-09-14T04:30:00Z"), "https://example.invalid/0430")

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

    private fun placed(map: MapView, x: Int, y: Int) = RectF().also { radarTileRect(map.projection, x, y, PointL(), it) }

    private fun assertPlacement(zoom: Double) {
        val map = mapAt(zoom)
        val projection = map.projection
        val actual = placed(map, 67, 45)

        // osmdroid's own tile grid is the integer zoom below; tile (67, 45) at zoom 7 is tile
        // (67, 45) shifted left by the difference there, and its far corner is the next one's near.
        val shift = floor(zoom).toInt() - 7
        val near = projection.getPixelFromTile(67 shl shift, 45 shl shift, null)
        val far = projection.getPixelFromTile(68 shl shift, 46 shl shift, null)
        val tolerance = 1.0f
        assertEquals("left at $zoom", near.left.toFloat(), actual.left, tolerance)
        assertEquals("top at $zoom", near.top.toFloat(), actual.top, tolerance)
        assertEquals("right at $zoom", far.left.toFloat(), actual.right, tolerance)
        assertEquals("bottom at $zoom", far.top.toFloat(), actual.bottom, tolerance)

        // The exact position: the Mercator pixel of the tile's corner at this zoom, plus the offset.
        val mapSize = TileSystem.MapSize(zoom)
        val exactLeft = 67.0 / 128.0 * mapSize + projection.offsetX
        val exactTop = 45.0 / 128.0 * mapSize + projection.offsetY
        assertTrue("left at $zoom is ${actual.left}, exactly $exactLeft", abs(actual.left - exactLeft) < EXACT)
        assertTrue("top at $zoom is ${actual.top}, exactly $exactTop", abs(actual.top - exactTop) < EXACT)
    }

    @Test fun `tile 67-45 lands where osmdroid puts it at zoom 7`() = assertPlacement(7.0)

    @Test fun `tile 67-45 lands where osmdroid puts it at zoom 12`() = assertPlacement(12.0)

    @Test fun `tile 67-45 lands where osmdroid puts it at a fractional zoom`() = assertPlacement(9.5)

    private fun storeWith(vararg tiles: Pair<Pair<Int, Int>, Bitmap>) = RadarTileStore().apply {
        tiles.forEach { (xy, bitmap) -> put(RadarTileKey(frame.time, xy.first, xy.second), bitmap) }
    }

    private fun render(map: MapView, store: RadarTileStore, background: Int, size: Int = SIZE): Pair<Bitmap, RadarOverlay> {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val overlay = RadarOverlay(frame, store, FrameLayers.RADAR_ALPHA)
        overlay.draw(canvas, map, false)
        return bitmap to overlay
    }

    /**
     * This morning's 04:30Z tile over Dorf Tirol at zoom 7, with the other three tiles empty, over the
     * basemap's dark. The whole four-tile rectangle is on screen here, so its feathered edge is too.
     */
    @Test
    fun `zoom 7, this morning's tile over the province, feathered at the edge`() {
        val bytes = RadarFixtures.bytes("radar-z7-67-45-0430Z.png")
        val tile = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val map = mapAt(7.0)
        val (bitmap, overlay) = render(map, storeWith((67 to 45) to tile), BACKGROUND)
        bitmap.captureRoboImage("src/test/screenshots/map_radar_z7.png")
        assertTrue("the rectangle is on screen at zoom 7, so the edge must be masked", overlay.maskedLastDraw)
    }

    /**
     * The fade, measured: solid tiles everywhere over transparent, then the alpha read along a line
     * into the rectangle from its left edge. Nothing at the edge, about half at half the feather,
     * the radar's full alpha from the feather on. At xhdpi 20 dp is 40 px.
     */
    @Test
    fun `the edge fades over twenty dp and the inside is untouched`() {
        val solid = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val store = storeWith((67 to 44) to solid, (68 to 44) to solid, (67 to 45) to solid, (68 to 45) to solid)
        val map = mapAt(7.0)
        val (bitmap, _) = render(map, store, Color.TRANSPARENT)
        val outer = RectF().also { provinceTileRect(map.projection, it) }
        val feather = RadarOverlay.FEATHER_DP * map.context.resources.displayMetrics.density
        assertEquals("xhdpi", 40f, feather, 0.01f)
        val y = outer.centerY().toInt()
        fun alphaAt(dx: Float) = Color.alpha(bitmap.getPixel((outer.left + dx).toInt(), y))
        val full = (255 * FrameLayers.RADAR_ALPHA).toInt()
        assertTrue("at the edge: ${alphaAt(0.5f)}", alphaAt(0.5f) < 12)
        assertEquals("halfway through the feather", full / 2f, alphaAt(feather / 2).toFloat(), 12f)
        assertEquals("past the feather", full.toFloat(), alphaAt(feather + 4).toFloat(), 3f)
        assertEquals("well inside", full.toFloat(), Color.alpha(bitmap.getPixel(outer.centerX().toInt(), y)).toFloat(), 3f)
    }

    /** From about zoom 10 the rectangle covers the viewport, and the mask must cost nothing there. */
    @Test
    fun `at zoom 12 the rectangle covers the view and no mask is drawn`() {
        val solid = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val map = mapAt(12.0)
        val (_, overlay) = render(map, storeWith((67 to 45) to solid), BACKGROUND)
        assertFalse(overlay.maskedLastDraw)
    }

    private companion object {
        const val SIZE = 800

        /** Sub-pixel: a placement truncated to an int is up to a whole pixel off at a fractional zoom. */
        const val EXACT = 0.05

        val BACKGROUND = Color.rgb(20, 24, 36)
    }
}
