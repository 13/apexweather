package it.apexweather.ui.stations

import it.apexweather.data.ChosenStation
import it.apexweather.domain.Place
import it.apexweather.domain.StationHeight
import it.apexweather.domain.model.StationObservation

/**
 * One station as the network answered for it.
 *
 * [reading] is null where the station answered but had nothing recent — Weather Underground returns
 * HTTP 204 for a live station that has not reported within the hour, and going quiet for an hour is
 * not the same thing as failing. [error] is for the second case.
 *
 * [claimedAltitudeM] is the station's *own* record of where it stands and [demAltitudeM] the ground
 * under its coordinates, from Open-Meteo's elevation endpoint. Both, because the difference is the
 * information: WU's elevation form is in feet, and ITIROL26 stood in its records at 204 m against
 * real ground at 654.
 */
data class StationProbe(
    val code: String,
    val name: String,
    val distanceKm: Double,
    /** The station's own coordinates, which is where the ground under it was asked about. */
    val lat: Double,
    val lon: Double,
    val claimedAltitudeM: Int?,
    val demAltitudeM: Int?,
    val reading: StationObservation?,
    val error: String? = null,
)

data class StationRow(
    val code: String,
    val name: String,
    val distanceKm: Double,
    /** Carried so a chosen station is stored with the coordinates the models must be asked about. */
    val lat: Double,
    val lon: Double,
    /** What the station says about itself. Shown, and acted on by nothing. */
    val claimedAltitudeM: Int?,
    /** The ground under it, which is the height this app will use. Null where it could not be asked. */
    val demAltitudeM: Int?,
    val reading: StationObservation?,
    val error: String? = null,
    /** The station this place actually reads, amateur or provincial. */
    val chosen: Boolean = false,
    /** The province's own network: the fallback, and the source of what an amateur station lacks. */
    val provincial: Boolean = false,
    /**
     * The station's claim about its own height is more than [StationHeight.MAX_DEM_DISAGREEMENT_M]
     * from the ground under it.
     *
     * Shown rather than acted on: the height used is the ground's either way. What it warns about is
     * that a record out by hundreds of metres suggests the coordinates may be somewhere else
     * entirely, which no elevation lookup can correct.
     */
    val heightDisputed: Boolean = false,
    /** Whether this station can be picked: it is not already chosen, and its height is known. */
    val selectable: Boolean = false,
) {
    /**
     * This row as something storable, or null where it may not be stored.
     *
     * Null for an amateur station whose ground is unknown. A height the app cannot check is a height
     * it will not act on, and `StationDownscale`'s cap is built out of exactly that number.
     */
    fun asChosen(istat: String): ChosenStation? {
        val altitude = if (provincial) claimedAltitudeM else StationHeight.heightOf(claimedAltitudeM, demAltitudeM)
        if (altitude == null) return null
        return ChosenStation(
            istat = istat,
            network = if (provincial) "siag" else "wu",
            code = code,
            // The reading's own name where there is one — "Tirolo - Tirol" is what the station's
            // owner called the place and is a better word for a reader than a station code.
            name = reading?.stationName ?: name,
            lat = lat,
            lon = lon,
            altitudeM = altitude,
            distanceKm = distanceKm,
        )
    }
}

data class NearbyStationsUiState(
    val placeName: String = "",
    val loading: Boolean = true,
    val rows: List<StationRow> = emptyList(),
    /** Nothing answered at all — offline, or the key was refused. */
    val failed: String? = null,
    /** The elevation request failed, so no amateur station's height is known and none may be picked. */
    val heightsUnknown: Boolean = false,
)

object NearbyStationsStateBuilder {
    fun build(
        place: Place,
        probes: List<StationProbe>,
        provincial: StationObservation?,
        locale: java.util.Locale,
    ): NearbyStationsUiState {
        val chosenCode = place.readingStation?.code
        val rows = probes.sortedBy { it.distanceKm }.map { p ->
            val height = StationHeight.heightOf(p.claimedAltitudeM, p.demAltitudeM)
            StationRow(
                code = p.code,
                name = p.name,
                distanceKm = p.distanceKm,
                lat = p.lat,
                lon = p.lon,
                claimedAltitudeM = p.claimedAltitudeM,
                demAltitudeM = p.demAltitudeM,
                reading = p.reading,
                error = p.error,
                chosen = p.code == chosenCode,
                heightDisputed = StationHeight.disputed(p.claimedAltitudeM, p.demAltitudeM),
                selectable = p.code != chosenCode && height != null,
            )
        }

        // The province's own goes last and under a rule rather than among them: it is where every
        // quantity an amateur station does not publish comes from, and its height was surveyed
        // rather than typed, so it needs no elevation request to be selectable.
        val official = place.station?.let { s ->
            StationRow(
                code = s.code,
                name = s.name,
                distanceKm = s.distanceKm,
                lat = s.lat,
                lon = s.lon,
                claimedAltitudeM = s.altitudeM,
                demAltitudeM = s.altitudeM,
                reading = provincial,
                chosen = s.code == chosenCode,
                provincial = true,
                selectable = s.code != chosenCode,
            )
        }

        return NearbyStationsUiState(
            placeName = place.name(locale),
            loading = false,
            rows = rows + listOfNotNull(official),
            heightsUnknown = probes.isNotEmpty() && probes.all { it.demAltitudeM == null },
        )
    }
}
