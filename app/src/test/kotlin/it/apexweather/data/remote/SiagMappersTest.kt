package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.NearbyStation
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

    /** Dorf Tirol's station, as the generated catalogue has it. */
    private val MERAN = NearbyStation("23200MS", "Meran", 46.688, 11.1366, 330, 1.53)

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
    fun `observation picks the place's own station and converts units`() {
        val resp = Fixtures.json.decodeFromString(SiagStationsResponse.serializer(), Fixtures.read("siag_stations.json"))
        val obs = SiagMappers.mapObservation(resp, MERAN)!!
        val row = resp.rows.first { it.code == MERAN.code }
        assertEquals(row.name, obs.stationName)
        assertEquals(row.t.siagDouble(), obs.tempC)
        row.ff.siagDouble()?.let { assertEquals(it * 3.6, obs.windKmh!!, 0.051) } ?: assertNull(obs.windKmh) // km/h rounded to 1 decimal
        assertEquals(LocalDateTime.parse(row.lastUpdated).atZone(SouthTyrol.ZONE).toInstant(), obs.time)
    }

    @Test
    fun `observation is null when there is no station at all`() {
        assertNull(SiagMappers.mapObservation(SiagStationsResponse(rows = emptyList()), MERAN))
    }

    /**
     * A station decommissioned since the catalogue was generated must not take the whole observation
     * with it: the nearest one still reporting stands in.
     */
    @Test
    fun `a station that has gone falls back to the nearest one still reporting`() {
        val resp = Fixtures.json.decodeFromString(SiagStationsResponse.serializer(), Fixtures.read("siag_stations.json"))
        val vanished = NearbyStation("00000XX", "Nowhere", MERAN.lat, MERAN.lon, 330, 0.0)
        val obs = SiagMappers.mapObservation(resp, vanished)
        assertNotNull(obs)
        assertEquals("Meran", obs!!.stationName)
    }

    @Test
    fun `dash strings parse to null`() {
        assertNull("--".siagDouble())
        assertEquals(4.3, "4.3".siagDouble()!!, 0.0)
        assertTrue(null.siagDouble() == null)
    }

    /**
     * The station's precipitation figure is a daily accumulation, not a rate. Checked against the
     * live network on 2026-09-10: every one of the 57 stations reported a non-zero value between 8
     * and 27.6 mm at the same timestamp, which no hourly reading would do. Reading it as "it is
     * raining now" is the obvious mistake, so the field and the label both say what it is.
     */
    @Test
    fun `the station's precipitation is the total so far today`() {
        val resp = Fixtures.json.decodeFromString(SiagStationsResponse.serializer(), Fixtures.read("siag_stations.json"))
        val obs = SiagMappers.mapObservation(resp, MERAN)!!
        val row = resp.rows.first { it.code == MERAN.code }
        assertEquals(row.n.siagDouble(), obs.precipTodayMm)
    }

    /**
     * Two values that sat on the row unread: snow depth and sunshine duration.
     *
     * Both are seasonal in opposite directions and both are what anybody here asks first in their
     * own season. `hs` is absent for 56 of the 57 stations in a September recording — Karerpass, at
     * 1752 m, is the one with anything to report — and absent has to stay absent: a station with no
     * snow sensor must not be made to say the hillside is bare.
     */
    @Test
    fun `snow depth and sunshine are read off the station row`() {
        val resp = Fixtures.json.decodeFromString(SiagStationsResponse.serializer(), Fixtures.read("siag_stations.json"))
        val meran = SiagMappers.mapObservation(resp, MERAN)!!
        assertNull("Meran has no snow in September and says nothing rather than zero", meran.snowDepthCm)
        // "05:31" — the one value on this row that is not a decimal.
        assertEquals(5 * 60 + 31, meran.sunshineTodayMinutes)

        val snowy = resp.rows.first { it.hs.siagDouble() != null }
        val up = SiagMappers.mapObservation(resp, NearbyStation(snowy.code!!, snowy.name!!, 46.4, 11.6, 1752, 0.0))!!
        assertEquals(snowy.hs.siagDouble(), up.snowDepthCm)
    }

    @Test
    fun `sunshine duration is minutes, and anything that is not a clock is nothing`() {
        assertEquals(0, "00:00".siagMinutes())
        assertEquals(5 * 60 + 5, "05:05".siagMinutes())
        assertEquals(13 * 60 + 45, "13:45".siagMinutes())
        assertNull("--".siagMinutes())
        assertNull(null.siagMinutes())
        assertNull("5.5".siagMinutes())
        assertNull("05:75".siagMinutes())
    }
}
