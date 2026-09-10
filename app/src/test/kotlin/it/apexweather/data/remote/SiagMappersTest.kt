package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.SiagCodes
import it.apexweather.domain.model.Source
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime

class SiagMappersTest {
    private val fetchedAt = Instant.parse("2026-09-08T14:00:00Z")

    @Test
    fun `kmos maps 3-hourly points with symbol conditions and third of 3h precipitation`() {
        val resp = Fixtures.json.decodeFromString(KmosResponse.serializer(), Fixtures.read("siag_kmos.json"))
        val fc = SiagMappers.mapKmos(resp, fetchedAt)
        assertEquals(Source.SIAG_KMOS, fc.source)
        val temp3 = resp.municipality.temp3!!.data
        assertEquals(temp3.size, fc.hourly.size)
        assertEquals(OffsetDateTime.parse(temp3[0].date).toInstant(), fc.hourly[0].time)
        assertEquals(temp3[0].value!!.double, fc.hourly[0].tempC, 0.0)
        val sym0 = resp.municipality.symbols3!!.data[0].value!!.contentOrNull
        assertEquals(SiagCodes.toCondition(sym0), fc.hourly[0].condition)
        val prec0 = resp.municipality.precSum3!!.data[0].value!!.double
        assertEquals(prec0 / 3.0, fc.hourly[0].precipMm, 1e-9)
        assertEquals(resp.municipality.tempMax24!!.data.size, fc.daily.size)
        assertEquals(OffsetDateTime.parse(resp.info.currentModelRun!!).toInstant(), fc.issuedAt)
    }

    @Test
    fun `kmos precipProb comes from precProb3`() {
        val resp = Fixtures.json.decodeFromString(KmosResponse.serializer(), Fixtures.read("siag_kmos.json"))
        val fc = SiagMappers.mapKmos(resp, fetchedAt)
        assertEquals(resp.municipality.precProb3!!.data[0].value!!.intOrNull, fc.hourly[0].precipProb)
    }

    @Test
    fun `kmos without temp3 fails loudly`() {
        val resp = KmosResponse(info = KmosInfo(), municipality = KmosMunicipality(code = "021101"))
        val ex = assertThrows(IllegalStateException::class.java) { SiagMappers.mapKmos(resp, fetchedAt) }
        assertTrue(ex.message!!.contains("temp3"))
    }

    @Test
    fun `bulletin evolution normalises CRLF`() {
        val w = OdhWeatherResponse(date = "2026-09-08T11:00:00", evolutionTitle = "T", evolution = "a\r\nb")
        val d = OdhDistrictResponse()
        val b = SiagMappers.mapBulletin(w, d, "de")
        assertEquals("a\nb", b.evolution)
    }

    @Test
    fun `bulletin merges evolution text, conditions and district days`() {
        val w = Fixtures.json.decodeFromString(OdhWeatherResponse.serializer(), Fixtures.read("odh_weather_de.json"))
        val d = Fixtures.json.decodeFromString(OdhDistrictResponse.serializer(), Fixtures.read("odh_district2_de.json"))
        val b = SiagMappers.mapBulletin(w, d, "de")
        assertEquals("de", b.language)
        assertEquals(w.evolutionTitle, b.title)
        assertEquals(LocalDateTime.parse(w.date).atZone(SouthTyrol.ZONE).toInstant(), b.issuedAt)
        assertEquals(w.conditions.size, b.conditions.size)
        assertEquals(d.forecast.size, b.days.size)
        val day0 = b.days[0]
        assertEquals(d.forecast[0].weatherCode, day0.code)
        assertEquals(d.forecast[0].maxTemp, day0.maxC)
        assertNotNull(day0.iconUrl)
    }

    @Test
    fun `observation picks the Meran station and converts units`() {
        val resp = Fixtures.json.decodeFromString(SiagStationsResponse.serializer(), Fixtures.read("siag_stations.json"))
        val obs = SiagMappers.mapObservation(resp)!!
        val row = resp.rows.first { it.code == DorfTirol.STATION_CODE }
        assertEquals(row.name, obs.stationName)
        assertEquals(row.t.siagDouble(), obs.tempC)
        row.ff.siagDouble()?.let { assertEquals(it * 3.6, obs.windKmh!!, 0.051) } ?: assertNull(obs.windKmh) // km/h rounded to 1 decimal
        assertEquals(LocalDateTime.parse(row.lastUpdated).atZone(SouthTyrol.ZONE).toInstant(), obs.time)
    }

    @Test
    fun `observation is null when station missing`() {
        assertNull(SiagMappers.mapObservation(SiagStationsResponse(rows = emptyList())))
    }

    @Test
    fun `dash strings parse to null`() {
        assertNull("--".siagDouble())
        assertEquals(4.3, "4.3".siagDouble()!!, 0.0)
        assertTrue(null.siagDouble() == null)
    }
}
