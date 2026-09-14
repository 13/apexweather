package it.apexweather.ui.map

import android.animation.ValueAnimator
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import it.apexweather.data.remote.RainViewerMapper
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import java.time.Instant
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/**
 * The rain layer of the MapView: one frame on screen, the next one faded in over it, and every
 * frame of the zoom's tiles fetched before the loop needs them.
 *
 * The old code removed the overlay and added the next on every frame, so each frame arrived as a
 * blank map until its tiles did and there was no transition at all. A provider per frame is kept
 * for the life of the frame list, which is what lets the tiles already be there.
 */
internal class FrameLayers(private val map: MapView) {

    private val providers = HashMap<Instant, MapTileProviderBasic>()
    private var shown: Overlay? = null
    private var shownTime: Instant? = null
    private var fading: ValueAnimator? = null

    fun show(frame: MapFrame?, motion: Boolean) {
        if (frame?.time == shownTime && frame != null) return
        fading?.end()
        val incoming = frame?.let(::overlayFor)
        val outgoing = shown
        shown = incoming
        shownTime = frame?.time
        if (incoming != null) map.overlays.add(0, incoming)
        if (!motion || outgoing == null || incoming == null) {
            outgoing?.let { map.overlays.remove(it) }
            incoming?.let { setFade(it, 1f) }
            map.invalidate()
            return
        }
        setFade(incoming, 0f)
        fading = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            addUpdateListener { a ->
                setFade(incoming, a.animatedValue as Float)
                setFade(outgoing, 1f - (a.animatedValue as Float))
                map.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    map.overlays.remove(outgoing)
                    map.invalidate()
                }
            })
            start()
        }
    }

    /**
     * Asks every radar frame from [from] onwards for the tiles the map is showing, parents included.
     * True once the next [READY_AHEAD] frames have them in memory.
     */
    fun preload(frames: List<MapFrame>, from: Int): Boolean {
        val zoom = minOf(map.zoomLevelDouble.toInt(), RainViewerMapper.MAX_ZOOM)
        val box = map.boundingBox
        val indices = buildList {
            for (x in tileX(box.lonWest, zoom)..tileX(box.lonEast, zoom)) {
                for (y in tileY(box.latNorth, zoom)..tileY(box.latSouth, zoom)) add(MapTileIndex.getTileIndex(zoom, x, y))
            }
        }
        var ready = true
        frames.drop(from.coerceAtLeast(0)).filterIsInstance<MapFrame.Observed>().forEachIndexed { n, frame ->
            val provider = providerFor(frame)
            indices.forEach { provider.getMapTile(it) }
            if (map.zoomLevelDouble > RainViewerMapper.MAX_ZOOM) provider.fetchRadarParents(map)
            if (n < READY_AHEAD && indices.any { provider.tileCache.getMapTile(it) == null }) ready = false
        }
        providers.entries.removeAll { (t, provider) ->
            frames.none { it.time == t }.also { gone -> if (gone) provider.detach() }
        }
        return ready
    }

    fun release() {
        fading?.cancel()
        providers.values.forEach { it.detach() }
        providers.clear()
    }

    private fun providerFor(frame: MapFrame.Observed): MapTileProviderBasic =
        providers.getOrPut(frame.time) {
            MapTileProviderBasic(map.context, RadarTileSource(frame.radar)).apply {
                setTileRequestCompleteHandler(map.tileRequestCompleteHandler)
                tileCache.ensureCapacity(TILE_CAPACITY)
            }
        }

    private fun overlayFor(frame: MapFrame): Overlay = when (frame) {
        is MapFrame.Observed -> TilesOverlay(providerFor(frame), map.context).apply {
            loadingBackgroundColor = android.graphics.Color.TRANSPARENT
            providerFor(frame).fetchRadarParents(map)
        }
        is MapFrame.Forecast -> NowcastOverlay(frame.step, NOWCAST_ALPHA)
    }

    private fun setFade(overlay: Overlay, f: Float) {
        when (overlay) {
            is TilesOverlay -> overlay.setColorFilter(radarFilter(RADAR_ALPHA * f))
            is NowcastOverlay -> overlay.fade = f
        }
    }

    private fun radarFilter(alpha: Float) = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, alpha, 0f)),
    )

    private fun tileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun tileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    companion object {
        const val FADE_MS = 250L
        const val READY_AHEAD = 3

        /** One zoom's worth: thirteen frames of a handful of tiles each, and no more. */
        const val TILE_CAPACITY = 40

        /** How much of the radar is let through, so the valley under the rain stays visible. */
        const val RADAR_ALPHA = 0.62f

        /** And of the forecast, out of 255: a shade lighter than the radar on purpose. */
        const val NOWCAST_ALPHA = 130
    }
}
