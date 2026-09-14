package it.apexweather.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import it.apexweather.data.remote.RadarFrame
import it.apexweather.data.remote.RainViewerMapper
import it.apexweather.domain.SouthTyrol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.util.PointL
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/** One radar tile: a frame's zoom-7 picture at column [x], row [y]. The frame's time keeps frames apart. */
internal data class RadarTileKey(val time: Instant, val x: Int, val y: Int)

/**
 * The radar's zoom-7 tiles, every frame's, held in memory and fetched without osmdroid.
 *
 * **RainViewer's radar stops at zoom 7, and so does the map's own minimum zoom.** Every radar pixel
 * the reader ever sees is therefore a zoom-7 tile scaled up, which is all [RadarOverlay] does. The
 * first version handed each frame to osmdroid as a tile source of its own, and osmdroid spent five
 * tile modules with their own thread pools per frame on it — thirteen `MapTileProviderBasic`s, 338
 * threads and 548 MB PSS after a minute of playback on the phone — to upscale the same four tiles
 * into a screenful of display-zoom copies per frame. Keyed by frame time, a tile cannot be shown for
 * another frame by construction.
 *
 * Only the tiles inside the province are asked for (see [radarTileRange]): that is four, so the
 * whole loop is 52 bitmaps of 256 px, about 13 MB. Nothing is written to disk — the frames are two
 * hours of imagery, and a stored tile would name a picture nobody can fetch again.
 *
 * **Held by `MapViewModel`, not by the map view**, so a trip to another tab and back does not
 * download the loop again: the view is rebuilt on every visit and the ViewModel is not. The store is
 * released with the ViewModel, and [FrameLayers] still evicts a frame's tiles when it leaves the
 * frame list.
 *
 * Main-thread confined: everything here is called from the MapView's thread, and a fetch comes back
 * to it before touching the map.
 */
class RadarTileStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val network = Dispatchers.IO.limitedParallelism(MAX_PARALLEL)
    private val bitmaps = HashMap<RadarTileKey, Bitmap>()
    private val inFlight = HashSet<RadarTileKey>()
    private val failedAt = HashMap<RadarTileKey, Long>()
    private var kept: Set<Instant> = emptySet()

    /** Called when a tile lands; the map view on screen sets it, and clears it when it goes. */
    internal var onTile: (() -> Unit)? = null

    internal val heldFrames: Set<Instant> get() = bitmaps.keys.mapTo(HashSet()) { it.time }

    internal fun get(key: RadarTileKey): Bitmap? = bitmaps[key]

    internal fun has(frame: RadarFrame, tiles: List<Pair<Int, Int>>): Boolean =
        tiles.all { (x, y) -> RadarTileKey(frame.time, x, y) in bitmaps }

    /** A tile put straight in, as a test hands one over without a network. */
    internal fun put(key: RadarTileKey, bitmap: Bitmap) {
        bitmaps[key] = bitmap
    }

    /** Drops [frames]' tiles. */
    internal fun evict(frames: Set<Instant>) {
        if (frames.isEmpty()) return
        bitmaps.keys.removeAll { it.time in frames }
        failedAt.keys.removeAll { it.time in frames }
    }

    /** Which frames a tile arriving from the network may still be stored for. */
    internal fun admit(frames: Set<Instant>) {
        kept = frames
    }

    /**
     * Fetches whatever of [tiles] this frame does not have and is not already fetching. A tile that
     * failed is not asked for again for [RETRY_AFTER_MS]: this runs on every pan, and offline that
     * would otherwise be a request per tile per scroll event.
     */
    internal fun request(frame: RadarFrame, tiles: List<Pair<Int, Int>>) {
        val now = SystemClock.elapsedRealtime()
        for ((x, y) in tiles) {
            val key = RadarTileKey(frame.time, x, y)
            if (key in bitmaps || key in inFlight) continue
            if (failedAt[key]?.let { now - it < RETRY_AFTER_MS } == true) continue
            inFlight += key
            scope.launch {
                val bitmap = withContext(network) { runCatching { download(frame.tileUrl(RainViewerMapper.MAX_ZOOM, x, y)) }.getOrNull() }
                inFlight -= key
                when {
                    bitmap == null -> failedAt[key] = SystemClock.elapsedRealtime()
                    key.time in kept -> {
                        failedAt -= key
                        bitmaps[key] = bitmap
                        onTile?.invoke()
                    }
                }
            }
        }
    }

    internal fun release() {
        scope.cancel()
        onTile = null
        bitmaps.clear()
        inFlight.clear()
        failedAt.clear()
    }

    private fun download(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", Configuration.getInstance().userAgentValue)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_PARALLEL = 4
        const val TIMEOUT_MS = 10_000
        const val RETRY_AFTER_MS = 30_000L
    }
}

/**
 * One radar frame, drawn as its zoom-7 tiles scaled to wherever the map is.
 *
 * Unfiltered, as osmdroid's approximater drew them: at zoom 12 a radar pixel is thirty-two screen
 * pixels of the same colour, and a filter would pretend to a smoothness the radar does not have.
 * RainViewer's own `smooth` option has already softened the edges inside the tile.
 *
 * **The edge of the four tiles is feathered over [FEATHER_DP].** Only the province's four tiles
 * are fetched, and at zoom 7 to 9 their rectangle (8,44–14,06 °E × 45,09–48,92 °N) is on screen: rain
 * arriving from Lombardy or Switzerland stopped at a hard straight line, which reads as a fault.
 * Faded out it reads as where the picture ends. More tiles would cost about 30 MB and 2,25 times
 * the data for weather outside the province. The fade is in dp, so the edge looks the same at every
 * zoom, and it costs nothing once the rectangle covers the whole viewport — from about zoom 10 on.
 */
