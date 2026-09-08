package it.apexweather.domain

import it.apexweather.domain.model.Condition

/** WMO 4677 weather interpretation codes as used by Open-Meteo. */
object WmoCodes {
    fun toCondition(code: Int?): Condition = when (code) {
        0 -> Condition.CLEAR
        1 -> Condition.MOSTLY_CLEAR
        2 -> Condition.PARTLY_CLOUDY
        3 -> Condition.CLOUDY
        45, 48 -> Condition.FOG
        51, 53, 55, 56, 57 -> Condition.DRIZZLE
        61, 63, 80 -> Condition.RAIN
        65, 81, 82 -> Condition.HEAVY_RAIN
        66, 67 -> Condition.SLEET
        71, 73, 77, 85 -> Condition.SNOW
        75, 86 -> Condition.HEAVY_SNOW
        95, 96, 99 -> Condition.THUNDERSTORM
        else -> Condition.CLOUDY
    }
}
