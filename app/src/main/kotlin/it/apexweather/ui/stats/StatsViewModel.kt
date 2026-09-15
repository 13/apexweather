package it.apexweather.ui.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Quantity
import it.apexweather.domain.model.Source
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    holder: WeatherStateHolder,
    repository: WeatherRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private data class Selection(val quantity: Quantity, val period: StatsPeriod, val lead: LeadBucket, val detail: Source?)

    private val place = holder.weather.map { it.place }.distinctUntilChanged { a, b -> a?.istat == b?.istat }
    private val now = holder.weather.map { it.now.truncatedTo(ChronoUnit.HOURS) }.distinctUntilChanged()
    private val windUnit = holder.weather.map { it.settings.windUnit }.distinctUntilChanged()
    private val hours = place.flatMapLatest { p -> if (p?.station == null) flowOf(emptyList()) else repository.stationHistory(p) }

    private val selection = combine(
        savedState.getStateFlow(QUANTITY_KEY, Quantity.TEMPERATURE.name),
        savedState.getStateFlow(PERIOD_KEY, StatsPeriod.MONTH.name),
        savedState.getStateFlow(LEAD_KEY, LeadBucket.SIX.name),
        savedState.getStateFlow<String?>(DETAIL_KEY, null),
    ) { q, p, l, d ->
        Selection(
            quantity = Quantity.entries.firstOrNull { it.name == q } ?: Quantity.TEMPERATURE,
            period = StatsPeriod.entries.firstOrNull { it.name == p } ?: StatsPeriod.MONTH,
            lead = LeadBucket.entries.firstOrNull { it.name == l } ?: LeadBucket.SIX,
            detail = d?.let { name -> Source.entries.firstOrNull { it.name == name } },
        )
    }

    val state: StateFlow<StatsUiState> = combine(hours, place, now, selection, windUnit) { h, p, n, s, w ->
        StatsStateBuilder.build(h, p, s.quantity, s.period, s.lead, n, s.detail, w)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setQuantity(q: Quantity) { savedState[QUANTITY_KEY] = q.name }
    fun setPeriod(p: StatsPeriod) { savedState[PERIOD_KEY] = p.name }
    fun setLead(l: LeadBucket) { savedState[LEAD_KEY] = l.name }
    fun openDetail(source: Source) { savedState[DETAIL_KEY] = source.name }
    fun closeDetail() { savedState[DETAIL_KEY] = null }

    private companion object {
        const val QUANTITY_KEY = "stats_quantity"
        const val PERIOD_KEY = "stats_period"
        const val LEAD_KEY = "stats_lead"
        const val DETAIL_KEY = "stats_detail"
    }
}