internal class RadarOverlay(private val frame: RadarFrame, private val store: RadarTileStore, private val alpha: Float) : Overlay() {

    /** [FrameLayers]' crossfade: 1 at full strength, fading to 0 as this frame gives way to the next. */
    var fade: Float = 1f

    /** Whether the last draw applied the edge mask; a test reads it. */
    internal var maskedLastDraw: Boolean = false
        private set

    private val paint = Paint().apply { isFilterBitmap = false }
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val rect = RectF()
    private val outer = RectF()
    private val layer = RectF()
    private val reuse = PointL()

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        maskedLastDraw = false
        if (shadow) return
        paint.alpha = (255 * alpha * fade).toInt().coerceIn(0, 255)
        if (paint.alpha == 0) return
        val projection = map.projection
        val box = map.boundingBox
        val tiles = radarTileRange(box.lonWest, box.lonEast, box.latNorth, box.latSouth)
        if (tiles.none { (x, y) -> store.get(RadarTileKey(frame.time, x, y)) != null }) return

        val feather = FEATHER_DP * map.context.resources.displayMetrics.density
        provinceTileRect(projection, outer)
        val width = map.width.toFloat()
        val height = map.height.toFloat()
        val covers = outer.left + feather <= 0f && outer.top + feather <= 0f &&
            outer.right - feather >= width && outer.bottom - feather >= height
        var saved = -1
        if (!covers) {
            layer.set(maxOf(outer.left, 0f), maxOf(outer.top, 0f), minOf(outer.right, width), minOf(outer.bottom, height))
            if (layer.isEmpty) return
            saved = canvas.saveLayer(layer, null)
        }
        for ((x, y) in tiles) {
            val bitmap = store.get(RadarTileKey(frame.time, x, y)) ?: continue
            radarTileRect(projection, x, y, reuse, rect)
            canvas.drawBitmap(bitmap, null, rect, paint)
        }
        if (saved >= 0) {
            // Each edge multiplies what is in the layer by a ramp from nothing to full over the
            // feather; a corner gets the product of its two. Four gradients a draw, no pixel loop.
            edge(canvas, outer.left, 0f, outer.left + feather, 0f)
            edge(canvas, outer.right, 0f, outer.right - feather, 0f)
            edge(canvas, 0f, outer.top, 0f, outer.top + feather)
            edge(canvas, 0f, outer.bottom, 0f, outer.bottom - feather)
            canvas.restoreToCount(saved)
            maskedLastDraw = true
        }
    }

    private fun edge(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        maskPaint.shader = LinearGradient(x0, y0, x1, y1, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(layer, maskPaint)
    }

    companion object {
        /** How far in from the edge of the four tiles the radar fades from nothing to full. */
        const val FEATHER_DP = 20f
    }
}

/**
 * Where zoom-7 tile ([x], [y]) sits on screen, at full sub-pixel precision: its two corners through
 * [projectPrecise], the same arithmetic the forecast grid is placed with.
 */
internal fun radarTileRect(projection: Projection, x: Int, y: Int, reuse: PointL, out: RectF) {
    val (left, top) = projectPrecise(projection, tileLat(y), tileLon(x), reuse)
    val (right, bottom) = projectPrecise(projection, tileLat(y + 1), tileLon(x + 1), reuse)
    out.set(left, top, right, bottom)
}

/** The screen rectangle of all the tiles the province needs: where the radar picture ends. */
internal fun provinceTileRect(projection: Projection, out: RectF) {
    val all = radarTileRange(SouthTyrol.WEST, SouthTyrol.EAST, SouthTyrol.NORTH, SouthTyrol.SOUTH)
    val reuse = PointL()
    val (left, top) = projectPrecise(projection, tileLat(all.minOf { it.second }), tileLon(all.minOf { it.first }), reuse)
    val (right, bottom) = projectPrecise(projection, tileLat(all.maxOf { it.second } + 1), tileLon(all.maxOf { it.first } + 1), reuse)
    out.set(left, top, right, bottom)
}

/**
 * The zoom-7 tiles, as (column, row), that a viewport from [west] to [east] and [north] to [south]
 * needs — clipped to the province first. Outside it the basemap has nothing to draw and the radar
 * is somebody else's weather, and without the clip a map at zoom 7 would ask for three dozen tiles
 * a frame.
 */
internal fun radarTileRange(west: Double, east: Double, north: Double, south: Double): List<Pair<Int, Int>> {
    val w = maxOf(west, SouthTyrol.WEST)
    val e = minOf(east, SouthTyrol.EAST)
    val n = minOf(north, SouthTyrol.NORTH)
    val s = maxOf(south, SouthTyrol.SOUTH)
    if (w > e || s > n) return emptyList()
    val zoom = RainViewerMapper.MAX_ZOOM
    return buildList {
        for (x in tileX(w, zoom)..tileX(e, zoom)) for (y in tileY(n, zoom)..tileY(s, zoom)) add(x to y)
    }
}

private const val TILES_AT_MAX_ZOOM = 1 shl RainViewerMapper.MAX_ZOOM

private fun tileLon(x: Int): Double = x.toDouble() / TILES_AT_MAX_ZOOM * 360.0 - 180.0

private fun tileLat(y: Int): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / TILES_AT_MAX_ZOOM))))

private fun tileX(lon: Double, zoom: Int): Int {
    val n = 1 shl zoom
    return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
}

private fun tileY(lat: Double, zoom: Int): Int {
    val n = 1 shl zoom
    return floor((1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
}
