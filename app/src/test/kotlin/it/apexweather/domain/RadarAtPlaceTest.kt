package it.apexweather.domain

import it.apexweather.Fixtures
import it.apexweather.RadarFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radar's word about one place, read off the tiles by RainViewer's own published table.
 *
 * The fixtures are the morning of 2026-09-14: a faint echo over Dorf Tirol at 04:30Z that no gauge
 * caught, gone by 05:30Z.
 */
class RadarAtPlaceTest {

    private val times = listOf("0430", "0440", "0450", "0500", "0510", "0520", "0530", "0540", "0550", "0600", "0610", "0620", "0630")

    private fun readingAt(time: String): RadarReading {
        val p = RadarAtPlace.pixelOf(DORF_TIROL.lat, DORF_TIROL.lon)
        return RadarAtPlace.read(RadarFixtures.tile("radar-z7-67-45-${time}Z.png"), RadarAtPlace.TILE_SIZE, p.px, p.py)
    }

    @Test
    fun `Dorf Tirol is pixel 247,46 of tile 67,45 at zoom 7`() {
        assertEquals(TilePixel(7, 67, 45, 247, 46), RadarAtPlace.pixelOf(DORF_TIROL.lat, DORF_TIROL.lon))
    }

    @Test
    fun `the generated table is RainViewer's Universal Blue column`() {
        val rows = Fixtures.read("${RadarFixtures.DIR}/rainviewer_api_colors_table.csv").lines().filter { it.isNotBlank() }
        val col = rows.first().split(",").indexOf("Universal Blue")
        var block = -1
        rows.drop(1).forEach { line ->
            val cells = line.split(",")
            val dbz = cells[0].toInt()
            if (dbz == -32) block++
            if (dbz < RadarColorTable.MIN_DBZ) return@forEach
            val rgba = cells[col].trim().removePrefix("#").toLong(16)
            val argb = (((rgba and 0xFF) shl 24) or (rgba ushr 8)).toInt()
            val table = if (block == 0) RadarColorTable.RAIN else RadarColorTable.SNOW
            assertEquals("block $block dBZ $dbz", argb, table[dbz - RadarColorTable.MIN_DBZ])
        }
    }

    @Test
    fun `this morning's echo was 8 dBZ, which is not rain`() {
        val r = readingAt("0430")
        assertEquals(8, r.dbz)
        assertFalse(r.isRain)
        assertFalse(r.snow)
    }

    @Test
    fun `by 05_30Z the radar saw nothing at all`() {
        assertEquals(RadarReading.NO_ECHO, readingAt("0530"))
        assertEquals(0.0, readingAt("0530").mmPerHour, 0.0)
    }

    @Test
    fun `the patch is the 3x3 around the place and takes its highest dBZ`() {
        assertEquals(listOf(8, 3, null, null, null, -10, null, null, null, null, null, null, null), times.map { readingAt(it).dbz })
    }

    /** A colour missing from the table would be read as no echo; RainViewer changing scheme must fail here. */
    @Test
    fun `every pixel of every recorded tile is in the table`() {
        times.forEach { t ->
            val unread = RadarFixtures.tile("radar-z7-67-45-${t}Z.png").count { RadarAtPlace.readingOf(it) == null }
            assertEquals("unreadable pixels at $t", 0, unread)
        }
    }

    @Test
    fun `a transparent pixel is no echo and an unknown colour is unreadable`() {
        assertEquals(RadarReading.NO_ECHO, RadarAtPlace.readingOf(0x00000000))
        assertNull(RadarAtPlace.readingOf(0xFF123456.toInt()))
    }

    @Test
    fun `the first rain colour is 15 dBZ, and a snow colour reads as snow`() {
        assertEquals(RadarReading(15), RadarAtPlace.readingOf(0xFF88DDEE.toInt()))
        assertTrue(RadarAtPlace.readingOf(0xFF88DDEE.toInt())!!.isRain)
        val snow20 = RadarColorTable.SNOW[20 - RadarColorTable.MIN_DBZ]
        assertEquals(RadarReading(20, snow = true), RadarAtPlace.readingOf(snow20))
    }

    @Test
    fun `Marshall-Palmer rates`() {
        assertEquals(0.32, RadarAtPlace.rateOf(15), 0.01)
        assertEquals(0.65, RadarAtPlace.rateOf(20), 0.01)
        assertEquals(23.7, RadarAtPlace.rateOf(45), 0.1)
    }
}
