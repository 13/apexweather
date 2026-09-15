package it.apexweather.ui.compare

import it.apexweather.data.remote.SourceMeta
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DayPart
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Place
import it.apexweather.domain.SourceInfo
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import java.time.Instant

/** A model's measured error at the station for one part of the day; null where too few hours are in. */
data class BiasCell(val part: DayPart, val kelvin: Double?)

/** The sheet's last block, which exists only where a thermometer can check the model. */
sealed interface StationBlock {
    /** The place has no station near enough; the block is left out. */
    data object NoStation : StationBlock

    /** KMOS is addressed by municipality and can never be checked against a thermometer. */
    data object NeverCheckable : StationBlock

    data class Checked(val stationName: String, val cells: List<BiasCell>) : StationBlock
}

/** The one line fetched when the sheet opens. */
sealed interface SourceMetaUi {
    /** The provider publishes nothing to ask (KMOS). */
    data object NotApplicable : SourceMetaUi
    data object Loading : SourceMetaUi
    data class Loaded(val meta: SourceMeta) : SourceMetaUi
    data object Unavailable : SourceMetaUi
}

data class SourceDetailState(
    val source: Source,
    val info: SourceInfo,
    /** Null when the source has never been loaded. */
    val status: SourceStatus?,
    val staleAfterHours: Int,
    /** The last hour with a value in the cached forecast. Never from provider metadata. */
    val reachUntil: Instant?,
    /** The vote this source casts on a normal hour; null when it is not in the blend. */
    val weight: Double?,
    /** How many sources in the same count share its core, itself included. */
    val familySize: Int,
    val inConsensus: Boolean,
    /** A global source while two or more regional ones are in the blend: it only counts where they do not reach. */
    val onlyFillsGaps: Boolean,
    val station: StationBlock,
    val now: Instant,
)

object SourceDetailStateBuilder {

    fun build(source: Source, snapshot: WeatherSnapshot, place: Place?, now: Instant): SourceDetailState {
        val blend = snapshot.forecastsForBlend.keys
        val regionalInBlend = blend.filter { it.regional }.toSet()
        val inConsensus = source in blend
        // The blender counts a regional run among the regional runs whenever two of them report,
        // and everything else among everything present — so the weight is worked out the same way.
        val among = if (source.regional && regionalInBlend.size >= 2) regionalInBlend else blend
        val station = place?.station
        return SourceDetailState(
            source = source,
            info = SourceInfo.of(source),
            status = snapshot.status[source],
            staleAfterHours = source.staleAfterHours,
            reachUntil = snapshot.forecasts[source]?.hourly?.maxOfOrNull { it.time },
            weight = if (inConsensus) ConsensusBlender.weightOf(source, among) else null,
            familySize = among.count { it.family == source.family }.coerceAtLeast(1),
            inConsensus = inConsensus,
            onlyFillsGaps = inConsensus && !source.regional && regionalInBlend.size >= 2,
            station = when {
                station == null -> StationBlock.NoStation
                !source.checkableAtStation -> StationBlock.NeverCheckable
                else -> StationBlock.Checked(
                    stationName = station.name,
                    cells = DayPart.entries.map { part -> BiasCell(part, snapshot.modelBias.byPart[source]?.get(part)?.get(LeadBucket.SIX)) },
                )
            },
            now = now,
        )
    }
}
