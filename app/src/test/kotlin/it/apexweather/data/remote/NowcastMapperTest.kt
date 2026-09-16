package it.apexweather.data.remote

import it.apexweather.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Against `geosphere_nowcast.json`, recorded from the live INCA nowcast on 2026-09-11 over a box
 * east of the province that had rain in it — a dry box would have proved only that zeroes survive
 * the trip.
 *
 * This is the half of the map the radar cannot do: where the rain is going rather than where it has
 * been.
 */
class NowcastMapperTest {

    private val nowcast = NowcastMapper.map(
        Fixtures.json.decodeFromString(NowcastResponse.serializer(), Fixtures.read("geosphere_nowcast.json")),
    )

    @Test
    fun `the run's own reference time is kept, not the fetch`() {
        assertEquals(Instant.parse("2026-09-11T07:30:00Z"), nowcast.issuedAt)
    }

    @Test
    fun `the steps are quarter hours, in order, reaching hours ahead`() {
        assertEquals(11, nowcast.steps.size)
        val times = nowcast.steps.map { it.time }
        assertEquals(times.sorted(), times)
        assertEquals(Instant.parse("2026-09-11T08:00:00Z"), times.first())
        assertEquals(Instant.parse("2026-09-11T10:30:00Z"), times.last())
        times.zipWithNext().forEach { (a, b) -> assertEquals(900L, b.epochSecond - a.epochSecond) }
    }

    /** `rr` is a sum over the quarter hour; a rate is what a reader and a colour scale can use. */
    @Test
    fun `the quarter-hourly sum is turned into a rate per hour`() {
        val wettest = nowcast.steps.flatMap { it.cells }.maxOf { it.mmPerHour }
        // The fixture's largest quarter-hour sum is 0,41 mm.
        assertEquals(0.41 * 4, wettest, 1e-9)
    }

    /**
     * A dry cell is left out rather than carried as a zero. Over a box this size most cells are dry
     * most of the time, and an overlay that drew them would be drawing nothing, slowly.
     */
    @Test
    fun `dry cells are dropped`() {
        val cells = nowcast.steps.flatMap { it.cells }
        assertTrue(cells.isNotEmpty())
        assertTrue(cells.all { it.mmPerHour >= NowcastMapper.MIN_MM_PER_HOUR })
        // The fixture holds 340 grid points at each of 11 steps; far fewer than all of them are wet.
        assertTrue("${cells.size} cells is not a filtered set", cells.size < 340 * 11 / 2)
    }

    @Test
    fun `every cell lands inside the box that was asked for`() {
        nowcast.steps.flatMap { it.cells }.forEach {
            assertTrue("lat ${it.lat}", it.lat in 45.85..46.13)
            assertTrue("lon ${it.lon}", it.lon in 12.85..13.17)
        }
    }

    /**
     * The grid's own extent, dry points included, so the overlay can fade the forecast out at the
     * edge of what was asked for rather than stop it at a straight line — and never fade a real
     * edge of the rain, which is what the extent of the wet cells alone would do.
     */
    @Test
    fun `each step carries the extent of the whole grid, dry points included`() {
        val points = Fixtures.json.decodeFromString(NowcastResponse.serializer(), Fixtures.read("geosphere_nowcast.json"))
            .features.map { it.geometry.coordinates }
        val expected = NowcastExtent(
            south = points.minOf { it[1] }, west = points.minOf { it[0] },
            north = points.maxOf { it[1] }, east = points.maxOf { it[0] },
        )
        nowcast.steps.forEach { assertEquals(expected, it.extent) }
        val wet = nowcast.steps.flatMap { it.cells }
        assertTrue("the wet cells alone span less", wet.minOf { it.lat } > expected.south || wet.maxOf { it.lat } < expected.north)
    }

    private val outlook = NowcastMapper.mapOutlook(
        Fixtures.json.decodeFromString(NowcastResponse.serializer(), Fixtures.read("geosphere_outlook.json")),
        after = null,
    )

    /**
     * INCA stops two and a half hours out and "will it rain this evening" is a map question too, so
     * AROME's ensemble carries the rest of the day at 2,5 km and an hour.
     */
    @Test
    fun `the outlook reaches a day ahead, hour by hour`() {
        val times = outlook.steps.map { it.time }
        assertEquals(times.sorted(), times)
        times.zipWithNext().forEach { (a, b) -> assertEquals(3600L, b.epochSecond - a.epochSecond) }
        assertTrue("${times.size} steps is not a day", times.size >= 24)
    }

