package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.SouthTyrol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The region-wide forecast, as GeoSphere's NetCDF (HDF5) over the whole province.
 *
 * Recorded 2026-09-16 at the same minute as the GeoJSON of the same box, and checked against it
 * point by point before being trusted: the values, the scale factor and the reference time agree
 * exactly. The GeoJSON of this box was 4,4 MB for INCA and 2,2 MB for AROME; these are 162 and
 * 270 kB.
 */
class NowcastGridTest {

    private val inca = NowcastGrid.map(Fixtures.bytes("map-2026-09-16/inca-1730Z.nc"), NowcastKind.NOWCAST)
    private val arome = NowcastGrid.map(Fixtures.bytes("map-2026-09-16/arome-ens-1200Z.nc"), NowcastKind.OUTLOOK)

    @Test
    fun `the reference time is the first step less its lead time`() {
        assertEquals(Instant.parse("2026-09-16T17:30:00Z"), inca.issuedAt)
        assertEquals(Instant.parse("2026-09-16T12:00:00Z"), arome.issuedAt)
    }

    @Test
    fun `inca is twelve quarter hours`() {
        val times = inca.steps.map { it.time }
        assertEquals(12, times.size)
        assertEquals(Instant.parse("2026-09-16T17:45:00Z"), times.first())
        times.zipWithNext().forEach { (a, b) -> assertEquals(900L, b.epochSecond - a.epochSecond) }
        assertTrue(inca.steps.all { it.kind == NowcastKind.NOWCAST })
    }

    @Test
    fun `arome is twenty-five hours`() {
        val times = arome.steps.map { it.time }
        assertEquals(25, times.size)
        assertEquals(Instant.parse("2026-09-16T17:00:00Z"), times.first())
        times.zipWithNext().forEach { (a, b) -> assertEquals(3600L, b.epochSecond - a.epochSecond) }
        assertTrue(arome.steps.all { it.kind == NowcastKind.OUTLOOK })
    }

    /** The same point in the GeoJSON of the same run read 0,31 mm in the last quarter hour. */
    @Test
    fun `inca's quarter-hour sums are scaled and turned into rates`() {
        val cell = inca.steps.last().cells.single { abs(it.lat - 46.189987) < 1e-5 && abs(it.lon - 10.313468) < 1e-5 }
        assertEquals(0.31 * 4, cell.mmPerHour, 1e-6)
        assertNull(cell.upperMmPerHour)
    }

    /** And AROME's hour at 46,203 / 10,314 read 0,207 at the median, at its first step. */
    @Test
    fun `arome's median and upper end are both read`() {
        val cell = arome.steps.first().cells.single { abs(it.lat - 46.203) < 1e-6 && abs(it.lon - 10.314) < 1e-6 }
        assertEquals(0.207, cell.mmPerHour, 1e-9)
        assertTrue(cell.upperMmPerHour!! >= cell.mmPerHour)
    }

    @Test
    fun `dry cells are dropped`() {
        // Counted in the recording with h5py: 2976 of 18 270 points wet at the first step.
        assertEquals(2976, inca.steps.first().cells.size)
        inca.steps.flatMap { it.cells }.forEach { assertTrue(it.mmPerHour >= NowcastMapper.MIN_MM_PER_HOUR) }
    }

    @Test
    fun `the grid covers the province`() {
        val e = inca.steps.first().extent!!
        assertTrue(e.south <= SouthTyrol.SOUTH + 0.01 && e.north >= SouthTyrol.NORTH - 0.06)
        assertTrue(e.west <= SouthTyrol.WEST + 0.02 && e.east >= SouthTyrol.EAST - 0.02)
        assertEquals(arome.steps.first().extent, arome.steps.last().extent)
    }

    @Test
    fun `something that is not a grid is no forecast`() {
        assertEquals(PrecipNowcast.EMPTY, NowcastGrid.map(ByteArray(64), NowcastKind.NOWCAST))
    }

    private fun abs(d: Double) = kotlin.math.abs(d)
}
