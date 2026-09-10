package it.apexweather.domain

/**
 * What is left of the fixed location, while the place is being made choosable.
 *
 * Every constant here is a default that belongs to one municipality and is on its way out: the
 * coordinates and the ISTAT code move into [Place], the district into the bulletin request, the
 * station into [NearbyStation]. Nothing new may use it, and it is deleted once the last caller is
 * repointed. The time zone and the warning region moved to [SouthTyrol], where they belong: they
 * are true of the whole province.
 */
object DorfTirol {
    const val NAME = "Dorf Tirol"
    const val LAT = 46.691
    const val LON = 11.155
    const val ISTAT = "021101"
    const val STATION_CODE = "23200MS"
    const val DISTRICT_ID = 2
    const val STATION_LAT = 46.688
    const val STATION_LON = 11.1366
    const val STATION_ALTITUDE_M = 330
}
