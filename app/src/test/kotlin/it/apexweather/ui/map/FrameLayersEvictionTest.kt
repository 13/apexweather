package it.apexweather.ui.map

import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.SouthTyrol
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure rules behind [FrameLayers]: which frames' radar tiles are kept, the order they are
 * fetched in, and which zoom-7 tiles a viewport needs. Tested without a MapView because none of the
 * rules touches one.
 */
class FrameLayersEvictionTest {

    private val t1 = Instant.parse("2026-09-14T10:00:00Z")
    private val t2 = Instant.parse("2026-09-14T10:10:00Z")
    private val t3 = Instant.parse("2026-09-14T10:20:00Z")

    private fun observed(t: Instant) = MapFrame.Observed(RadarFrame(t, "https://example.invalid/${t.epochSecond}"))
    private fun forecast(t: Instant) = MapFrame.Forecast(NowcastStep(t, emptyList()))

    // --- Eviction ------------------------------------------------------------------------------

    @Test
    fun `a shown frame absent from the new list is kept`() {
        val evicted = framesToEvict(held = setOf(t1, t2), radarFrames = listOf(observed(t2)), protected = setOf(t1))
        assertEquals(emptySet<Instant>(), evicted)
    }

    @Test
    fun `an outgoing frame is kept alongside a different shown one`() {
        // t1 is on screen and t2 is still fading out from under it; neither is in the new list.
        val evicted = framesToEvict(held = setOf(t1, t2, t3), radarFrames = listOf(observed(t3)), protected = setOf(t1, t2))
        assertEquals(emptySet<Instant>(), evicted)
    }

    @Test
    fun `a frame neither in the list nor protected is evicted`() {
        val evicted = framesToEvict(held = setOf(t1, t2, t3), radarFrames = listOf(observed(t2)), protected = setOf(t3))
        assertEquals(setOf(t1), evicted)
    }

    /**
     * Heute's visible frames are all forecast, and eviction used to be computed from those, so
     * switching to Heute dropped every radar frame and switching back fetched them all again. The
     * rule follows the radar frame list, whatever the zoom is showing.
     */
    @Test
    fun `the radar frame list keeps its tiles while Heute shows only forecast`() {
        val evicted = framesToEvict(
            held = setOf(t1, t2),
            radarFrames = listOf(observed(t1), observed(t2), forecast(t3)),
            protected = emptySet(),
        )
        assertEquals(emptySet<Instant>(), evicted)
    }

    @Test
    fun `a forecast frame in the list keeps no radar tiles`() {
        val evicted = framesToEvict(held = setOf(t3), radarFrames = listOf(forecast(t3)), protected = emptySet())
        assertEquals(setOf(t3), evicted)
    }

    @Test
    fun `the empty case evicts nothing`() {
        assertEquals(emptySet<Instant>(), framesToEvict(held = emptySet(), radarFrames = emptyList(), protected = emptySet()))
    }

    // --- Preload order -------------------------------------------------------------------------

    /**
     * A first load selects the newest radar frame, and preloading "from the selection onwards" then
     * fetched that one frame alone: the loop ran through the forecast, wrapped to the oldest radar
     * frame, and faded it in over nothing.
     */
    @Test
    fun `every radar frame is preloaded, from the selection onwards and wrapping round`() {
        val frames = listOf(observed(t1), observed(t2), observed(t3), forecast(t3.plusSeconds(900)))
        assertEquals(listOf(t3, t1, t2), preloadOrder(frames, from = 2).map { it.time })
        assertEquals(listOf(t1, t2, t3), preloadOrder(frames, from = 3).map { it.time })
        assertEquals(listOf(t1, t2, t3), preloadOrder(frames, from = 0).map { it.time })
    }

    @Test
    fun `a selection out of range still orders every radar frame`() {
        val frames = listOf(observed(t1), observed(t2))
        assertEquals(listOf(t1, t2), preloadOrder(frames, from = -1).map { it.time })
        assertEquals(listOf(t1, t2), preloadOrder(frames, from = 9).map { it.time })
        assertEquals(emptyList<Instant>(), preloadOrder(listOf(forecast(t1)), from = 0).map { it.time })
    }

    // --- Tiles ---------------------------------------------------------------------------------

    /** The whole province at zoom 7 is four tiles; anything the viewport shows past it is not asked for. */
    @Test
    fun `the province needs four zoom-7 tiles and a viewport past it needs no more`() {
        val province = radarTileRange(SouthTyrol.WEST, SouthTyrol.EAST, SouthTyrol.NORTH, SouthTyrol.SOUTH)
        assertEquals(setOf(67 to 44, 68 to 44, 67 to 45, 68 to 45), province.toSet())
        assertEquals(province.toSet(), radarTileRange(5.0, 18.0, 50.0, 43.0).toSet())
    }

    @Test
    fun `a viewport over Meran needs the one tile under it`() {
        assertEquals(listOf(67 to 45), radarTileRange(11.10, 11.20, 46.72, 46.64))
    }
}
