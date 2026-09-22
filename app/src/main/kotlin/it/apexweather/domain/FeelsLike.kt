package it.apexweather.domain

/**
 * Whether an apparent temperature is worth putting beside the real one, and what it is.
 *
 * "Gefühlt" only means something where something is doing the feeling. Wind chill is defined in the
 * cold and the heat index in the heat; in the mild middle an apparent temperature is arithmetic over
 * humidity and wind, and printing it there gives the reader a second number to reconcile in exchange
 * for nothing. So this is silent unless the hour is cold *and* feels colder, or hot *and* feels
 * hotter.
 *
 * The one-sidedness is deliberate on both ends. A breeze at 30 °C that takes the edge off is relief
 * rather than news, and a humid morning at 2 °C that feels a degree warmer is not a fact anybody
 * acts on.
 *
 * The thresholds are judgements rather than measurements, and should be changed as judgements:
 * 10 °C and 26 °C are where wind chill and heat index are conventionally published, and 2 K is the
 * separation [ForecastScores.TEMP_HIT_K] already treats as the edge of a temperature hit.
 *
 * There is deliberately no second guard that the two round to different degrees. A separation of
 * [MIN_DELTA_C] cannot round to the same integer, so such a rule could never fire — and a rule that
 * can never fire is one that will be quietly wrong the day a threshold moves.
 */
object FeelsLike {
    /** At or below this, wind chill is defined and a colder-feeling hour is worth saying. */
    const val COLD_MAX_C = 10.0

    /** At or above this, humidity and sun are what make an hour feel hotter than it is. */
    const val WARM_MIN_C = 26.0

    /** Any less than this and the two numbers are the same fact twice. */
    const val MIN_DELTA_C = 2.0

    /**
     * The apparent temperature to print beside [tempC], or null to say nothing.
     *
     * [tempC] is the **hero's** temperature — the air the reader is standing in, which in this app
     * is usually a station reading carried up the hill by [StationDownscale] and not the models'
     * median. [offsetC] is [it.apexweather.domain.model.ConsensusHour.feelsOffsetC], which is safe
     * to add to it because it is a per-model difference and so carries neither the population gap
     * nor the bias correction.
     */
    fun shown(tempC: Double?, offsetC: Double?): Double? {
        if (tempC == null || offsetC == null) return null
        val cold = tempC <= COLD_MAX_C && offsetC <= -MIN_DELTA_C
        val warm = tempC >= WARM_MIN_C && offsetC >= MIN_DELTA_C
        return if (cold || warm) tempC + offsetC else null
    }
}
