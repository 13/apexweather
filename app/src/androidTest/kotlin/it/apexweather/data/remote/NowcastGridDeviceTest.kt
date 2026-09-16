package it.apexweather.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The same read as `NowcastGridTest`, on a device.
 *
 * jhdf is a desktop library: it reads through `java.nio`, loads its filters in a static
 * initialiser and was written against the JVM, not ART. A unit test proves the parse, not that the
 * library runs where the app does — which is the lesson `MeteoAlarmMapperDeviceTest` is here for.
 */
class NowcastGridDeviceTest {

    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }

    @Test
    fun theNowcastReadsOnAndroid() {
        val errors = mutableListOf<Throwable>()
        val inca = NowcastGrid.map(bytes("map-2026-09-16/inca-1730Z.nc"), NowcastKind.NOWCAST) { errors += it }
        assertEquals(emptyList<Throwable>(), errors)
        assertEquals(Instant.parse("2026-09-16T17:30:00Z"), inca.issuedAt)
        assertEquals(12, inca.steps.size)
        assertEquals(2976, inca.steps.first().cells.size)
    }

    @Test
    fun theOutlookReadsOnAndroid() {
        val errors = mutableListOf<Throwable>()
        val arome = NowcastGrid.map(bytes("map-2026-09-16/arome-ens-1200Z.nc"), NowcastKind.OUTLOOK) { errors += it }
        assertEquals(emptyList<Throwable>(), errors)
        assertEquals(25, arome.steps.size)
        assertTrue(arome.steps.flatMap { it.cells }.any { it.upperMmPerHour != null })
    }
}
