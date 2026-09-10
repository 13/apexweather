package it.apexweather.ui.compare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.AppSettings
import it.apexweather.data.CompareVariable
import it.apexweather.data.SettingsRepository
import it.apexweather.domain.DailyAggregator
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Which slice of time the chart plots. Days are held as an offset from today rather than as a
 * date: an offset cannot go stale, so a selection restored the next morning still means "today"
 * instead of pinning the chart to a day that has passed.
 */
sealed interface DaySelection {
    /** The three-day sweep from the current hour, which the screen has always opened on. */
    data object Sweep : DaySelection

    /** One whole local calendar day, [offset] days from today. */
    data class Day(val offset: Int) : DaySelection
}

/** The window the chart plots, derived once so the builder and the axis cannot disagree. */
data class CompareWindow(val from: Instant, val hours: Long, val selection: DaySelection)

data class SeriesPoint(val time: Instant, val value: Double)
data class BandPoint(val time: Instant, val min: Double, val max: Double)
data class DayCell(val minC: Double, val maxC: Double, val precipMm: Double)
data class DayRow(val date: LocalDate, val consensus: DayCell, val cells: Map<Source, DayCell>)

data class CompareUiState(
    val loading: Boolean = true,
    val variable: CompareVariable = CompareVariable.TEMPERATURE,
    val selected: Set<Source> = Source.entries.toSet(),
    val series: Map<Source, List<SeriesPoint>> = emptyMap(),
    val consensusLine: List<SeriesPoint> = emptyList(),
    val band: List<BandPoint> = emptyList(),
    val dayRows: List<DayRow> = emptyList(),
    val statuses: Map<Source, SourceStatus> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val window: CompareWindow = CompareWindow(Instant.EPOCH, 72, DaySelection.Sweep),
    /**
     * Truncated to the hour. Nothing here needs the minute: the only reader is Format.timestamp,
     * which uses it to decide whether a timestamp is from today. Carrying the full instant made
     * the state differ every minute, which defeated the distinctUntilChanged below it.
     */
    val now: Instant = Instant.EPOCH,
)

object CompareStateBuilder {
    private const val SWEEP_HOURS = 72L

    /**
     * A day runs local midnight to local midnight, so two days are comparable and the axis does not
     * creep along by the minute. Its length comes from the zone rather than a constant 24: Europe/Rome
     * has a 25-hour day in October and a 23-hour one in March, and a constant would push an hour of
     * data off the axis twice a year.
     */
    fun window(selection: DaySelection, now: Instant, zone: ZoneId): CompareWindow = when (selection) {
        DaySelection.Sweep -> CompareWindow(now.truncatedTo(ChronoUnit.HOURS), SWEEP_HOURS, selection)
        is DaySelection.Day -> {
            val start = now.atZone(zone).toLocalDate().plusDays(selection.offset.toLong()).atStartOfDay(zone)
            CompareWindow(start.toInstant(), Duration.between(start, start.plusDays(1)).toHours(), selection)
        }
    }

    fun build(
        snapshot: WeatherSnapshot,
        settings: AppSettings,
        consensus: ConsensusForecast,
        now: Instant,
        selection: DaySelection = DaySelection.Sweep,
    ): CompareUiState {
        val window = window(selection, now, SouthTyrol.ZONE)
        val from = window.from
        val to = from.plus(window.hours, ChronoUnit.HOURS)
        fun HourlyPoint.value(): Double? = when (settings.compareVariable) {
            CompareVariable.TEMPERATURE -> tempC
            CompareVariable.PRECIPITATION -> precipMm
            CompareVariable.WIND -> windKmh?.let(settings.windUnit::fromKmh)
        }
        // A model that publishes no wind contributes no points, so KMOS drops out of the wind
        // chart on its own rather than drawing a flat zero line.
        val series = snapshot.forecasts
            .filterKeys { it in settings.compareSources }
            .mapValues { (_, fc) ->
                fc.hourly.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
                    .mapNotNull { p -> p.value()?.let { SeriesPoint(p.time, it) } }
            }
            .filterValues { it.isNotEmpty() }
        val hoursInWindow = consensus.hourly.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
        val consensusLine = hoursInWindow.mapNotNull { h ->
            val v = when (settings.compareVariable) {
                CompareVariable.TEMPERATURE -> h.tempC
                CompareVariable.PRECIPITATION -> h.precipMm
                CompareVariable.WIND -> h.windKmh?.let(settings.windUnit::fromKmh)
            }
            v?.let { SeriesPoint(h.time, it) }
        }
        val band = if (settings.compareVariable == CompareVariable.TEMPERATURE) hoursInWindow.map { BandPoint(it.time, it.tempMinC, it.tempMaxC) } else emptyList()

        val perSourceDaily = DailyAggregator.perSource(snapshot.forecasts, SouthTyrol.ZONE)
        val dayRows = consensus.daily.map { d ->
            DayRow(
                date = d.date,
                consensus = DayCell(d.minC, d.maxC, d.precipMm),
                cells = perSourceDaily.mapNotNull { (src, byDate) -> byDate[d.date]?.let { src to DayCell(it.minC, it.maxC, it.precipMm) } }.toMap(),
            )
        }
        return CompareUiState(
            loading = false, variable = settings.compareVariable, selected = settings.compareSources,
            series = series, consensusLine = consensusLine, band = band, dayRows = dayRows,
            statuses = snapshot.status, settings = settings,
            window = window, now = now.truncatedTo(ChronoUnit.HOURS),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompareViewModel @Inject constructor(
    holder: WeatherStateHolder,
    private val settingsRepository: SettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    /**
     * Which day the chart shows. This is view state rather than a preference, so it lives here and
     * not in the settings DataStore: the two settings that are persisted answer "how do I like to
     * read this", while the day answers "what am I looking at right now". SavedStateHandle still
     * carries it across process death.
     */
    private val selection: Flow<DaySelection> =
        savedState.getStateFlow(DAY_KEY, SWEEP_OFFSET).map(::selectionOf)

    val state: StateFlow<CompareUiState> = combine(holder.weather, selection) { weather, selected ->
        CompareStateBuilder.build(weather.snapshot, weather.settings, weather.consensus, weather.now, selected)
    }
        // The state carries `now` truncated to the hour, so the minute tick produces an identical
        // state 59 minutes out of 60; without this the whole compare state rebuilt every minute.
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompareUiState())

    fun toggleSource(source: Source) = viewModelScope.launch { settingsRepository.toggleCompareSource(source) }

    fun setVariable(v: CompareVariable) = viewModelScope.launch { settingsRepository.setCompareVariable(v) }

    fun setDay(selected: DaySelection) {
        savedState[DAY_KEY] = when (selected) {
            DaySelection.Sweep -> SWEEP_OFFSET
            is DaySelection.Day -> selected.offset
        }
    }

    private companion object {
        const val DAY_KEY = "compare_day"

        /**
         * SavedStateHandle holds only what a Bundle can hold, so the selection travels as an Int
         * with a sentinel for the sweep rather than as the sealed type itself.
         */
        const val SWEEP_OFFSET = -1

        fun selectionOf(offset: Int): DaySelection =
            if (offset < 0) DaySelection.Sweep else DaySelection.Day(offset)
    }
}
