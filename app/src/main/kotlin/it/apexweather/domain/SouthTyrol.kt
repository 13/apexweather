package it.apexweather.domain

import java.time.ZoneId

/** What is true of the whole province, whichever place inside it the reader has chosen. */
object SouthTyrol {
    val ZONE: ZoneId = ZoneId.of("Europe/Rome")

    /**
     * MeteoAlarm's EMMA region. Italy publishes civil-protection warnings per region, and the
     * smallest area covering any place in this app is the whole of Trentino-Alto Adige.
     */
    const val WARNING_REGION = "IT002"

    /** Dorf Tirol: what the app showed before a place could be chosen, so an update opens on it. */
    const val DEFAULT_ISTAT = "021101"
}
