package it.apexweather.data.remote

import it.apexweather.domain.CompassPoint
import it.apexweather.domain.NearbyStation
import it.apexweather.domain.model.StationObservation
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant

/**
 * Weather Underground's PWS contributor API — one endpoint, the current observation of one station.
 *
 * The `near` and `history` endpoints are deliberately absent from the app. Choosing a station is
 * `tools/generate-places.py`'s job, done once against a DEM and reviewed by a human: a phone
 * choosing its own thermometer would spend its quota finding stations instead of reading one, and
 * could not check a claimed altitude against anything — six of the ten stations around Dorf Tirol
 * claim a valley floor while standing on a hillside. And the rapid history is not served for every
 * station: ITIROL26 answers 204 to it while ITIROL16 returns 263 readings a day, so nothing may
 * depend on it.
 *
 * The key is personal, capped at 1500 requests a day and 30 a minute, and **not licensed for
 * redistribution** — which is why the amateur reading never reaches the share card. It is absent in
 * most checkouts, and absent means this whole path is switched off.
 */
interface WeatherUndergroundApi {
    /**
     * The station's latest reading, or **HTTP 204 with no body** when it has reported nothing in the
     * last 60 minutes.
     *
     * 204 is an ordinary outcome rather than a failure, and a live station produces it: ITIROL26
     * answered 204 on three endpoints and then, minutes later, 200 with a reading. The caller falls
     * back to the provincial station quietly — no banner, no failed source, nobody accused.
     */
    @GET("v2/pws/observations/current?format=json&units=m")
    suspend fun current(
        @Query("stationId") stationId: String,
        @Query("apiKey") apiKey: String,
    ): Response<WuResponse>

    companion object { const val BASE_URL = "https://api.weather.com/" }
}

@Serializable
data class WuResponse(val observations: List<WuObservation> = emptyList())

@Serializable
data class WuObservation(
    val stationID: String? = null,
    val obsTimeUtc: String? = null,
    val neighborhood: String? = null,
    val humidity: Int? = null,
    val winddir: Int? = null,
    /**
     * WU's own flag: 1 passed, -1 not checked, 0 failed. **Recorded, and obeyed by nothing.**
     *
     * It is a neighbour-consistency test, and in this terrain a correctly sited station fails it for
     * being right. Measured on ITIROL16 over 263 readings on 2026-09-22: 40 flagged 0, every one of
     * them between 10:44 and 19:04, peaking at 92 % of the 13:00 hour — which is exactly when it
     * disagrees with ITIROL23, ITIROL25 and ITIROL24, the three neighbours that claim 128 to 182 m
     * on ground the DEM puts at 419 to 598. Refusing those readings would throw away the best
     * thermometer's whole afternoon in favour of one 300 m below it.
     */
    val qcStatus: Int? = null,
    val solarRadiation: Double? = null,
    val metric: WuMetric? = null,
)

@Serializable
data class WuMetric(
    val temp: Double? = null,
    val windSpeed: Double? = null,
    val windGust: Double? = null,
    val pressure: Double? = null,
    val precipRate: Double? = null,
    /**
     * Rain since local midnight — the same shape as SIAG's `n`, which is what lets
     * [it.apexweather.domain.StationDry] and [it.apexweather.domain.MeasuredRain] work on an amateur
     * gauge unchanged.
     */
    val precipTotal: Double? = null,
    /** The altitude the station's owner typed into a web form. Believed by nobody; see the generator. */
    val elev: Double? = null,
)

object WeatherUndergroundMapper {
    /**
     * One amateur reading, or null where there is none.
     *
     * The response's `neighborhood` is preferred over the catalogue's name because it is what the
     * station's owner called the place and is usually the better word for a reader — "Tirolo -
     * Tirol" rather than a station code.
     *
     * [WuObservation.solarRadiation] is carried through as null where the station publishes none.
     * **Absent must stay absent**: ITIROL16 has no pyranometer at all — confirmed across all 263 of
     * its readings on 2026-09-22 — and a zero there would tell
     * [it.apexweather.domain.StationSun] the sun is not shining, which is a measurement nobody took.
     *
     * The wind direction is mapped through this app's own [CompassPoint] vocabulary. Note that
     * [StationObservation.windDir] is shown to the reader raw and SIAG fills it with SIAG's own
     * letters, so the two networks do not spell the eight points identically. Fixing that properly
     * means storing degrees and naming them at the point of display, which is a change to the SIAG
     * path and not to this one.
     */
    fun map(body: WuResponse?, station: NearbyStation): StationObservation? {
        val o = body?.observations?.firstOrNull() ?: return null
        val time = o.obsTimeUtc?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val m = o.metric
        return StationObservation(
            stationName = o.neighborhood ?: station.name,
            time = time,
            tempC = m?.temp,
            humidityPct = o.humidity,
            windKmh = m?.windSpeed,
            windDir = o.winddir?.let { CompassPoint.of(it).name },
            gustKmh = m?.windGust,
            precipTodayMm = m?.precipTotal,
            pressureHpa = m?.pressure,
            radiationWm2 = o.solarRadiation,
        )
    }
}
