package it.apexweather.ui.map

import androidx.compose.ui.graphics.toArgb
import it.apexweather.Fixtures
import it.apexweather.RadarFixtures
import it.apexweather.data.remote.NowcastCell
import it.apexweather.data.remote.NowcastKind
import it.apexweather.data.remote.NowcastMapper
import it.apexweather.data.remote.NowcastResponse
import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.RadarAtPlace
import it.apexweather.domain.RadarColorTable
import it.apexweather.domain.RadarReading
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

    // --- Fix B: the radar overrules the first hour of forecast near the place -----------------

    private val lat = 46.688958
    private val lon = 11.156624

    private fun instantOf(hhmm: String): Instant = Instant.parse("2026-09-14T${hhmm.take(2)}:${hhmm.drop(2)}:00Z")

    private fun morningRadar(vararg hhmm: String) = hhmm.map { RadarFrame(instantOf(it), "https://example.invalid/$it") }

    private fun morningReadings(vararg hhmm: String): Map<Instant, RadarReading> {
        val p = RadarAtPlace.pixelOf(lat, lon)
        return hhmm.associate { t -> instantOf(t) to RadarAtPlace.read(RadarFixtures.tile("radar-z7-67-45-${t}Z.png"), 256, p.px, p.py) }
    }

    private val inca05 by lazy {
        NowcastMapper.map(Fixtures.json.decodeFromString(NowcastResponse.serializer(), Fixtures.read("map-2026-09-14/inca-0500Z.json")))
    }

    private val times = arrayOf("0430", "0440", "0450", "0500", "0510", "0520", "0530", "0540")

    private fun List<MapFrame>.step(hhmm: String) =
        filterIsInstance<MapFrame.Forecast>().first { it.time == Instant.parse("2026-09-14T$hhmm:00Z") }.step

    private fun NowcastStep.atPlace() = cells.first { it.lat == 46.68674850463867 && it.lon == 11.16190242767334 }

    /**
     * 2026-09-14, as the reader saw it at 07:45 local: the newest radar frame (05:40Z) dry over Dorf
     * Tirol, and INCA's 05:00Z run still carrying a shower that had died an hour before. Nobody's
     * gauge caught a drop.
     */
    @Test
    fun `this morning's dead shower is marked unconfirmed at Dorf Tirol`() {
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, PlaceCheck(lat, lon, morningReadings(*times)))
        assertTrue(frames.step("05:45").atPlace().unconfirmed)
        assertEquals(0.96, frames.step("05:45").atPlace().mmPerHour, 0.001)
        assertTrue(frames.step("06:00").atPlace().unconfirmed)
        assertTrue(frames.step("06:15").atPlace().unconfirmed)
    }

    @Test
    fun `past an hour after the newest radar frame nothing is marked`() {
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, PlaceCheck(lat, lon, morningReadings(*times)))
        assertTrue(frames.step("06:45").cells.isNotEmpty())
        assertFalse(frames.step("06:45").cells.any { it.unconfirmed })
    }

    @Test
    fun `cells further than five kilometres are never marked`() {
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, PlaceCheck(lat, lon, morningReadings(*times)))
        val far = frames.step("05:45").cells.filter { kotlin.math.abs(it.lat - lat) > 0.06 }
        assertTrue("the fixture must have rain far away for this to prove anything", far.isNotEmpty())
        assertFalse(far.any { it.unconfirmed })
    }

    @Test
    fun `where the newest frame is raining at the place nothing is marked`() {
        val wet = morningReadings(*times).mapValues { RadarReading(30) }
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, PlaceCheck(lat, lon, wet))
        assertFalse(frames.filterIsInstance<MapFrame.Forecast>().any { f -> f.step.cells.any { it.unconfirmed } })
    }

    /** A tile that failed is an unknown, and an unknown overrules nothing. */
    @Test
    fun `with no reading for the newest frame nothing is marked`() {
        val readings = morningReadings(*times) - Instant.parse("2026-09-14T05:40:00Z")
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, PlaceCheck(lat, lon, readings))
        assertFalse(frames.filterIsInstance<MapFrame.Forecast>().any { f -> f.step.cells.any { it.unconfirmed } })
        assertFalse(MapUiState.timeline(morningRadar(*times), inca05.steps, null)
            .filterIsInstance<MapFrame.Forecast>().any { f -> f.step.cells.any { it.unconfirmed } })
    }

    @Test
    fun `the card knows since when the radar has seen no rain here`() {
        val check = PlaceCheck(lat, lon, morningReadings(*times))
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, check)
        val state = MapUiState(frames = frames, selected = frames.indexOfFirst { it.time == Instant.parse("2026-09-14T05:45:00Z") }, check = check, loading = false)
        assertTrue(state.unconfirmedHere)
        // 04:30Z's 8 dBZ is an echo but not rain, so the dry run reaches back to the first frame.
        assertEquals(Instant.parse("2026-09-14T04:30:00Z"), state.radarDrySince)
    }

    // --- The radar check reaches Heute too -------------------------------------------------

    private fun cellStep(minutesAfter: Long, kind: NowcastKind) =
        NowcastStep(instantOf("0540").plusSeconds(minutesAfter * 60), listOf(NowcastCell(lat, lon, 1.2)), kind)

    private val dryNewest = PlaceCheck(lat, lon, mapOf(instantOf("0540") to RadarReading.NO_ECHO))

    /**
     * Jetzt could say "unsicher" for 06:00 while Heute's bar for the same hour at the same place read
     * "leichter Regen": only the nowcast half of the timeline was ever checked against the radar.
     */
    @Test
    fun `an outlook hour within the hour and five kilometres of a dry newest frame is marked`() {
        val marked = MapUiState.markUnconfirmed(listOf(cellStep(20, NowcastKind.OUTLOOK)), instantOf("0540"), dryNewest)
        assertTrue(marked.single().cells.single().unconfirmed)
    }

    @Test
    fun `an outlook hour past the hour is not marked`() {
        val marked = MapUiState.markUnconfirmed(listOf(cellStep(61, NowcastKind.OUTLOOK)), instantOf("0540"), dryNewest)
        assertFalse(marked.single().cells.single().unconfirmed)
    }

    @Test
    fun `exactly sixty minutes is still inside the window, for the outlook and the nowcast alike`() {
        val outlook = MapUiState.markUnconfirmed(listOf(cellStep(60, NowcastKind.OUTLOOK)), instantOf("0540"), dryNewest)
        assertTrue(outlook.single().cells.single().unconfirmed)
        val nowcast = MapUiState.timeline(listOf(RadarFrame(instantOf("0540"), "x")), listOf(cellStep(60, NowcastKind.NOWCAST)), dryNewest)
        assertTrue((nowcast.last() as MapFrame.Forecast).step.cells.single().unconfirmed)
        val past = MapUiState.timeline(listOf(RadarFrame(instantOf("0540"), "x")), listOf(cellStep(61, NowcastKind.NOWCAST)), dryNewest)
        assertFalse((past.last() as MapFrame.Forecast).step.cells.single().unconfirmed)
    }

    // --- Zooms -----------------------------------------------------------------------------

    /** Heute with no frame yet — the chip tapped before the outlook arrived — must not open 24 h out. */
    @Test
    fun `a first load in Heute opens on its first hour`() {
        val opened = MapUiState(zoom = MapZoom.TODAY).selectionAfter(outlook(0, 1, 2, 3))
        assertEquals(0, opened)
    }

    @Test
    fun `a refresh while in Heute keeps the instant`() {
        val before = MapUiState(outlook = outlook(0, 1, 2, 3), zoom = MapZoom.TODAY, selected = 2)
        val after = outlook(1, 2, 3, 4)
        assertEquals(t0.plusSeconds(2 * 3600), after[before.selectionAfter(after)].time)
    }

    private fun outlook(vararg hours: Long) = hours.map { h ->
        MapFrame.Forecast(NowcastStep(t0.plusSeconds(h * 3600), listOf(NowcastCell(46.6, 11.1, 1.0)), NowcastKind.OUTLOOK))
    }

    @Test
    fun `Jetzt reaches three hours past the newest radar frame and no further`() {
        val frames = MapUiState.timeline(radar(-10, 0), steps(15, 180, 195))
        assertEquals(t0.plusSeconds(180 * 60), frames.last().time)
    }

    @Test
    fun `the zoom decides which frames are visible`() {
        val s = MapUiState(frames = MapUiState.timeline(radar(-10, 0), steps(15)), outlook = outlook(1, 2, 3), loading = false)
        assertEquals(3, s.visible.size)
        assertEquals(3, s.withZoom(MapZoom.TODAY).visible.size)
        assertTrue(s.withZoom(MapZoom.TODAY).visible.all { it is MapFrame.Forecast })
    }

    @Test
    fun `switching zoom keeps the instant when the other zoom has it`() {
        val s = MapUiState(
            frames = MapUiState.timeline(radar(-10, 0), steps(15, 30, 45, 60)),
            outlook = outlook(1, 2, 3), selected = 5, loading = false,
        )
        assertEquals(t0.plusSeconds(3600), s.frame?.time)
        val today = s.withZoom(MapZoom.TODAY)
        assertEquals(t0.plusSeconds(3600), today.frame?.time)
    }

    @Test
    fun `switching zoom lands on the present when the other zoom does not have the instant`() {
        val s = MapUiState(
            frames = MapUiState.timeline(radar(-20, -10, 0), steps(15)),
            outlook = outlook(1, 2, 3), selected = 0, loading = false,
        )
        assertEquals(0, s.withZoom(MapZoom.TODAY).selected)
        assertFalse(s.withZoom(MapZoom.TODAY).playing)
        assertEquals(2, s.withZoom(MapZoom.TODAY).withZoom(MapZoom.NOW).selected)
    }
}

