package it.apexweather.data.remote

import it.apexweather.domain.NearbyStation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class WeatherUndergroundMapperTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val station = NearbyStation(
        code = "ITIROL16", name = "Tirolo - Tirol",
        lat = 46.693246, lon = 11.155237, altitudeM = 634, distanceKm = 0.49, network = "wu",
    )

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun body(text: String = fixture("wu_itirol16_current.json")) =
        json.decodeFromString(WuResponse.serializer(), text)

    @Test
    fun `maps the recorded response`() {
        val o = WeatherUndergroundMapper.map(body(), station)!!
        assertEquals(Instant.parse("2026-09-22T20:04:44Z"), o.time)
        assertEquals(13.0, o.tempC!!, 0.0)
        assertEquals(44, o.humidityPct)
        assertEquals(0.0, o.windKmh!!, 0.0)
        assertEquals(0.0, o.gustKmh!!, 0.0)
        assertEquals(0.0, o.precipTodayMm!!, 0.0)
        assertEquals(1023.03, o.pressureHpa!!, 0.001)
        // The owner's own word for the place, not the station code.
        assertEquals("Tirolo - Tirol", o.stationName)
        // 338° sits inside the 45° sector centred on north, which runs 337,5 to 22,5 — so it is N,
        // not NW. Eight points is all the app claims; see CompassPoint.
        assertEquals("N", o.windDir)
    }

    /**
     * ITIROL16 publishes no radiation at all — confirmed across all 263 of its readings on
     * 2026-09-22, not inferred from one. Absent must stay absent: a station with no pyranometer
     * must not be made to say the sun is not shining, or StationSun has a measurement nobody took.
     */
    @Test
    fun `missing radiation stays null rather than becoming zero`() {
        assertNull(WeatherUndergroundMapper.map(body(), station)!!.radiationWm2)
    }

    /** HTTP 204 is an ordinary outcome, not an error: a live station answers it intermittently. */
    @Test
    fun `no body maps to no reading`() {
        assertNull(WeatherUndergroundMapper.map(null, station))
    }

    @Test
    fun `an empty observation list maps to no reading`() {
        assertNull(WeatherUndergroundMapper.map(body("""{"observations":[]}"""), station))
    }

    /**
     * qcStatus is recorded and obeyed by nothing. It is a neighbour-consistency test, and in this
     * terrain a correctly sited station fails it for being right: ITIROL16 was flagged 0 on 40 of
     * 263 readings on 2026-09-22, every one between 10:44 and 19:04, when it disagrees with three
     * neighbours claiming 128 to 182 m on ground the DEM puts at 419 to 598 m. Refusing those
     * readings would drop the best thermometer's whole afternoon and fall back to one 300 m below.
     */
    @Test
    fun `a failed qc flag still maps to a reading`() {
        val flagged = fixture("wu_itirol16_current.json").replace("\"qcStatus\": 1", "\"qcStatus\": 0")
        assertEquals(13.0, WeatherUndergroundMapper.map(body(flagged), station)!!.tempC!!, 0.0)
    }

    /** A reading with no timestamp is not a reading: nothing downstream can age it. */
    @Test
    fun `an observation without a time maps to no reading`() {
        val timeless = fixture("wu_itirol16_current.json")
            .replace("\"obsTimeUtc\": \"2026-09-22T20:04:44Z\"", "\"obsTimeUtc\": null")
        assertNull(WeatherUndergroundMapper.map(body(timeless), station))
    }
}
