package it.apexweather.domain

import kotlin.math.abs

/**
 * How high an amateur station really stands, and whether its own account of that is believable.
 *
 * [StationDownscale]'s cap is `2 K + 9,8 K/km × |height difference|`, so this number decides how far
 * the app may move its most-read temperature. Weather Underground's elevation is whatever the
 * station's owner typed into a web form — and that form is in **feet**, which is how ITIROL26 came
 * to be recorded at 204 m while standing at 654. Believing it would have handed that station four
 * and a half degrees of licence over the hero.
 *
 * So the claim is never the height. The ground is, from Open-Meteo's elevation endpoint, which
 * answers a whole list of coordinates in one request — `tools/generate-places.py` does the same
 * against SRTM and stores `int(round(dem))`. Open-Meteo serves Copernicus GLO-90 where the
 * generator used SRTM 30 m: two models of the same ground, metres apart, which a hundred-metre gate
 * does not notice. Measured 2026-09-23 — ITIROL16 catalogue 634 m, Open-Meteo 639; ITIROL26 claim
 * 669, Open-Meteo 659.
 */
object StationHeight {
    /** The generator's own gate, `tools/generate-places.py`'s `PWS_MAX_DEM_DISAGREEMENT_M`. */
    const val MAX_DEM_DISAGREEMENT_M = 100

    /**
     * The height to act on: the ground, or null where the ground is unknown.
     *
     * Null is not a fallback to the claim. A station whose height is only its owner's word is a
     * station this app will not read, because the one thing that height does is set how far the
     * hero may be moved.
     */
    fun heightOf(claimedM: Int?, demM: Int?): Int? = demM

    /**
     * The station's own account of its height is too far from the ground to be believed.
     *
     * Worth showing rather than acting on: the height used is the DEM's either way. What it warns
     * about is that a record out by hundreds of metres suggests the coordinates may be somewhere
     * else entirely, which no elevation lookup can correct.
     */
    fun disputed(claimedM: Int?, demM: Int?): Boolean {
        if (claimedM == null || demM == null) return false
        return abs(claimedM - demM) > MAX_DEM_DISAGREEMENT_M
    }
}
