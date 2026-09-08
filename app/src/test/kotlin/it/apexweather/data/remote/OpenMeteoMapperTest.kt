package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.WmoCodes
import it.apexweather.domain.model.Source
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class OpenMeteoMapperTest {
    private val raw = Fixtures.read("openmeteo.json")
    private val resp = Fixtures.json.decodeFromString(OpenMeteoResponse.serializer(), raw)
    private val rawObj = Fixtures.json.parseToJsonElement(raw).jsonObject
    private val fetchedAt = Instant.parse("2026-09-08T12:00:00Z")
    private val result = OpenMeteoMapper.map(resp, fetchedAt)

    @Test
    fun `maps all five models`() {
        assertEquals(setOf(Source.ICON_CH1, Source.ICON_CH2, Source.ICON_2I, Source.ICON_D2, Source.ECMWF), result.keys)
    }

    @Test
    fun `hourly values match raw arrays and skip null hours`() {
        val hourly = rawObj["hourly"]!!.jsonObject
        val rawTemps = hourly["temperature_2m_icon_d2"]!!.jsonArray
        val expectedCount = rawTemps.count { it.jsonPrimitive.content != "null" }
        val d2 = result.getValue(Source.ICON_D2)
        assertEquals(expectedCount, d2.hourly.size)
        val first = d2.hourly.first()
        assertEquals(rawTemps[0].jsonPrimitive.double, first.tempC, 0.0)
        assertEquals(hourly["wind_speed_10m_icon_d2"]!!.jsonArray[0].jsonPrimitive.double, first.windKmh, 0.0)
        assertEquals(WmoCodes.toCondition(hourly["weather_code_icon_d2"]!!.jsonArray[0].jsonPrimitive.int), first.condition)
        val firstTime = hourly["time"]!!.jsonArray[0].jsonPrimitive.content
        assertEquals(LocalDateTime.parse(firstTime).atZone(DorfTirol.ZONE).toInstant(), first.time)
    }

    @Test
    fun `daily carries sunrise and sunset and skips days without temperature`() {
        val ecmwf = result.getValue(Source.ECMWF)
        assertEquals(7, ecmwf.daily.size)
        assertNotNull(ecmwf.daily.first().sunrise)
        assertNotNull(ecmwf.daily.first().sunset)
        val ch1 = result.getValue(Source.ICON_CH1)
        assertTrue(ch1.daily.size < 7) // 48 h model → only the first 2-3 days have min/max
    }

    @Test
    fun `issuedAt equals fetchedAt because Open-Meteo has no run time`() {
        assertEquals(fetchedAt, result.getValue(Source.ICON_CH1).issuedAt)
    }

    @Test
    fun `missing precipitation probability stays null (ICON-2I)`() {
        assertTrue(result.getValue(Source.ICON_2I).hourly.all { it.precipProb == null })
    }
}
