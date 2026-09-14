package it.apexweather.domain

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.tan

/**
 * What the radar saw at one place, in reflectivity: [dbz] null means no echo at all.
 *
 * Below [RadarAtPlace.RAIN_DBZ] an echo is drawn (as a translucent beige) but is not rain: 14 dBZ is
 * under 0,3 mm/h, and on 2026-09-14 an 8 dBZ echo over Dorf Tirol reached no gauge.
 */
data class RadarReading(val dbz: Int?, val snow: Boolean = false) {
    val isRain: Boolean get() = dbz != null && dbz >= RadarAtPlace.RAIN_DBZ

    /** Marshall–Palmer, and zero for anything that is not rain. An approximation, not a gauge. */
    val mmPerHour: Double get() = if (isRain) RadarAtPlace.rateOf(dbz!!) else 0.0

    companion object {
        val NO_ECHO = RadarReading(null)
    }
}

/** A pixel of a Web Mercator tile. */
data class TilePixel(val zoom: Int, val x: Int, val y: Int, val px: Int, val py: Int)

/**
 * Reads RainViewer's tiles by the table RainViewer publishes, rather than by guessing at colours.
 *
 * Every non-transparent pixel of thirteen recorded tiles was an exact table entry, so there is no
 * nearest-colour fallback: a colour outside the table is unreadable and skipped, and a test fails if
 * any appear.
 */
object RadarAtPlace {
    const val ZOOM = 7
    const val TILE_SIZE = 256

    /** The first colour Universal Blue draws as rain rather than as a beige wash. */
    const val RAIN_DBZ = 15

    /** One pixel either side: `smooth` blurs edges, and a single pixel lands on a rim too easily. */
    private const val PATCH = 1

    // Rain first and lowest dBZ first, so a colour both blocks share reads as rain and a colour
    // repeated across dBZ reads as the least of them.
    private val lookup: Map<Int, RadarReading> = HashMap<Int, RadarReading>().apply {
        RadarColorTable.RAIN.forEachIndexed { i, argb ->
            if (argb ushr 24 != 0) putIfAbsent(argb, RadarReading(i + RadarColorTable.MIN_DBZ))
        }
        RadarColorTable.SNOW.forEachIndexed { i, argb ->
            if (argb ushr 24 != 0) putIfAbsent(argb, RadarReading(i + RadarColorTable.MIN_DBZ, snow = true))
        }
    }

    fun pixelOf(lat: Double, lon: Double): TilePixel {
        val n = 1 shl ZOOM
        val xf = (lon + 180.0) / 360.0 * n
        val yf = (1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * n
        val x = floor(xf).toInt()
        val y = floor(yf).toInt()
        return TilePixel(
            ZOOM, x, y,
            ((xf - x) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1),
            ((yf - y) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1),
        )
    }

    /** Null when the colour is not in the table. */
    fun readingOf(argb: Int): RadarReading? =
        if (argb ushr 24 == 0) RadarReading.NO_ECHO else lookup[argb]

    /** The highest reading in the 3x3 patch around ([px], [py]). */
    fun read(argb: IntArray, width: Int, px: Int, py: Int): RadarReading {
        val height = argb.size / width
        var best = RadarReading.NO_ECHO
        for (dy in -PATCH..PATCH) {
            for (dx in -PATCH..PATCH) {
                val x = px + dx
                val y = py + dy
                if (x !in 0 until width || y !in 0 until height) continue
                val r = readingOf(argb[y * width + x]) ?: continue
                if ((r.dbz ?: Int.MIN_VALUE) > (best.dbz ?: Int.MIN_VALUE)) best = r
            }
        }
        return best
    }

    fun rateOf(dbz: Int): Double = (10.0.pow(dbz / 10.0) / 200.0).pow(1.0 / 1.6)
}
