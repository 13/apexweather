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

    /** The frame fading out from under [shown], while its own provider is still protected. */
    private var outgoingTime: Instant? = null
    private var fading: ValueAnimator? = null

    /** The observed frame times [requestTiles] was last asked to keep; [onAnimationEnd] reads it. */
    private var lastWanted: Set<Instant> = emptySet()

    /**
     * Set once [release] has run. Every other method becomes a no-op after — `show` does nothing,
     * `requestTiles` fetches nothing, and `nextFramesCached` reports true rather than polling a
     * MapView that no longer has anything under it, so a caller stuck in the preload loop past
     * teardown stops waiting rather than spinning.
     */
    var released: Boolean = false
        private set

    fun show(frame: MapFrame?, motion: Boolean) {
        if (released) return
        if (frame?.time == shownTime) return
        fading?.end()
        val incoming = frame?.let(::overlayFor)
        val outgoing = shown
        val outgoingWasTime = shownTime
        shown = incoming
        shownTime = frame?.time
        if (incoming != null) map.overlays.add(0, incoming)
        if (!motion || outgoing == null || incoming == null) {
            outgoing?.let { map.overlays.remove(it) }
            incoming?.let { setFade(it, 1f) }
            outgoingTime = null
            map.invalidate()
            return
        }
        setFade(incoming, 0f)
        outgoingTime = outgoingWasTime
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
                    val endedTime = outgoingTime
                    outgoingTime = null
                    fading = null
                    // The frame that just finished fading out may have fallen out of the frame
                    // list while it was fading — a pan or a zoom that landed mid-fade, say — so it
                    // is checked against the wanted set again here, not only in requestTiles.
                    if (endedTime != null && endedTime !in lastWanted) providers.remove(endedTime)?.detach()
                    map.invalidate()
                }
            })
            start()
        }
    }

    /**
     * Asks every radar frame from [from] onwards for the tiles the map is showing, parents
     * included, and prunes providers nothing on screen still needs.
     *
     * A provider behind the frame on screen or behind one still fading out is never detached here
     * whatever [frames] says — see [providersToEvict]. The old code evicted purely off the frame
     * list, which could drop the very provider a `TilesOverlay` on screen was reading from.
     */
    fun requestTiles(frames: List<MapFrame>, from: Int) {
        if (released) return
        val indices = tileIndices()
        val wanted = frames.filterIsInstance<MapFrame.Observed>().map { it.time }.toSet()
        lastWanted = wanted
        frames.drop(from.coerceAtLeast(0)).filterIsInstance<MapFrame.Observed>().forEach { frame ->
            val provider = providerFor(frame)
            indices.forEach { provider.getMapTile(it) }
            if (map.zoomLevelDouble > RainViewerMapper.MAX_ZOOM) provider.fetchRadarParents(map)
        }
        val protected = setOfNotNull(shownTime, outgoingTime)
        providersToEvict(providers.keys, wanted, protected).forEach { providers.remove(it)?.detach() }
    }

    /**
     * True once the next [READY_AHEAD] observed frames from [from] have the current viewport's
     * tiles in memory. Never fetches anything itself — [requestTiles] does that — this only reads
     * the cache. False, not true, when the viewport has no tile indices yet: an empty box has
     * nothing cached by definition, and reporting ready on it would clear the play button's ring
     * before there was ever anything to check.
     */
    fun nextFramesCached(frames: List<MapFrame>, from: Int): Boolean {
        if (released) return true
        val indices = tileIndices()
        if (indices.isEmpty()) return false
        var checked = 0
        for (frame in frames.drop(from.coerceAtLeast(0)).filterIsInstance<MapFrame.Observed>()) {
            if (checked >= READY_AHEAD) break
            val provider = providers[frame.time] ?: return false
            if (indices.any { provider.tileCache.getMapTile(it) == null }) return false
            checked++
        }
        return true
    }

    fun release() {
        released = true
        fading?.cancel()
        fading = null
        providers.values.forEach { it.detach() }
        providers.clear()
    }

    /** The current viewport's tile indices at the radar's own zoom ceiling. */
    private fun tileIndices(): List<Long> {
        val zoom = minOf(map.zoomLevelDouble.toInt(), RainViewerMapper.MAX_ZOOM)
        val box = map.boundingBox
        return buildList {
            for (x in tileX(box.lonWest, zoom)..tileX(box.lonEast, zoom)) {
                for (y in tileY(box.latNorth, zoom)..tileY(box.latSouth, zoom)) add(MapTileIndex.getTileIndex(zoom, x, y))
            }
        }
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

        /**
         * How long the preload loop polls [nextFramesCached] before giving up and clearing the
         * ring anyway. Play is not held hostage by a slow network.
         */
        const val PRELOAD_TIMEOUT_MS = 8_000L

        /** One zoom's worth: thirteen frames of a handful of tiles each, and no more. */
        const val TILE_CAPACITY = 40

        /** How much of the radar is let through, so the valley under the rain stays visible. */
        const val RADAR_ALPHA = 0.62f

        /** And of the forecast, out of 255: a shade lighter than the radar on purpose. */
        const val NOWCAST_ALPHA = 130
    }
}

/**
 * Which of the [held] providers no longer earns its place: not [wanted] by the current frame
 * list, and not [protected] — behind the frame on screen right now, or the one still fading out
 * from under it.
 *
 * Pure, so the rule can be proven without a MapView: `requestTiles` used to evict purely off
 * [wanted], which could detach the very provider the overlay currently on screen was reading
 * from — a `TilesOverlay` left pointing at a provider whose tiles are gone.
 */
internal fun providersToEvict(held: Set<Instant>, wanted: Set<Instant>, protected: Set<Instant>): Set<Instant> =
    held - wanted - protected
