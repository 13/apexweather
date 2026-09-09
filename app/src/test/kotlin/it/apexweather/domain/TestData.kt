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
    condition: Condition = Condition.CLEAR,
) = HourlyPoint(
    time = hour(i), tempC = temp, precipMm = precip, precipProb = prob,
    windKmh = wind, gustKmh = gust, condition = condition,
)

fun forecast(source: Source, hourly: List<HourlyPoint>) = SourceForecast(
    source = source, issuedAt = T0, fetchedAt = T0, hourly = hourly, daily = emptyList(),
)
