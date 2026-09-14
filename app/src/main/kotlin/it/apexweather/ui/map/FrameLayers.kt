package it.apexweather.ui.map

import android.animation.ValueAnimator
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.time.Instant

/**
 * The rain layer of the MapView: one frame on screen, the next one faded in over it, and every
 * radar frame's tiles fetched before the loop needs them.
 *
 * The old code removed the overlay and added the next on every frame, so each frame arrived as a
 * blank map until its tiles did and there was no transition at all. Every radar frame's tiles are
 * kept in one [RadarTileStore] for the life of the frame list, which is what lets them already be
 * there — see that class for why this is not an osmdroid tile provider per frame any more. The
 * store is not this class's to release: it outlives the map view, so a return to the tab finds
 * the loop already downloaded.
 */
internal class FrameLayers(private val map: MapView, private val tiles: RadarTileStore) {

    init {
        tiles.onTile = { map.postInvalidate() }
    }
    private var shown: Overlay? = null
    private var shownTime: Instant? = null

    /** The frame fading out from under [shown], whose tiles are still protected. */
    private var outgoingTime: Instant? = null
    private var fading: ValueAnimator? = null

    /** The radar frame list [requestTiles] was last given; [show]'s fade end reads it. */
    private var lastFrames: List<MapFrame> = emptyList()

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
        // Preload asked for this frame's tiles once. If one of them failed then — a dropped
        // connection for a second — the frame would show with a blank quadrant for the rest of the
        // loop, so it is asked for again here, under the store's own retry cooldown.
        if (frame is MapFrame.Observed) tiles.request(frame.radar, viewportTiles())
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
                    outgoingTime = null
                    fading = null
                    // The frame that just finished fading out may have left the frame list while it
                    // was fading — a refresh that landed mid-fade — so the list is applied again.
                    if (!released) evict()
                    map.invalidate()
                }
            })
            start()
        }
    }

    /**
     * Asks for every radar frame's tiles in [visible] for the ground on screen, starting at [from]
     * and wrapping round (see [preloadOrder]), and drops the tiles of frames [radarFrames] no longer
     * has.
     *
     * Eviction follows the radar frame list, not [visible]: Heute's visible frames are all forecast,
     * and computing it from them dropped every radar frame on the way into Heute and fetched them
     * all again on the way out. The frame on screen and the one fading out are never evicted
     * whatever the list says — see [framesToEvict].
     */
    fun requestTiles(radarFrames: List<MapFrame>, visible: List<MapFrame>, from: Int) {
        if (released) return
        lastFrames = radarFrames
        evict()
        val range = viewportTiles()
        preloadOrder(visible, from).forEach { tiles.request(it.radar, range) }
    }

    /**
     * True once the next [READY_AHEAD] radar frames the loop will reach from [from] — wrapping round,
     * as the loop does — have the viewport's tiles. Never fetches anything itself. False, not true,
     * when the viewport has no tiles yet: an empty box has nothing cached by definition, and
     * reporting ready on it would clear the play button's ring before there was anything to check.
     */
    fun nextFramesCached(visible: List<MapFrame>, from: Int): Boolean {
        if (released) return true
        val range = viewportTiles()
        if (range.isEmpty()) return false
        return preloadOrder(visible, from).take(READY_AHEAD).all { tiles.has(it.radar, range) }
    }

    fun release() {
        released = true
        fading?.cancel()
        fading = null
        tiles.onTile = null
    }

    private fun evict() {
        val protected = setOfNotNull(shownTime, outgoingTime)
        tiles.evict(framesToEvict(tiles.heldFrames, lastFrames, protected))
        // A tile still in flight for a frame that has just left the list is not stored when it lands.
        tiles.admit(lastFrames.filterIsInstance<MapFrame.Observed>().mapTo(HashSet()) { it.time } + protected)
    }

    private fun viewportTiles(): List<Pair<Int, Int>> {
        val box = map.boundingBox
        return radarTileRange(box.lonWest, box.lonEast, box.latNorth, box.latSouth)
    }

    private fun overlayFor(frame: MapFrame): Overlay = when (frame) {
        is MapFrame.Observed -> RadarOverlay(frame.radar, tiles, RADAR_ALPHA)
        is MapFrame.Forecast -> NowcastOverlay(frame.step, NOWCAST_ALPHA)
    }

    private fun setFade(overlay: Overlay, f: Float) {
        when (overlay) {
            is RadarOverlay -> overlay.fade = f
            is NowcastOverlay -> overlay.fade = f
        }
    }

    companion object {
        const val FADE_MS = 250L
        const val READY_AHEAD = 3

        /**
         * How long the preload loop polls [nextFramesCached] before giving up and clearing the
         * ring anyway. Play is not held hostage by a slow network.
         */
        const val PRELOAD_TIMEOUT_MS = 8_000L

        /** How much of the radar is let through, so the valley under the rain stays visible. */
        const val RADAR_ALPHA = 0.62f

        /** And of the forecast, out of 255: a shade lighter than the radar on purpose. */
        const val NOWCAST_ALPHA = 130
    }
}

/**
 * Which of the [held] frames' tiles no longer earn their place: not a radar frame of [radarFrames],
 * and not [protected] — the frame on screen right now, or the one still fading out from under it.
 *
 * [radarFrames] is the whole radar-and-nowcast list, never the zoom's visible one: in Heute that is
 * all forecast, and evicting against it dropped every radar frame.
 */
internal fun framesToEvict(held: Set<Instant>, radarFrames: List<MapFrame>, protected: Set<Instant>): Set<Instant> =
    held - radarFrames.filterIsInstance<MapFrame.Observed>().mapTo(HashSet()) { it.time } - protected

/**
 * Every radar frame of [frames], in the order the loop reaches them from [from]: that frame and
 * those after it, then round from the start.
 *
 * Preloading used to take the frames from [from] onwards only. A first load selects the newest
 * radar frame, so exactly one frame was fetched; the loop then ran through the forecast, wrapped to
 * the oldest radar frame, and faded it in over nothing.
 */
internal fun preloadOrder(frames: List<MapFrame>, from: Int): List<MapFrame.Observed> {
    if (frames.isEmpty()) return emptyList()
    val start = if (from in frames.indices) from else 0
    return (frames.drop(start) + frames.take(start)).filterIsInstance<MapFrame.Observed>()
}
