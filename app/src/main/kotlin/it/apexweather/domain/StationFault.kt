package it.apexweather.domain

import kotlin.math.abs

/**
 * Whether a station reading is a measurement or a fault.
 *
 * Deliberately one rule, and a loose one. [ForecastScores] already decides a station fault this
 * way — a reading further than [ForecastScores.TEMP_FAULT_K] from the models' weighted median at
 * the station drops that hour for every model and is counted once — and reusing that number means
 * the app has one idea of "that thermometer is broken" rather than two that can drift apart.
 *
 * It exists because a place may now read an amateur station: a thermometer somebody put up
 * themselves, which can be unplugged, moved indoors, or left in the sun. The provincial network is
 * maintained and this rule will essentially never fire for it.
 *
 * **It must stay loose.** A village thermometer disagreeing with the models by several degrees is
 * not an error, it is the entire reason for reading one. Measured at Dorf Tirol on 2026-09-22,
 * ITIROL16 against the valley floor: +4,1 K at five in the morning under a nocturnal inversion, and
 * −6,6 K at eight while the village still sat in the Texelgruppe's shadow and Meran had gained ten
 * degrees in three hours. A rule tight enough to call either of those a fault would reject the
 * feature's whole value.
 *
 * For an amateur station this is the **only** guard. [StationDownscale] fades, brackets and hands
 * the screen back to the models, but it moves the provincial station alone: an amateur reading is
 * quoted as read, because it was chosen for standing at the village (see `HomeStateBuilder`).
 * A reading this rule rejects falls back to the provincial one, which still has all of that.
 */
object StationFault {
    /**
     * The models' weighted median at the station, or null where there is none — in which case there
     * is nothing to check against and the reading stands. A station with no model coverage is not
     * thereby suspect.
     */
    fun usable(readingC: Double?, modelMedianC: Double?): Boolean {
        if (readingC == null) return false
        if (modelMedianC == null) return true
        return abs(readingC - modelMedianC) <= ForecastScores.TEMP_FAULT_K
    }
}
