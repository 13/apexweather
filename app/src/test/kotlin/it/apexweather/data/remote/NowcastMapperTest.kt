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

    private val outlook = NowcastMapper.mapOutlook(
        Fixtures.json.decodeFromString(NowcastResponse.serializer(), Fixtures.read("geosphere_outlook.json")),
        after = null,
    )

    /**
     * INCA stops two and a half hours out and "will it rain this evening" is a map question too, so
     * AROME carries the rest of the day at 2,5 km and an hour.
     */
    @Test
    fun `the outlook reaches a day ahead, hour by hour`() {
        val times = outlook.steps.map { it.time }
        assertEquals(times.sorted(), times)
        assertEquals(Instant.parse("2026-09-12T08:00:00Z"), times.last())
        times.zipWithNext().forEach { (a, b) -> assertEquals(3600L, b.epochSecond - a.epochSecond) }
        assertTrue("${times.size} steps is not a day", times.size >= 23)
    }

    /**
     * `rr_acc` is accumulated from the run's start. Drawn undifferenced it would paint the whole
     * day's rain onto every hour of it, growing all afternoon and never stopping.
     */
    @Test
    fun `the accumulated series is differenced into each hour's own rain`() {
        val wettest = outlook.steps.maxOf { step -> step.cells.maxOfOrNull { it.mmPerHour } ?: 0.0 }
        // The fixture's largest single-hour step up is well under its 6 mm total accumulation.
        assertTrue("$wettest looks like an accumulation, not an hour", wettest < 3.0)
        assertTrue(wettest > 0.0)
    }

    /** The first step has nothing before it to difference against, so it is not guessed at. */
    @Test
    fun `the first step of an accumulated series is dropped rather than taken whole`() {
        assertTrue(outlook.steps.none { it.time == Instant.parse("2026-09-11T08:00:00Z") })
    }

    /** Where the finer forecast already covers an hour, the coarser one does not draw over it. */
    @Test
    fun `hours the nowcast already covers are left to it`() {
        val trimmed = NowcastMapper.mapOutlook(
            Fixtures.json.decodeFromString(NowcastResponse.serializer(), Fixtures.read("geosphere_outlook.json")),
            after = Instant.parse("2026-09-11T14:00:00Z"),
        )
        assertTrue(trimmed.steps.all { it.time.isAfter(Instant.parse("2026-09-11T14:00:00Z")) })
        assertTrue(trimmed.steps.size < outlook.steps.size)
    }

    /** A response that says nothing is not a forecast of no rain. */
    @Test
    fun `an empty response yields no forecast at all`() {
        assertEquals(0, NowcastMapper.map(NowcastResponse()).steps.size)
        assertEquals(PrecipNowcast.EMPTY, NowcastMapper.map(NowcastResponse()))
    }

    /** The box is drawn around the place, which is what makes this affordable at all. */
    @Test
    fun `the box is centred on the place`() {
        val box = NowcastApi.boxAround(it.apexweather.domain.DORF_TIROL)
        val (south, west, north, east) = box.split(",").map { part -> part.toDouble() }
        assertEquals(it.apexweather.domain.DORF_TIROL.lat, (south + north) / 2, 1e-3)
        assertEquals(it.apexweather.domain.DORF_TIROL.lon, (west + east) / 2, 1e-3)
        assertTrue("the box must be south,west,north,east", south < north && west < east)
    }
}

private operator fun <T> List<T>.component4(): T = this[3]
