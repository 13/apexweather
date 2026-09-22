package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import java.time.Instant
import java.time.ZoneId

val ROME: ZoneId = ZoneId.of("Europe/Rome")
val T0: Instant = Instant.parse("2026-09-08T00:00:00Z")

fun hour(i: Int): Instant = T0.plusSeconds(i * 3600L)

fun point(
    i: Int,
    temp: Double,
    precip: Double = 0.0,
    prob: Int? = null,
    wind: Double? = 5.0,
    gust: Double? = null,
    freezing: Double? = null,
    snowCm: Double? = null,
    condition: Condition = Condition.CLEAR,
) = HourlyPoint(
    time = hour(i), tempC = temp, precipMm = precip, precipProb = prob,
    windKmh = wind, gustKmh = gust, freezingLevelM = freezing, snowCm = snowCm, condition = condition,
)

fun forecast(source: Source, hourly: List<HourlyPoint>) = SourceForecast(
    source = source, issuedAt = T0, fetchedAt = T0, hourly = hourly, daily = emptyList(),
)

/** Dorf Tirol, as the generated catalogue has it. */
val DORF_TIROL = Place(
    istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
    lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
    station = NearbyStation("23200MS", "Meran", 46.688, 11.1366, 330, 1.53),
)

/**
 * Dorf Tirol as it looks once the generator has found it an amateur station.
 *
 * ITIROL16 is real: 0,49 km from the village and, per the DEM under its coordinates, within a few
 * tens of metres of its height — against the provincial thermometer in Meran, 1,53 km away and
 * 264 m below. It publishes no radiation at all, which is why the provincial reading is still
 * fetched and still feeds StationSun.
 */
val DORF_TIROL_WITH_PWS = DORF_TIROL.copy(
    pws = NearbyStation("ITIROL16", "Tirolo - Tirol", 46.693246, 11.155237, 634, 0.49, network = "wu"),
)

/** Sterzing: another district, another valley, and — as constructed here — no station at all. */
val STERZING = Place(
    istat = "021115", nameDe = "Sterzing", nameIt = "Vipiteno", nameEn = "Vipiteno",
    lat = 46.8967, lon = 11.4333, altitudeM = 948, district = 5, station = null,
)
