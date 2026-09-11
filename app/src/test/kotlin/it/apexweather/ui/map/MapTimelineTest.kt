package it.apexweather.ui.map

import it.apexweather.data.remote.NowcastCell
import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The one timeline that carries both halves of the map: where the rain has been, and where it is
 * going. Getting the join wrong is the failure that matters — a reader who cannot tell an
 * observation from a forecast is being misled by a picture.
 */
class MapTimelineTest {

    private val t0: Instant = Instant.parse("2026-09-11T08:00:00Z")

    private fun radar(vararg minutes: Long) =
        minutes.map { RadarFrame(t0.plusSeconds(it * 60), "https://example.invalid/$it") }

    private fun steps(vararg minutes: Long) =
        minutes.map { NowcastStep(t0.plusSeconds(it * 60), listOf(NowcastCell(46.6, 11.1, 1.0))) }

    @Test
    fun `the past comes first and the forecast after it`() {
        val frames = MapUiState.timeline(radar(-20, -10, 0), steps(15, 30))
        assertEquals(5, frames.size)
        assertEquals(frames.map { it.time }.sorted(), frames.map { it.time })
        assertTrue(frames.take(3).all { it is MapFrame.Observed })
        assertTrue(frames.drop(3).all { it is MapFrame.Forecast })
    }

    /**
     * INCA is issued on the quarter hour and its first steps often cover minutes a radar has already
     * watched. Where both exist the radar is the better witness, and a timeline that stepped
     * backwards through them would be nonsense.
     */
    @Test
    fun `forecast steps the radar has already seen are dropped`() {
        val frames = MapUiState.timeline(radar(-20, -10, 0), steps(-15, 0, 15))
        assertEquals(4, frames.size)
        assertEquals(1, frames.count { it is MapFrame.Forecast })
        assertEquals(t0.plusSeconds(15 * 60), frames.last().time)
    }

    @Test
    fun `the present is the newest frame a radar actually saw`() {
        val state = MapUiState(frames = MapUiState.timeline(radar(-10, 0), steps(15, 30)), loading = false)
        assertEquals(1, state.nowIndex)
        assertTrue(state.hasForecast)
    }

    @Test
    fun `with no forecast the present is the end of the line`() {
        val state = MapUiState(frames = MapUiState.timeline(radar(-10, 0), emptyList()), loading = false)
        assertEquals(1, state.nowIndex)
        assertFalse(state.hasForecast)
        assertEquals(state.frames.lastIndex, state.nowIndex)
    }

    /** The radar failing must not take the forecast down with it, nor the other way round. */
    @Test
    fun `either half alone is still a timeline`() {
        val onlyForecast = MapUiState(frames = MapUiState.timeline(emptyList(), steps(15, 30)), loading = false)
        assertEquals(2, onlyForecast.frames.size)
        assertEquals(-1, onlyForecast.nowIndex)
        assertTrue("with no radar at all the map says so", onlyForecast.radarUnavailable)

        val onlyRadar = MapUiState(frames = MapUiState.timeline(radar(-10, 0), emptyList()), loading = false)
        assertFalse(onlyRadar.radarUnavailable)
    }

    @Test
    fun `the selected frame says which kind it is`() {
        val frames = MapUiState.timeline(radar(-10, 0), steps(15))
        assertFalse(MapUiState(frames = frames, selected = 1).showingForecast)
        assertTrue(MapUiState(frames = frames, selected = 2).showingForecast)
    }

    /**
     * The timeline is rebuilt every ten minutes and on every return to the tab, and an index into
     * the old list means nothing in the new one — frames fall off the back as they age out. Before
     * this, a refresh dropped the reader wherever that number happened to land, which on a playing
     * loop looked like the map jumping about on its own.
     */
    @Test
    fun `a refreshed timeline keeps the reader on the minute they were looking at`() {
        val before = MapUiState(frames = MapUiState.timeline(radar(-20, -10, 0), steps(15, 30)), selected = 1)
        val wasLookingAt = before.frames[1].time
        // Ten minutes on: the oldest frame has aged out and a new one has arrived at the front.
        val after = MapUiState.timeline(radar(-10, 0, 10), steps(25, 40))
        val moved = before.selectionAfter(after)
        assertEquals(wasLookingAt, after[moved].time)
    }

    @Test
    fun `a first load opens on the present rather than on the far end of the forecast`() {
        val frames = MapUiState.timeline(radar(-10, 0), steps(15, 30))
        val opened = MapUiState().selectionAfter(frames)
        assertEquals(frames.indexOfLast { it is MapFrame.Observed }, opened)
        assertFalse(MapUiState(frames = frames, selected = opened).showingForecast)
    }

    /** With nothing observed at all there is no present to open on, so the newest frame will do. */
    @Test
    fun `a first load with only a forecast opens on its last step`() {
        val frames = MapUiState.timeline(emptyList(), steps(15, 30))
        assertEquals(frames.lastIndex, MapUiState().selectionAfter(frames))
    }

    @Test
    fun `an emptied timeline resolves to zero rather than throwing`() {
        assertEquals(0, MapUiState(frames = MapUiState.timeline(radar(0), emptyList()), selected = 0).selectionAfter(emptyList()))
    }

    @Test
    fun `a selection off the end resolves to no frame rather than throwing`() {
        assertNull(MapUiState(frames = MapUiState.timeline(radar(0), emptyList()), selected = 7).frame)
    }
}

/** The colours both layers are drawn in, and the legend is labelled with. */
class PrecipColorsTest {

    @Test
    fun `below the first stop nothing is drawn`() {
        assertNull(PrecipColors.forRate(0.0))
        assertNull(PrecipColors.forRate(0.09))
    }

    @Test
    fun `heavier rain never picks a lighter colour`() {
        val seen = generateSequence(0.1) { it * 1.3 }.takeWhile { it < 60.0 }
            .mapNotNull { PrecipColors.forRate(it) }.toList()
        val indices = seen.map { PrecipColors.RAMP.indexOf(it) }
        assertEquals("the ramp must never step backwards", indices.sorted(), indices)
        assertTrue(indices.all { it >= 0 })
    }

    @Test
    fun `the heaviest rate there is still has a colour`() {
        assertEquals(PrecipColors.RAMP.last(), PrecipColors.forRate(500.0))
    }
}
