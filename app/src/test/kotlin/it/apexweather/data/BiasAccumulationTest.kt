package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import it.apexweather.Fixtures
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.OpenMeteoMapper
import it.apexweather.data.remote.OpenMeteoResponse
import it.apexweather.data.remote.OpenMeteoStationResponse
import it.apexweather.data.remote.SiagApi
import it.apexweather.data.remote.SiagStationRow
import it.apexweather.data.remote.SiagStationsResponse
import it.apexweather.data.remote.EnsembleApi
import it.apexweather.data.remote.GeoSphereApi
import it.apexweather.data.remote.KmosResponse
import it.apexweather.data.remote.MeteoAlarmApi
import it.apexweather.data.remote.OdhApi
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.DayPart
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes

/**
 * Proof that the corrector eventually speaks, which no single refresh can show.
 *
 * `station_history` is filled in over half a day and a cell needs six hours in it, so the first
 * thing a reader gets from BiasCorrector is silence — for about a day. Everything up to now has
 * tested the two ends separately: that a row is written with the right lead buckets in it, and that
 * a list of samples produces the right biases. What neither shows is that running the real write
 * path hour after hour actually accumulates samples the read path can use, in the cells it should.
 *
 * So the clock is turned by hand through two days of hourly refreshes against a station and a model
 * that are made up but consistent: the thermometer reads a plain diurnal curve, and one model is
 * given a habit that depends both on the part of the day and on how far ahead it was asked. What
 * comes back out of the repository has to be that habit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiasAccumulationTest {

    /** What the thermometer reads: a plain diurnal swing, warmest mid-afternoon. */
    private fun truth(hour: Instant): Double {
        val h = hour.atZone(SouthTyrol.ZONE).hour
        return 12.0 + 8.0 * sin((h - 9) / 24.0 * 2 * Math.PI)
    }

    /**
     * The habit given to ICON-D2: warm in the afternoon and cold at night, and worse the further
     * ahead it is asked. Every other model is given none, so the test also shows that one model's
     * habit is not smeared across the rest.
     */
    private fun habit(target: Instant, lead: LeadBucket): Double {
        val byPart = when (DayPart.of(target, SouthTyrol.ZONE)) {
            DayPart.AFTERNOON -> 1.0
            DayPart.NIGHT -> -1.0
            else -> 0.0
        }
        val byLead = when (lead) {
            LeadBucket.NOW -> 0.5
            LeadBucket.SIX -> 1.0
            LeadBucket.TWELVE -> 2.0
        }
        return byPart * byLead
    }

    /** The station's live reading, always for the clock's own hour. */
    private inner class Station : SiagApi {
        override suspend fun municipality(istat: String): KmosResponse = throw IOException("not this test")
        override suspend fun stations(categoryId: Int, visibility: Int): SiagStationsResponse {
            val hour = clock.instant().truncatedTo(ChronoUnit.HOURS)
            return SiagStationsResponse(
                rows = listOf(
                    SiagStationRow(
                        code = DORF_TIROL.station!!.code,
                        name = DORF_TIROL.station!!.name,
                        t = "%.1f".format(truth(hour)).replace('.', ','),
                        rh = "70",
                        lastUpdated = LocalDateTime.ofInstant(hour, SouthTyrol.ZONE)
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        latitude = DORF_TIROL.station!!.lat.toString().replace('.', ','),
                        longitude = DORF_TIROL.station!!.lon.toString().replace('.', ','),
                    ),
                ),
            )
        }
    }

    /**
     * The models at the station. The series is what *this run* says, so the error it carries for an
     * hour depends on how far ahead of that hour the run is being made — which is the whole point
     * of the record being written down before the hour arrives.
     */
    private inner class Models : OpenMeteoApi {
        override suspend fun forecast(
            latitude: Double, longitude: Double, timezone: String, forecastDays: Int,
            models: String, hourly: String, daily: String, minutely: String, minutelySteps: Int,
        ): OpenMeteoResponse = throw IOException("not this test")

        override suspend fun stationForecast(
            latitude: Double, longitude: Double, elevation: Int, timezone: String,
            pastDays: Int, forecastDays: Int, models: String, hourly: String,
        ): OpenMeteoStationResponse {
            val run = clock.instant().truncatedTo(ChronoUnit.HOURS)
            val from = run.minus(Duration.ofDays(pastDays.toLong()))
            val hours = (0 until (pastDays + forecastDays) * 24).map { from.plusSeconds(it * 3600L) }
            val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                "time" to JsonArray(
                    hours.map {
                        JsonPrimitive(
                            LocalDateTime.ofInstant(it, SouthTyrol.ZONE)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")),
                        )
                    },
                ),
            )
            OpenMeteoMapper.MODELS.forEach { (source, key) ->
                fields["temperature_2m_$key"] = JsonArray(
                    hours.map { target ->
                        val lead = Duration.between(run, target).toHours()
                        val error = if (source == Source.ICON_D2 && lead >= 0) {
                            habit(target, LeadBucket.judging(lead))
                        } else {
                            0.0
                        }
                        JsonPrimitive(truth(target) + error)
                    },
                )
            }
            return OpenMeteoStationResponse(elevation = 330.0, hourly = JsonObject(fields))
        }
    }

    /**
     * The sources this test has no use for. They refuse with something that is not an [IOException]
     * on purpose: the repository retries those once after three seconds, which is right on a phone
     * whose radio has not settled and is forty-eight wasted three-second waits here.
     */
    private object Absent {
        fun no(): Nothing = throw IllegalStateException("not this test")
    }

    private class NoGeoSphere : GeoSphereApi {
        override suspend fun forecast(latLon: String, parameters: String) = Absent.no()
    }

    private class NoOdh : OdhApi {
        override suspend fun weather(language: String) = Absent.no()
        override suspend fun district(id: Int, language: String) = Absent.no()
    }

    private class NoMeteoAlarm : MeteoAlarmApi {
        override suspend fun italy() = Absent.no()
    }

    private class NoEnsemble : EnsembleApi {
        override suspend fun forecast(
            latitude: Double, longitude: Double, models: String, forecastDays: Int,
            timezone: String, hourly: String,
        ) = Absent.no()
    }

    private lateinit var db: AppDatabase
    private val clock = MutableClock(Instant.parse("2026-09-08T00:00:00Z"))
    private lateinit var repo: WeatherRepository

    @Before fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = WeatherRepository(
            db.weatherDao(), Models(), NoGeoSphere(), Station(),
            NoOdh(), NoMeteoAlarm(), NoEnsemble(), Fixtures.json, clock,
        )
    }

    @After fun tearDown() = db.close()

    @Test
    fun `two days of hourly refreshes teach the corrector the habit it was given`() = runTest(timeout = 5.minutes) {
        repeat(48) {
            repo.refresh(DORF_TIROL, "de")
            clock.now = clock.now.plus(Duration.ofHours(1))
        }

        val bias = repo.snapshot(DORF_TIROL, "de").first().modelBias
        val afternoon = Instant.parse("2026-09-09T13:00:00Z") // 15:00 local
        val night = Instant.parse("2026-09-09T01:00:00Z") // 03:00 local

        // The habit it was given, read back per part of the day and per lead time. A tenth of a
        // degree of slack, because the samples are whole hours of a curve rather than a constant.
        assertEquals(0.5, bias.at(Source.ICON_D2, afternoon, SouthTyrol.ZONE, 0)!!, 0.1)
        assertEquals(1.0, bias.at(Source.ICON_D2, afternoon, SouthTyrol.ZONE, 6)!!, 0.1)
        assertEquals(2.0, bias.at(Source.ICON_D2, afternoon, SouthTyrol.ZONE, 12)!!, 0.1)
        assertEquals(-0.5, bias.at(Source.ICON_D2, night, SouthTyrol.ZONE, 0)!!, 0.1)
        assertEquals(-2.0, bias.at(Source.ICON_D2, night, SouthTyrol.ZONE, 12)!!, 0.1)

        // The morning was given none, and comes back as measured-and-zero rather than as absent.
        val morning = Instant.parse("2026-09-09T07:00:00Z") // 09:00 local
        assertEquals(0.0, bias.at(Source.ICON_D2, morning, SouthTyrol.ZONE, 12)!!, 0.1)

        // And no other model is given ICON-D2's habit.
        assertEquals(0.0, bias.at(Source.ICON_CH1, afternoon, SouthTyrol.ZONE, 12)!!, 0.1)
    }

    /**
     * And that it says nothing until it has the evidence. Six hours of refreshes fills no cell,
     * because a cell wants six hours *of one part of the day at one lead time* and six consecutive
     * hours are spread across two parts and only ever seen at three leads.
     */
    @Test
    fun `six hours of refreshes is not yet a habit`() = runTest {
        repeat(6) {
            repo.refresh(DORF_TIROL, "de")
            clock.now = clock.now.plus(Duration.ofHours(1))
        }
        val bias = repo.snapshot(DORF_TIROL, "de").first().modelBias
        assertNull(bias.at(Source.ICON_D2, Instant.parse("2026-09-08T13:00:00Z"), SouthTyrol.ZONE, 0))
    }
}
