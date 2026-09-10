package it.apexweather.domain

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Distance in kilometres, flat-earth.
 *
 * Over the tens of kilometres this province spans, the error against a great-circle formula is
 * centimetres — and the inputs, a village centroid and a weather station, are nowhere near that
 * precise to begin with.
 */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dx = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2)) * 111.32
    val dy = (lat2 - lat1) * 110.57
    return hypot(dx, dy)
}
