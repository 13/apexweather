package it.apexweather.domain

import java.time.ZoneId

/** The one and only location of this app. */
object DorfTirol {
    const val NAME = "Dorf Tirol"
    const val LAT = 46.691
    const val LON = 11.155
    const val ISTAT = "021101"
    const val STATION_CODE = "23200MS"
    const val DISTRICT_ID = 2

    /**
     * The weather station whose live reading the app quotes. It is the nearest one at 1.4 km, but it
     * stands on the valley floor in Meran at 330 m while the village is up at about 600 m, and the
     * models put the village a median 1.9 K cooler than the station across a two-day run — as much
     * as 2.7 K on a clear afternoon. Its reading is therefore never shown as the village's
     * temperature without being carried up the hill first; see StationDownscale.
     */
    const val STATION_LAT = 46.688
    const val STATION_LON = 11.1366
    const val STATION_ALTITUDE_M = 330
    val ZONE: ZoneId = ZoneId.of("Europe/Rome")
}
