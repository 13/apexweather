package it.apexweather.domain

import kotlinx.serialization.Serializable
import java.util.Locale

/** The weather station that speaks for a place, precomputed by `tools/generate-places.py`. */
@Serializable
data class NearbyStation(
    val code: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val altitudeM: Int,
    val distanceKm: Double,
    /**
     * How steady village-minus-station is in ICON-D2 over eight weeks, in kelvin — the number this
     * station was chosen on, and a fair warning about how much the reading is worth.
     *
     * A station in the same air has a difference that barely moves whatever its size; one over a
     * ridge spends every clear night in a different inversion. Null where the place had only one
     * candidate, or the models could not be asked when the catalogue was generated.
     */
    val stabilityK: Double? = null,
)

/**
 * One South Tyrolean municipality: everything that differs between locations, in one object.
 *
 * The ISTAT code is what SIAG KMOS is addressed by, the coordinates are what Open-Meteo and
 * GeoSphere are asked about, the district is which of the seven bulletins this place reads, and
 * the station is the nearest thermometer — null where none is close enough to speak for the place,
 * in which case the app shows the model consensus and says nothing about a measurement.
 */
@Serializable
data class Place(
    val istat: String,
    val nameDe: String,
    val nameIt: String,
    val nameEn: String,
    val lat: Double,
    val lon: Double,
    val altitudeM: Int,
    val district: Int,
    val station: NearbyStation? = null,
) {
    /** The name in the reader's language, never in the JVM's default. */
    fun name(locale: Locale): String = when (locale.language) {
        "it" -> nameIt
        "en" -> nameEn
        else -> nameDe
    }
}
