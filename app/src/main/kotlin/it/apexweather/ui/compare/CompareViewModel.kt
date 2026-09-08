package it.apexweather.ui.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.AppSettings
import it.apexweather.data.CompareVariable
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DailyAggregator
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

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
    val now: Instant = Instant.EPOCH,
)

object CompareStateBuilder {
    private const val WINDOW_HOURS = 72L

    fun build(snapshot: WeatherSnapshot, settings: AppSettings, consensus: ConsensusForecast, now: Instant): CompareUiState {
        val from = now.truncatedTo(ChronoUnit.HOURS)
        val to = from.plus(WINDOW_HOURS, ChronoUnit.HOURS)
        fun HourlyPoint.value() = when (settings.compareVariable) {
            CompareVariable.TEMPERATURE -> tempC
            CompareVariable.PRECIPITATION -> precipMm
            CompareVariable.WIND -> settings.windUnit.fromKmh(windKmh)
        }
        // KMOS carries no wind at all (the mapper stores 0.0), so it must not draw a flat zero line.
        val series = snapshot.forecasts
            .filterKeys { it in settings.compareSources && !(settings.compareVariable == CompareVariable.WIND && it == Source.SIAG_KMOS) }
            .mapValues { (_, fc) -> fc.hourly.filter { !it.time.isBefore(from) && it.time.isBefore(to) }.map { SeriesPoint(it.time, it.value()) } }
            .filterValues { it.isNotEmpty() }
        val window = consensus.hourly.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
        val consensusLine = window.map { h ->
            SeriesPoint(h.time, when (settings.compareVariable) {
                CompareVariable.TEMPERATURE -> h.tempC
                CompareVariable.PRECIPITATION -> h.precipMm
                CompareVariable.WIND -> settings.windUnit.fromKmh(h.windKmh)
            })
        }
        val band = if (settings.compareVariable == CompareVariable.TEMPERATURE) window.map { BandPoint(it.time, it.tempMinC, it.tempMaxC) } else emptyList()

        val perSourceDaily = snapshot.forecasts.mapValues { (_, fc) ->
            (fc.daily.takeIf { it.isNotEmpty() } ?: DailyAggregator.aggregate(fc.hourly, DorfTirol.ZONE)).associateBy { it.date }
        }
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
            statuses = snapshot.status, settings = settings, now = now,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompareViewModel @Inject constructor(
    private val repository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
    private val blender: ConsensusBlender,
    private val clock: Clock,
) : ViewModel() {
    val state: StateFlow<CompareUiState> = settingsRepository.settings.flatMapLatest { s ->
        repository.snapshot(s.bulletinLanguage(Locale.getDefault().toLanguageTag())).map { snap ->
            CompareStateBuilder.build(snap, s, blender.blend(snap.forecasts), clock.instant())
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompareUiState())

    fun toggleSource(source: Source) = viewModelScope.launch { settingsRepository.toggleCompareSource(source) }

    fun setVariable(v: CompareVariable) = viewModelScope.launch { settingsRepository.setCompareVariable(v) }
}
