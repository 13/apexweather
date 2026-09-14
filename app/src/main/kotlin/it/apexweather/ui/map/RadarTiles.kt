package it.apexweather.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
 * Main-thread confined: [request], [has], [get], [evict] and [admit] are called from the MapView's thread,
 * and the fetch comes back to it before touching the map.
 */
internal class RadarTileStore(private val onTile: () -> Unit) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val network = Dispatchers.IO.limitedParallelism(MAX_PARALLEL)
    private val bitmaps = HashMap<RadarTileKey, Bitmap>()
    private val inFlight = HashSet<RadarTileKey>()
    private val failedAt = HashMap<RadarTileKey, Long>()
    private var kept: Set<Instant> = emptySet()

    val heldFrames: Set<Instant> get() = bitmaps.keys.mapTo(HashSet()) { it.time }

    fun get(key: RadarTileKey): Bitmap? = bitmaps[key]

    fun has(frame: RadarFrame, tiles: List<Pair<Int, Int>>): Boolean =
        tiles.all { (x, y) -> RadarTileKey(frame.time, x, y) in bitmaps }

    /** Drops [frames]' tiles. */
    fun evict(frames: Set<Instant>) {
        if (frames.isEmpty()) return
        bitmaps.keys.removeAll { it.time in frames }
        failedAt.keys.removeAll { it.time in frames }
    }

    /** Which frames a tile arriving from the network may still be stored for. */
    fun admit(frames: Set<Instant>) {
        kept = frames
    }

    /**
     * Fetches whatever of [tiles] this frame does not have and is not already fetching. A tile that
     * failed is not asked for again for [RETRY_AFTER_MS]: this runs on every pan, and offline that
     * would otherwise be a request per tile per scroll event.
     */
    fun request(frame: RadarFrame, tiles: List<Pair<Int, Int>>) {
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
                        onTile()
                    }
                }
            }
        }
    }

    fun release() {
        scope.cancel()
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
 */
internal class RadarOverlay(private val frame: RadarFrame, private val store: RadarTileStore, private val alpha: Float) : Overlay() {

    /** [FrameLayers]' crossfade: 1 at full strength, fading to 0 as this frame gives way to the next. */
    var fade: Float = 1f

    private val paint = Paint().apply { isFilterBitmap = false }
    private val rect = RectF()
    private val reuse = PointL()

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val box = map.boundingBox
        paint.alpha = (255 * alpha * fade).toInt().coerceIn(0, 255)
        if (paint.alpha == 0) return
        val projection = map.projection
        for ((x, y) in radarTileRange(box.lonWest, box.lonEast, box.latNorth, box.latSouth)) {
            val bitmap = store.get(RadarTileKey(frame.time, x, y)) ?: continue
            val (left, top) = projectPrecise(projection, tileLat(y), tileLon(x), reuse)
            val (right, bottom) = projectPrecise(projection, tileLat(y + 1), tileLon(x + 1), reuse)
            rect.set(left, top, right, bottom)
            canvas.drawBitmap(bitmap, null, rect, paint)
        }
    }
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