/** The colours both layers are drawn in: the radar's own, at the rates the radar means by them. */
class PrecipColorsTest {

    @Test
    fun `below a tenth of a millimetre nothing is drawn`() {
        assertNull(PrecipColors.forRate(0.0))
        assertNull(PrecipColors.forRate(0.09))
    }

    /** The radar draws 0-14 dBZ as a beige wash; forecast drizzle gets the same wash, not rain's blue. */
    @Test
    fun `under 0,3 mm per hour is the beige wash, not rain`() {
        assertEquals(PrecipColors.SUB_RAIN, PrecipColors.forRate(0.1))
        assertEquals(PrecipColors.SUB_RAIN, PrecipColors.forRate(0.29))
        assertFalse(PrecipColors.isRain(0.29))
        assertEquals(PrecipColors.RAMP.first(), PrecipColors.forRate(0.32))
    }

    /** Before this, orange meant 8 mm/h on the forecast and about 24 on the radar. */
    @Test
    fun `each colour is the radar's colour at the rate the radar means by it`() {
        listOf(15, 18, 20, 23, 29, 45, 50, 54).forEachIndexed { i, dbz ->
            val argb = RadarColorTable.RAIN[dbz - RadarColorTable.MIN_DBZ]
            val colour = PrecipColors.forRate(RadarAtPlace.rateOf(dbz) + 1e-9)
            assertEquals("dBZ $dbz", argb, colour!!.toArgb())
            assertEquals("stop $i", PrecipColors.RAMP[i], colour)
        }
    }

