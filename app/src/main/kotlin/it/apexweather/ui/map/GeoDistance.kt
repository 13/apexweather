package it.apexweather.ui.map

import kotlin.math.cos
import kotlin.math.sqrt

private const val KM_PER_DEGREE = 111.2

/**
 * Equirectangular approximation: good to metres over the few kilometres the map ever compares
 * (a radius check around the place, or a forecast cell's distance from it).
 */
internal fun distanceKm(lat: Double, lon: Double, lat0: Double, lon0: Double): Double {
    val dy = (lat - lat0) * KM_PER_DEGREE
    val dx = (lon - lon0) * KM_PER_DEGREE * cos(Math.toRadians(lat0))
    return sqrt(dx * dx + dy * dy)
}
