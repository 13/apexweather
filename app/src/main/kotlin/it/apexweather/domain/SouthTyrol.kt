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

    /**
     * The province's own corners, with a little air around them.
     *
     * The map is the province's map: the basemap has nothing to draw outside these lines, and the
     * radar beyond them is somebody else's weather. So the map is not allowed to wander off it.
     * The margin is a few kilometres, enough that a place on the border is not pinned against the
     * edge of the screen.
     */
    const val NORTH = 47.15
    const val SOUTH = 46.16
    const val WEST = 10.30
    const val EAST = 12.55
}
