package it.apexweather.data

import it.apexweather.domain.NearbyStation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The station a reader has chosen to read for one place, overriding the catalogue.
 *
 * `tools/generate-places.py` picks a place's amateur station by measurement — claimed height
 * against SRTM, height costed like distance, then eight weeks of ICON-D2 stability. That is a good
 * rule and it stays the default. It is not the reader's rule: it cannot know that one station is
 * maintained by somebody who will notice when it stops, or that another publishes radiation (which
 * [it.apexweather.domain.StationSun] needs) while the chosen one has no pyranometer at all.
 *
 * [altitudeM] is **the DEM's answer and never the station's own claim**. Weather Underground's
 * elevation is whatever the owner typed into a web form, and that form is in feet, which is how
 * ITIROL26 came to claim 204 m while standing at 654. [it.apexweather.domain.StationDownscale]'s
 * cap is `2 K + 9,8 K/km × |height difference|`, so a station wrong about its height by 450 m buys
 * itself four and a half degrees of licence over the app's most-read number.
 *
 * No `horizon` and no `stabilityK`: a skyline is tens of thousands of DEM samples and a generator
 * job, and the stability score is eight weeks of model runs. `Horizon.usable` already returns false
 * for a missing profile, so a chosen station falls back to the astronomical sun times, which is a
 * documented and tested path.
 */
@Serializable
data class ChosenStation(
    val istat: String,
    /** `"wu"` or `"siag"`, matching [NearbyStation.network]. */
    val network: String,
    val code: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val altitudeM: Int,
    val distanceKm: Double,
) {
    fun toStation(): NearbyStation = NearbyStation(
        code = code, name = name, lat = lat, lon = lon,
        altitudeM = altitudeM, distanceKm = distanceKm, network = network,
    )
}

/**
 * Encoding the list for DataStore, which holds strings.
 *
 * JSON rather than the comma-joined form the recents and the pins use, because a record has eight
 * fields and two of them are names that may contain a comma ("Tirolo - Tirol" does not, but nothing
 * stops one).
 */
object ChosenStations {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(stations: List<ChosenStation>): String = json.encodeToString(stations)

    /** Anything unreadable is nothing, the way every other stored list here drops what it cannot parse. */
    fun decode(stored: String?): List<ChosenStation> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ChosenStation>>(stored) }.getOrDefault(emptyList())
    }

    /**
     * That place's record replaced, or removed where [station] is null — which is how a reader says
     * "go back to whatever the catalogue chose".
     */
    fun with(
        current: List<ChosenStation>,
        station: ChosenStation?,
        istat: String = station?.istat.orEmpty(),
    ): List<ChosenStation> = current.filterNot { it.istat == istat } + listOfNotNull(station)
}
