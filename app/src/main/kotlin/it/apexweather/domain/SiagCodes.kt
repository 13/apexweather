package it.apexweather.domain

import it.apexweather.domain.model.Condition

/**
 * Landeswetterdienst Südtirol symbol letters. Icon number = position in the alphabet (a=1 … z=26).
 * Table verified against the bulletin history and the icon set on 2026-09-08.
 */
object SiagCodes {
    private const val ICON_BASE = "https://api-weather.services.siag.it/api/v2/graphics/icons/HDimgsource/wetter/icon_"

    private val table: Map<Char, Condition> = mapOf(
        'a' to Condition.CLEAR,          // Wolkenlos
        'b' to Condition.MOSTLY_CLEAR,   // Heiter
        'c' to Condition.PARTLY_CLOUDY,  // Wolkig
        'd' to Condition.CLOUDY,         // Stark bewölkt
        'e' to Condition.CLOUDY,         // Bedeckt
        'f' to Condition.RAIN,           // Wolkig, mäßiger Regen
        'g' to Condition.HEAVY_RAIN,     // Wolkig, starker Regen
        'h' to Condition.RAIN,           // Bedeckt, mäßiger Regen
        'i' to Condition.HEAVY_RAIN,     // Bedeckt, starker Regen
        'j' to Condition.DRIZZLE,        // Bedeckt, leichter Regen
        'k' to Condition.MOSTLY_CLEAR,   // Durchscheinende Bewölkung
        'l' to Condition.SNOW,           // Wolkig, leichter Schneefall
        'm' to Condition.SNOW,           // Wolkig, mäßiger Schneefall
        'n' to Condition.SNOW,           // Bedeckt, leichter Schneefall
        'o' to Condition.SNOW,           // Bedeckt, mäßiger Schneefall
        'p' to Condition.HEAVY_SNOW,     // Bedeckt, starker Schneefall
        'q' to Condition.SLEET,          // Wolkig, Schneeregen
        'r' to Condition.SLEET,          // Bedeckt, Schneeregen
        's' to Condition.FOG,            // Hochnebel / Nebel mit Sonne
        't' to Condition.FOG,            // Talnebel
        'u' to Condition.THUNDERSTORM,   // Wolkig, Gewitter mit mäßigen Schauern
        'v' to Condition.THUNDERSTORM,   // Bedeckt, Gewitter mit starken Schauern
        'w' to Condition.THUNDERSTORM,   // Wolkig, Gewitter mit Schneeregen
        'x' to Condition.THUNDERSTORM,   // Bedeckt, Gewitter mit Schneeregen
        'y' to Condition.THUNDERSTORM,   // Wolkig, Gewitter mit Schnee
        'z' to Condition.THUNDERSTORM,   // Bedeckt, Gewitter mit Schnee
    )

    /** Accepts "g", "g_d", "g_n" (KMOS appends a day/night suffix). */
    fun toCondition(letter: String?): Condition {
        val c = letter?.trim()?.lowercase()?.firstOrNull() ?: return Condition.CLOUDY
        return table[c] ?: Condition.CLOUDY
    }

    fun iconUrl(letter: String): String {
        val c = letter.trim().lowercase().first()
        return "$ICON_BASE${c - 'a' + 1}.png"
    }
}
