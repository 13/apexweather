package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.SouthTyrol
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

    private val fourteenDay = OpenMeteoMapper.map(
        Fixtures.json.decodeFromString(OpenMeteoResponse.serializer(), Fixtures.read("openmeteo_14d.json")),
        Instant.parse("2026-09-09T12:00:00Z"),
    )

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
        assertEquals(hourly["wind_speed_10m_icon_d2"]!!.jsonArray[0].jsonPrimitive.double, first.windKmh!!, 0.0)
        assertEquals(WmoCodes.toCondition(hourly["weather_code_icon_d2"]!!.jsonArray[0].jsonPrimitive.int), first.condition)
        val firstTime = hourly["time"]!!.jsonArray[0].jsonPrimitive.content
        assertEquals(LocalDateTime.parse(firstTime).atZone(SouthTyrol.ZONE).toInstant(), first.time)
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

    /**
     * `openmeteo.json` was recorded before the app asked for the freezing level, so it has no such
     * column at all — which makes it the case that matters most here. A response that predates a
     * request change must map to a null, never to a zero-metre snow line at the bottom of the valley.
     */
    @Test
    fun `a response without the freezing level column maps to null, not to zero`() {
        assertTrue(result.getValue(Source.ICON_D2).hourly.all { it.freezingLevelM == null })
    }

    /**
     * `openmeteo_14d.json` is the same upstream recorded on 2026-09-09 with the request the app
     * makes today: fourteen days, and the freezing level asked for.
     */
    @Test
    fun `the freezing level is read where the model publishes one`() {
        val hourly = fourteenDay.getValue(Source.ICON_D2).hourly
        val levels = hourly.mapNotNull { it.freezingLevelM }
        assertTrue(levels.isNotEmpty())
        // An Alpine 0 °C isotherm in September; a value outside this is a unit or parsing mistake.
        assertTrue(levels.all { it in 0.0..6000.0 })
    }

    /** ECMWF IFS returns the column all null through Open-Meteo. Absence must not read as sea level. */
    @Test
    fun `ECMWF publishes no freezing level and says so`() {
        assertTrue(fourteenDay.getValue(Source.ECMWF).hourly.all { it.freezingLevelM == null })
    }

    /** Only ECMWF reaches the far end; the regional models stop where their run stops. */
    @Test
    fun `two weeks are asked for and only ECMWF answers for all of them`() {
        assertEquals(14, OpenMeteoMapper.FORECAST_DAYS)
        val ecmwfDays = fourteenDay.getValue(Source.ECMWF).daily.size
        val regionalDays = fourteenDay.getValue(Source.ICON_D2).daily.size
        assertTrue("ECMWF reached only $ecmwfDays days", ecmwfDays >= 10)
        assertTrue("a regional model claimed $regionalDays days", regionalDays < ecmwfDays)
    }

    /**
     * The station call is a separate, deliberately tiny request: one variable, two days, at the
     * station's own coordinates and altitude. It exists to say how much colder the village is than
     * the station at a given hour, so the elevation coming back is the number that matters most.
     */
    @Test
    fun `the station reference is read at the station's altitude`() {
        val resp = Fixtures.json.decodeFromString(
            OpenMeteoStationResponse.serializer(), Fixtures.read("openmeteo_station.json"),
        )
        val reference = OpenMeteoStationMapper.map(resp, Instant.parse("2026-09-09T12:00:00Z"))
        assertEquals(330.0, reference.elevationM, 0.0)
        assertTrue(reference.bySource.isNotEmpty())
        // A September valley floor: outside this the units or the parse are wrong.
        assertTrue(reference.bySource.values.flatMap { it.values }.all { it in -20.0..45.0 })
    }

    /**
     * Kept per model, not collapsed to a median: measuring how wrong each one has lately been needs
     * each one on its own, and that is what pays for the extra rows.
     */
    @Test
    fun `the station reference keeps the models apart`() {
        val reference = OpenMeteoStationMapper.map(
            Fixtures.json.decodeFromString(OpenMeteoStationResponse.serializer(), Fixtures.read("openmeteo_station.json")),
            Instant.parse("2026-09-10T12:00:00Z"),
        )
        assertTrue("only ${reference.bySource.size} models", reference.bySource.size >= 4)
        val anHour = reference.bySource.values.first().keys.first()
        assertTrue(reference.at(Instant.ofEpochSecond(anHour)).size >= 4)
    }

    /** The village sits about 290 m above the station, and the models know it. */
    @Test
    fun `the models put the village colder than the station`() {
        val reference = OpenMeteoStationMapper.map(
            Fixtures.json.decodeFromString(OpenMeteoStationResponse.serializer(), Fixtures.read("openmeteo_station.json")),
            Instant.parse("2026-09-09T12:00:00Z"),
        )
        val village = it.apexweather.domain.ConsensusBlender(SouthTyrol.ZONE).blend(fourteenDay)
        val shared = village.hourly.mapNotNull { hour -> reference.tempAt(hour.time)?.let { hour.tempC - it } }
        assertTrue("no overlapping hours between the two calls", shared.isNotEmpty())
        val median = shared.sorted()[shared.size / 2]
        assertTrue("village-minus-station came out at $median °C", median < 0.0)
        assertTrue("that is not a height difference: $median °C", median > -6.0)
    }

    /**
     * The regional half of the consensus used to be four flavours of ICON, and models sharing a core
     * agree with each other for reasons that have nothing to do with being right. These two are
     * independent HARMONIE-AROME runs, and the third is ECMWF's machine-learned model — the same
     * institution, an entirely different way of forecasting.
     */
    @Test
    fun `the three added models all reach this valley`() {
        listOf(Source.KNMI_HARMONIE, Source.DMI_HARMONIE, Source.ECMWF_AIFS).forEach { source ->
            val hourly = fourteenDay[source]?.hourly.orEmpty()
            assertTrue("$source returned nothing", hourly.isNotEmpty())
            assertTrue("$source returned implausible temperatures", hourly.all { it.tempC in -40.0..45.0 })
        }
    }

    /** The two-kilometre runs are short-range; only the global models reach the end of the list. */
    @Test
    fun `the added regional models are short-range and the AI model is not`() {
        val knmi = fourteenDay.getValue(Source.KNMI_HARMONIE).hourly.size
        val aifs = fourteenDay.getValue(Source.ECMWF_AIFS).hourly.size
        assertTrue("KNMI reached $knmi hours", knmi in 24..120)
        assertTrue("AIFS reached only $aifs hours", aifs > 240)
    }

    @Test
    fun `every model in the table is asked for by name`() {
        assertEquals(Source.entries.size - 2, OpenMeteoMapper.MODELS.size) // KMOS and AROME come from elsewhere
        assertEquals(OpenMeteoMapper.MODELS.size, OpenMeteoMapper.MODELS.values.toSet().size)
    }

    /**
     * Quarter-hourly precipitation is what turns "rain some time in the 15:00 hour" into "rain from
     * 15:15". Only the regional models publish one natively.
     */
    @Test
    fun `the regional models carry a quarter-hourly series and the globals do not`() {
        val regional = fourteenDay.getValue(Source.ICON_D2).minutely
        assertTrue("no quarter-hourly series", regional.isNotEmpty())
        assertEquals(OpenMeteoMapper.MINUTELY_STEPS, regional.size)
        // Fifteen minutes apart, in order.
        val gaps = regional.zipWithNext { a, b -> java.time.Duration.between(a.time, b.time).toMinutes() }
        assertTrue("steps were $gaps", gaps.all { it == 15L })
        assertTrue(regional.all { it.precipMm >= 0.0 })

        // A 25 km global returns a series when asked, but it is interpolated from its own hourly
        // one; letting it vote on when the rain starts would be false precision.
        assertTrue(fourteenDay.getValue(Source.ECMWF).minutely.isEmpty())
        assertTrue(fourteenDay.getValue(Source.ECMWF_AIFS).minutely.isEmpty())
    }
}
