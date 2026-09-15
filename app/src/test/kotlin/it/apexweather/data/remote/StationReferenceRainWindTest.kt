package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Recorded 2026-09-15 for Meran with temperature, rain and wind; see the plan's adjustments. */
class StationReferenceRainWindTest {

    private fun station(name: String) = OpenMeteoStationMapper.map(
        Fixtures.json.decodeFromString(OpenMeteoStationResponse.serializer(), Fixtures.read(name)),
        Instant.parse("2026-09-15T08:00:00Z"),
    )

    @Test
    fun `the station call's rain and wind are read per model`() {
        val ref = station("openmeteo_station_rain_wind.json")
        // 2026-09-10T03:00 local is 01:00Z: ICON-D2 had 1,0 mm in that hour.
        assertEquals(1.0, ref.rainAt(Instant.parse("2026-09-10T01:00:00Z")).getValue(Source.ICON_D2), 1e-9)
        // 2026-09-11T15:00 local is 13:00Z: 23,0 °C and 1,1 km/h.
        assertEquals(23.0, ref.at(Instant.parse("2026-09-11T13:00:00Z")).getValue(Source.ICON_D2), 1e-9)
        assertEquals(1.1, ref.windAt(Instant.parse("2026-09-11T13:00:00Z")).getValue(Source.ICON_D2), 1e-9)
        assertEquals(OpenMeteoMapper.MODELS.keys.map { it.name }.toSet(), ref.rainBySource.keys)
    }

    /** The temperature-only recording still maps, with nothing invented for rain or wind. */
    @Test
    fun `a response without rain or wind leaves those series empty`() {
        val ref = station("openmeteo_station.json")
        assertTrue(ref.bySource.isNotEmpty())
        assertTrue(ref.rainBySource.isEmpty())
        assertTrue(ref.windBySource.isEmpty())
    }

    @Test
    fun `AROME's rain and wind are carried with its temperature`() {
        val arome = GeoSphereMapper.map(
            Fixtures.json.decodeFromString(GeoSphereResponse.serializer(), Fixtures.read("geosphere.json")),
            Instant.parse("2026-09-08T17:00:00Z"),
        )
        val ref = station("openmeteo_station.json").plus(Source.GEOSPHERE_AROME, arome.hourly)
        assertTrue(ref.bySource.getValue(Source.GEOSPHERE_AROME.name).isNotEmpty())
        assertEquals(arome.hourly.size, ref.rainBySource.getValue(Source.GEOSPHERE_AROME.name).size)
        assertTrue(ref.windBySource.getValue(Source.GEOSPHERE_AROME.name).isNotEmpty())
    }
}