    /**
     * The trap this nearly shipped on. `rr_*` and `rain_*` both claim `kg m-2` in this dataset and
     * are not the same quantity at all — `rr_p50` tops out at 0,008 over a box a day long where
     * `rain_p50` reaches 7,9, so a map drawn from the first shows no rain, ever.
     */
    @Test
    fun `the outlook reads the parameter that actually holds the rain`() {
        val wettest = outlook.steps.maxOf { step -> step.cells.maxOfOrNull { it.mmPerHour } ?: 0.0 }
        assertEquals(7.86, wettest, 1e-6)
    }

    /** These are per-step already, unlike the deterministic run's accumulation. */
    @Test
    fun `the hourly steps are rates, not a running total`() {
        val wettestCell = outlook.steps.mapNotNull { step ->
            step.cells.maxByOrNull { it.mmPerHour }?.mmPerHour
        }
        assertTrue("an accumulation would only ever climb", wettestCell.zipWithNext().any { (a, b) -> b < a })
    }

    /** Where the finer forecast already covers an hour, the coarser one does not draw over it. */
    @Test
    fun `hours the nowcast already covers are left to it`() {
        val after = outlook.steps[3].time
        val trimmed = NowcastMapper.mapOutlook(
            Fixtures.json.decodeFromString(NowcastResponse.serializer(), Fixtures.read("geosphere_outlook.json")),
            after = after,
        )
        assertTrue(trimmed.steps.all { it.time.isAfter(after) })
        assertTrue(trimmed.steps.size < outlook.steps.size)
    }

    /** A response that says nothing is not a forecast of no rain. */
    @Test
    fun `an empty response yields no forecast at all`() {
        assertEquals(0, NowcastMapper.map(NowcastResponse()).steps.size)
        assertEquals(PrecipNowcast.EMPTY, NowcastMapper.map(NowcastResponse()))
    }

    /** The forecast is asked for the province and a margin, as the radar shows it. */
    @Test
    fun `the box is the province and a margin`() {
        val (south, west, north, east) = NowcastApi.PROVINCE_BOX.split(",").map { part -> part.toDouble() }
        val m = NowcastApi.MARGIN_DEG
        assertEquals(it.apexweather.domain.SouthTyrol.SOUTH - m, south, 1e-9)
        assertEquals(it.apexweather.domain.SouthTyrol.WEST - m, west, 1e-9)
        assertEquals(it.apexweather.domain.SouthTyrol.NORTH + m, north, 1e-9)
        assertEquals(it.apexweather.domain.SouthTyrol.EAST + m, east, 1e-9)
    }

    /**
     * The two halves are drawn at different sizes on the map, so a step has to say which it is —
     * INCA's grid is a kilometre and AROME's two and a half, and cells drawn at the wrong width
     * leave gaps that read as dry ground.
     */
    @Test
    fun `each step says which run it came from`() {
        assertTrue(nowcast.steps.all { it.kind == NowcastKind.NOWCAST })
        assertTrue(outlook.steps.all { it.kind == NowcastKind.OUTLOOK })
    }

    /**
     * The wetter end of the ensemble, carried beside the middle of it.
     *
     * A forecast drawn as one field looks like a fact, and fifty members do not agree on one. Over
     * a box of the eastern Dolomites the median called 730 cell-hours dry that the ninetieth
     * percentile called wet — that is where the rain might reach, and it is not the same claim as
     * where it is expected.
     */
    @Test
    fun `the outlook carries the ensemble's upper end as well as its middle`() {
        val cells = outlook.steps.flatMap { it.cells }
        assertTrue(cells.isNotEmpty())
        assertTrue("no cell carried an upper bound", cells.any { it.upperMmPerHour != null })
        assertTrue("the upper end is never below the middle", cells.all { (it.upperMmPerHour ?: 0.0) >= it.mmPerHour - 1e-9 })
        assertEquals(22.429, cells.maxOf { it.upperMmPerHour ?: 0.0 }, 1e-3)
    }

    /**
     * A cell the median calls dry is kept when the upper end does not, because "the middle of the
     * ensemble says no" is not "no" — the map draws those pale rather than not at all.
     */
    @Test
    fun `a cell only the upper end calls wet survives`() {
        val onlyUpper = outlook.steps.flatMap { it.cells }
            .filter { it.mmPerHour < NowcastMapper.MIN_MM_PER_HOUR }
        assertTrue("nothing was kept on the upper end alone", onlyUpper.isNotEmpty())
        assertTrue(onlyUpper.all { (it.upperMmPerHour ?: 0.0) >= NowcastMapper.MIN_MM_PER_HOUR })
    }

    /** INCA has no members, so it has no upper end to offer. */
    @Test
    fun `the nowcast carries no upper end`() {
        assertTrue(nowcast.steps.flatMap { it.cells }.all { it.upperMmPerHour == null })
    }

}

private operator fun <T> List<T>.component4(): T = this[3]
