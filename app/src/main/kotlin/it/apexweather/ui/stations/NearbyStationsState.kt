package it.apexweather.ui.stations

import it.apexweather.domain.Place
import it.apexweather.domain.model.StationObservation
import kotlin.math.abs

/**
 * One station as the network answered for it.
 *
 * [reading] is null where the station answered but had nothing recent — Weather Underground returns
 * HTTP 204 for a live station that has not reported within the hour, and going quiet for an hour is
 * not the same thing as failing. [error] is for the second case.
 *
 * [claimedAltitudeM] is the station's *own* record of where it stands, straight from the response,
 * because that is the number worth putting on screen: it is the one the generator checks against a
 * DEM, and the one that was wrong by 450 m for ITIROL26.
 */
data class StationProbe(
    val code: String,
    val name: String,
    val distanceKm: Double,
    val claimedAltitudeM: Int?,
    val reading: StationObservation?,
    val error: String? = null,
)

data class StationRow(
    val code: String,
    val name: String,
    val distanceKm: Double,
    val claimedAltitudeM: Int?,
    /** What the catalogue recorded — the DEM's answer — for the one station it knows about. */
    val verifiedAltitudeM: Int? = null,
    val reading: StationObservation?,
    val error: String? = null,
    /** The station this place actually reads, amateur or provincial. */
    val chosen: Boolean = false,
    /** The province's own network: the fallback, and the source of what an amateur station lacks. */
    val provincial: Boolean = false,
    /**
     * The claimed altitude is too far from this place's own to be believed.
     *
     * **Unverified, never "wrong".** The generator has SRTM tiles and refuses a station whose claim
     * disagrees with the ground under it by more than 100 m; the app has no tiles and cannot repeat
     * that check. All it can see is a claim implausible against the village's height, and saying
     * "wrong" on that evidence would be the kind of confident falsehood this app avoids elsewhere.
     *
     * Worth showing at all because WU had ITIROL26 at 204 m against a real 654 — its station form
     * is in feet and 669 had been typed into it — and on this screen that is visible in a second
     * rather than found by reading a JSON payload.
     */
    val altitudeUnverified: Boolean = false,
)

data class NearbyStationsUiState(
    val placeName: String = "",
    val loading: Boolean = true,
    val rows: List<StationRow> = emptyList(),
    /** Nothing answered at all — offline, or the key was refused. */
    val failed: String? = null,
)

object NearbyStationsStateBuilder {
    /**
     * The steepest ground a claimed altitude is allowed to imply, in metres of height per kilometre
     * of distance from the village.
     *
     * A flat height difference will not do, and the first draft of this used one: a station 412 m
     * below the village is perfectly ordinary two kilometres away on the valley floor and flatly
     * impossible 350 metres away, which is a 50° slope. Distance is the term that separates them.
     *
     * 600 m/km is about 31°, steeper than any ground a weather station sits on here and far steeper
     * than the slope from Dorf Tirol down to Meran (264 m over 1,5 km, about 10°). Measured against
     * the real neighbours: ITIROL25 claims 412 m below the village 0,35 km away — 1177 m/km — and
     * ITIROL23 943 m/km, both caught; ITIROL16 at 16 m/km and ITIROL26 at 110 m/km are untouched.
     *
     * **It does not catch everything, and it must not pretend to.** ITIROL24 claims 128 m at
     * 1,76 km, which is 265 m/km and perfectly possible terrain — its claim is still wrong by
     * 291 m against the DEM, and the app has no way to know that. Only the generator, which has
     * SRTM tiles, can. This flags what is impossible, not what is merely untrue.
     */
    const val MAX_PLAUSIBLE_SLOPE_M_PER_KM = 600

    /**
     * Distances below this are treated as this, so a station a few metres away does not divide the
     * allowance down to nothing.
     */
    const val MIN_SLOPE_DISTANCE_KM = 0.2

    fun build(
        place: Place,
        probes: List<StationProbe>,
        provincial: StationObservation?,
        locale: java.util.Locale,
    ): NearbyStationsUiState {
        val chosenCode = place.readingStation?.code
        val rows = probes
            .sortedBy { it.distanceKm }
            .map { p ->
                StationRow(
                    code = p.code,
                    name = p.name,
                    distanceKm = p.distanceKm,
                    claimedAltitudeM = p.claimedAltitudeM,
                    // The catalogue stores the DEM's altitude, and only for the station it chose.
                    verifiedAltitudeM = place.pws?.takeIf { it.code == p.code }?.altitudeM,
                    reading = p.reading,
                    error = p.error,
                    chosen = p.code == chosenCode,
                    altitudeUnverified = p.claimedAltitudeM?.let { claimed ->
                        val allowed = MAX_PLAUSIBLE_SLOPE_M_PER_KM *
                            maxOf(p.distanceKm, MIN_SLOPE_DISTANCE_KM)
                        abs(claimed - place.altitudeM) > allowed
                    } ?: false,
                )
            }

        // The province's own goes last and under a rule rather than among them: it is not a
        // candidate for anything, it is what the app falls back to and where every quantity an
        // amateur station does not publish comes from.
        val official = place.station?.let { s ->
            StationRow(
                code = s.code,
                name = s.name,
                distanceKm = s.distanceKm,
                claimedAltitudeM = s.altitudeM,
                verifiedAltitudeM = s.altitudeM,
                reading = provincial,
                chosen = s.code == chosenCode,
                provincial = true,
            )
        }

        return NearbyStationsUiState(
            placeName = place.name(locale),
            loading = false,
            rows = rows + listOfNotNull(official),
        )
    }
}
