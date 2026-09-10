package it.apexweather.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * The map tab's state: the radar frames, which one is showing, and the chosen place to centre on.
 *
 * The place comes from the same holder every other screen reads, so switching place in the picker
 * moves the map with everything else.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val radar: RadarRepository,
    holder: WeatherStateHolder,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var animation: Job? = null

    init {
        viewModelScope.launch {
            holder.home.collect { home -> _state.update { it.copy(place = home.place) } }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val frames = radar.frames()
            _state.update {
                // Open on the newest frame: it is the one the reader came to see, and the animation
                // that follows runs from the beginning back round to it.
                it.copy(frames = frames, selected = (frames.size - 1).coerceAtLeast(0), loading = false)
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
