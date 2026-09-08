package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime

class GeoSphereMapperTest {
    private val resp = Fixtures.json.decodeFromString(GeoSphereResponse.serializer(), Fixtures.read("geosphere.json"))
    private val fetchedAt = Instant.parse("2026-09-08T14:00:00Z")
    private val fc = GeoSphereMapper.map(resp, fetchedAt)
    private val params = resp.features.first().properties.parameters

    @Test
    fun `source issuedAt and count`() {
        assertEquals(Source.GEOSPHERE_AROME, fc.source)
        assertEquals(OffsetDateTime.parse(resp.referenceTime).toInstant(), fc.issuedAt)
        assertEquals(resp.timestamps.size, fc.hourly.size)
        assertTrue(fc.hourly.size >= 48)
    }

    @Test
    fun `precipitation is the hourly difference of the accumulated series`() {
        val acc = params.getValue("rr_acc").data
        assertEquals(acc[0]!!, fc.hourly[0].precipMm, 1e-9)
        assertEquals(acc[3]!! - acc[2]!!, fc.hourly[3].precipMm, 1e-9)
        assertTrue(fc.hourly.all { it.precipMm >= 0.0 })
    }

    @Test
    fun `wind converted from u v components`() {
        val (speed, dir) = GeoSphereMapper.windFromUV(u = 0.0, v = -5.0) // blowing toward south = from north
        assertEquals(18.0, speed, 1e-9)
        assertEquals(0, dir)
        val (_, west) = GeoSphereMapper.windFromUV(u = 5.0, v = 0.0) // toward east = from west
        assertEquals(270, west)
    }

    @Test
    fun `condition derivation`() {
        assertEquals(Condition.CLEAR, GeoSphereMapper.condition(0.0, 0.0, tcc = 0.1, tempC = 20.0, cape = 0.0))
        assertEquals(Condition.PARTLY_CLOUDY, GeoSphereMapper.condition(0.0, 0.0, tcc = 0.5, tempC = 20.0, cape = 0.0))
        assertEquals(Condition.CLOUDY, GeoSphereMapper.condition(0.05, 0.0, tcc = 0.95, tempC = 20.0, cape = 0.0))
        assertEquals(Condition.DRIZZLE, GeoSphereMapper.condition(0.3, 0.0, tcc = 1.0, tempC = 15.0, cape = 0.0))
        assertEquals(Condition.RAIN, GeoSphereMapper.condition(2.0, 0.0, tcc = 1.0, tempC = 15.0, cape = 0.0))
        assertEquals(Condition.HEAVY_RAIN, GeoSphereMapper.condition(5.0, 0.0, tcc = 1.0, tempC = 15.0, cape = 100.0))
        assertEquals(Condition.THUNDERSTORM, GeoSphereMapper.condition(3.0, 0.0, tcc = 1.0, tempC = 25.0, cape = 800.0))
        assertEquals(Condition.SNOW, GeoSphereMapper.condition(1.0, 0.9, tcc = 1.0, tempC = -2.0, cape = 0.0))
        assertEquals(Condition.HEAVY_SNOW, GeoSphereMapper.condition(3.0, 2.8, tcc = 1.0, tempC = -2.0, cape = 0.0))
        assertEquals(Condition.SLEET, GeoSphereMapper.condition(2.0, 0.8, tcc = 1.0, tempC = 1.0, cape = 0.0))
    }

    @Test
    fun `daily aggregated from hourly`() {
        assertTrue(fc.daily.size >= 2)
        assertTrue(fc.daily.all { it.maxC >= it.minC })
    }
}
