package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun `all-null t2m fails loudly`() {
        val allNullResp = GeoSphereResponse(
            referenceTime = "2026-09-08T12:00+00:00",
            timestamps = listOf("2026-09-08T13:00+00:00", "2026-09-08T14:00+00:00"),
            features = listOf(
                GeoSphereFeature(
                    properties = GeoSphereProperties(
                        parameters = mapOf(
                            "t2m" to GeoSphereParam(data = listOf(null, null)),
                        ),
                    ),
                ),
            ),
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            GeoSphereMapper.map(allNullResp, fetchedAt)
        }
        assertTrue(ex.message.orEmpty().contains("t2m"))
    }

    @Test
    fun `accumulated precipitation skips null entries without double counting`() {
        val resp = GeoSphereResponse(
            referenceTime = "2026-09-08T12:00+00:00",
            timestamps = listOf(
                "2026-09-08T13:00+00:00",
                "2026-09-08T14:00+00:00",
                "2026-09-08T15:00+00:00",
            ),
            features = listOf(
                GeoSphereFeature(
                    properties = GeoSphereProperties(
                        parameters = mapOf(
                            "t2m" to GeoSphereParam(data = listOf(10.0, 10.0, 10.0)),
                            "rr_acc" to GeoSphereParam(data = listOf(1.0, null, 3.0)),
                        ),
                    ),
                ),
            ),
        )
        val result = GeoSphereMapper.map(resp, fetchedAt)
        assertEquals(1.0, result.hourly[0].precipMm, 1e-9)
        assertEquals(0.0, result.hourly[1].precipMm, 1e-9)
        assertEquals(2.0, result.hourly[2].precipMm, 1e-9)
    }
    /**
     * AROME publishes no weather code and this mapper had no fog branch at all, so one of the app's
     * ten sources could never vote for fog however saturated the air it was forecasting. The dataset
     * offers nineteen parameters and not one of them is visibility, so saturation under a covered
     * sky stands in for it.
     */
    @Test
    fun `saturated air under a covered sky is fog`() {
        assertEquals(
            Condition.FOG,
            GeoSphereMapper.condition(precipMm = 0.0, snowMm = 0.0, tcc = 1.0, tempC = 9.0, cape = 0.0, rh2m = 99.0),
        )
    }

    /** Rain saturates the air too, and rain is the more useful thing to be told. */
    @Test
    fun `saturated air with rain in it is rain`() {
        assertEquals(
            Condition.RAIN,
            GeoSphereMapper.condition(precipMm = 1.2, snowMm = 0.0, tcc = 1.0, tempC = 9.0, cape = 0.0, rh2m = 100.0),
        )
    }

    /** Saturated but with a broken sky is not cloud on the ground. */
    @Test
    fun `saturated air under a clearing sky is not fog`() {
        assertEquals(
            Condition.PARTLY_CLOUDY,
            GeoSphereMapper.condition(precipMm = 0.0, snowMm = 0.0, tcc = 0.5, tempC = 9.0, cape = 0.0, rh2m = 99.0),
        )
    }

    /** Humidity missing for an hour must not read as saturated. */
    @Test
    fun `no humidity is not fog`() {
        assertEquals(
            Condition.CLOUDY,
            GeoSphereMapper.condition(precipMm = 0.0, snowMm = 0.0, tcc = 1.0, tempC = 9.0, cape = 0.0, rh2m = null),
        )
    }

}
