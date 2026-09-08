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
    val ZONE: ZoneId = ZoneId.of("Europe/Rome")
}
