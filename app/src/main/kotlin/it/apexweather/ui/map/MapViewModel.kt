package it.apexweather.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.NowcastRepository
import it.apexweather.data.RadarRepository
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The map tab's state: the timeline, which frame is showing, and the chosen place to centre on.
 *
 * The timeline is the radar's last two hours followed by the next two and a half from GeoSphere's
 * nowcast — where the rain has been, and where it is going. The place comes from the same holder
 * every other screen reads, so switching place in the picker moves the map with everything else,
 * and asks for the forecast around the new one.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val radar: RadarRepository,
    private val nowcast: NowcastRepository,
    holder: WeatherStateHolder,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var animation: Job? = null

    init {
        viewModelScope.launch {
            holder.home.collect { home ->
                val changed = _state.value.place?.istat != home.place?.istat
                _state.update { it.copy(place = home.place) }
                // The forecast covers a box around the place, so a new place needs a new one.
                if (changed && home.place != null) refresh()
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val past = radar.frames()
            // The forecast is only asked for once a place is known, because the box it covers is
            // drawn around the place. A failure here leaves the radar loop intact: the map was worth
            // looking at without a forecast until now, and still is.
            val ahead = _state.value.place?.let { nowcast.forPlace(it).steps }.orEmpty()
            val frames = MapUiState.timeline(past, ahead)
            _state.update {
                // Open on the newest frame a radar actually saw — the present, with the past behind
                // it and the forecast ahead. Opening on the last forecast step would lead with the
                // least certain thing on the timeline.
                val now = frames.indexOfLast { f -> f is MapFrame.Observed }
                it.copy(
                    frames = frames,
                    selected = if (now >= 0) now else (frames.size - 1).coerceAtLeast(0),
                    loading = false,
                )
            }
        }
    }

    fun select(index: Int) {
        pause()
        _state.update { it.copy(selected = index.coerceIn(0, (it.frames.size - 1).coerceAtLeast(0))) }
    }

    fun play() {
        if (_state.value.frames.size < 2) return
        animation?.cancel()
        _state.update { it.copy(playing = true) }
        animation = viewModelScope.launch {
            while (true) {
                val s = _state.value
                val last = s.frames.lastIndex
                val next = if (s.selected >= last) 0 else s.selected + 1
                // A beat on the newest frame, so the eye can find where the loop restarts.
                delay(if (s.selected >= last) LOOP_PAUSE_MS else FRAME_MS)
                _state.update { it.copy(selected = next) }
            }
        }
    }

    fun pause() {
        animation?.cancel()
        animation = null
        _state.update { it.copy(playing = false) }
    }

    override fun onCleared() {
        animation?.cancel()
    }

    private companion object {
        const val FRAME_MS = 400L
        const val LOOP_PAUSE_MS = 1200L
    }
}