    @Test
    fun `heavier rain never picks a lighter colour`() {
        val seen = generateSequence(RadarAtPlace.rateOf(RadarAtPlace.RAIN_DBZ)) { it * 1.3 }.takeWhile { it < 200.0 }
            .mapNotNull { PrecipColors.forRate(it) }.toList()
        val indices = seen.map { PrecipColors.RAMP.indexOf(it) }
        assertEquals("the ramp must never step backwards", indices.sorted(), indices)
        assertTrue(indices.all { it >= 0 })
    }

    /**
     * "bis 0,3 mm/h" is printed from [PrecipColors.RAIN_FROM_MM] and the colour from [PrecipColors.isRain];
     * with the first at a literal 0,3 and the second at 15 dBZ's 0,3158 a rate between them was
     * called rain in words and drawn as the beige wash.
     */
    @Test
    fun `the rain threshold in words and in colour is the radar's 15 dBZ`() {
        assertEquals(RadarAtPlace.rateOf(RadarAtPlace.RAIN_DBZ), PrecipColors.RAIN_FROM_MM, 0.0)
        assertTrue(PrecipColors.isRain(PrecipColors.RAIN_FROM_MM))
        assertFalse(PrecipColors.isRain(PrecipColors.RAIN_FROM_MM - 1e-9))
    }

    @Test
    fun `the forecast mapper and the map agree on what is worth drawing`() {
        assertEquals(PrecipColors.DRAWN_FROM_MM, NowcastMapper.MIN_MM_PER_HOUR, 0.0)
    }

    @Test
    fun `the heaviest rate there is still has a colour`() {
        assertEquals(PrecipColors.RAMP.last(), PrecipColors.forRate(500.0))
    }
}
