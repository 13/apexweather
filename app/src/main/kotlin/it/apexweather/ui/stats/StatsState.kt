package it.apexweather.ui.stats

import it.apexweather.data.WindUnit
import it.apexweather.domain.Contender
import it.apexweather.domain.DayPart
import it.apexweather.domain.ForecastScores
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Place
import it.apexweather.domain.Quantity
import it.apexweather.domain.Score
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.VerificationHour
import it.apexweather.domain.model.Source
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

enum class StatsPeriod(val days: Long) { WEEK(7), MONTH(30), SEASON(90) }

data class StatsRow(val contender: Contender, val rank: Int?, val score: Score)

data class StatsDetail(
    val source: Source,
    val quantity: Quantity,
    val score: Score?,
    val byPart: Map<DayPart, Score>,
    val daily: List<Pair<LocalDate, Double>>,
)

data class StatsUiState(
    val loading: Boolean = true,
    val hasStation: Boolean = true,
    val stationName: String? = null,
    val quantity: Quantity = Quantity.TEMPERATURE,
    val period: StatsPeriod = StatsPeriod.MONTH,
    val lead: LeadBucket = LeadBucket.SIX,
    /** Ranked models with the reference rows placed among them by score. */
    val rows: List<StatsRow> = emptyList(),
    val unranked: List<StatsRow> = emptyList(),
    val observedHours: Int = 0,
    val firstHour: Instant? = null,
    val excludedHours: Int = 0,
    val now: Instant = Instant.EPOCH,
    val detail: StatsDetail? = null,
    val windUnit: WindUnit = WindUnit.KMH,
)

data class StatsCardState(
    /** True until the station history has been read: "no station" and "not enough data" both wait for it. */
    val loading: Boolean = true,
    val hasStation: Boolean = true,
    val top: List<StatsRow> = emptyList(),
    val enoughData: Boolean = false,
)

/** A source's place in the card's ranking (temperature, 30 days, 6 h); [rank] null while not ranked. */
data class SourceRank(val rank: Int?, val of: Int, val score: Score?)

object StatsStateBuilder {

    fun build(
        hours: List<VerificationHour>,
        place: Place?,
        quantity: Quantity,
        period: StatsPeriod,
        lead: LeadBucket,
        now: Instant,
        detailSource: Source?,
        windUnit: WindUnit = WindUnit.KMH,
    ): StatsUiState {
        val station = place?.station
        if (station == null) return StatsUiState(loading = false, hasStation = false, quantity = quantity, period = period, lead = lead, now = now, windUnit = windUnit)
        val since = now.minus(Duration.ofDays(period.days))
        val ranking = ForecastScores.rank(hours, quantity, lead, since)
        val rows = (ranking.ranked + ranking.references)
            .sortedWith(ForecastScores.compare(quantity))
            .map { StatsRow(it.contender, it.rank, it.score) }
        val detail = detailSource?.let { source ->
            StatsDetail(
                source = source,
                quantity = quantity,
                score = (ranking.ranked + ranking.unranked).firstOrNull { it.contender == Contender.Model(source) }?.score,
                byPart = ForecastScores.byPart(hours, quantity, lead, since, source, SouthTyrol.ZONE),
                daily = ForecastScores.dailyError(hours, quantity, lead, since, source, SouthTyrol.ZONE),
            )
        }
        return StatsUiState(
            loading = false, hasStation = true, stationName = station.name,
            quantity = quantity, period = period, lead = lead,
            rows = rows, unranked = ranking.unranked.map { StatsRow(it.contender, null, it.score) },
            observedHours = ranking.observedHours, firstHour = ranking.firstHour,
            excludedHours = ranking.excludedHours, now = now, detail = detail, windUnit = windUnit,
        )
    }

    fun card(hours: List<VerificationHour>, place: Place?, now: Instant): StatsCardState {
        if (place?.station == null) return StatsCardState(loading = false, hasStation = false)
        val ranking = cardRanking(hours, now)
        return StatsCardState(
            loading = false,
            hasStation = true,
            top = ranking.ranked.take(3).map { StatsRow(it.contender, it.rank, it.score) },
            enoughData = ranking.ranked.isNotEmpty(),
        )
    }

    fun sourceRanks(hours: List<VerificationHour>, now: Instant): Map<Source, SourceRank> {
        val ranking = cardRanking(hours, now)
        val of = ranking.ranked.size
        return (ranking.ranked + ranking.unranked).associate { row ->
            (row.contender as Contender.Model).source to SourceRank(row.rank, of, row.score)
        }
    }

    private fun cardRanking(hours: List<VerificationHour>, now: Instant) =
        ForecastScores.rank(hours, Quantity.TEMPERATURE, LeadBucket.SIX, now.minus(Duration.ofDays(StatsPeriod.MONTH.days)))
}
