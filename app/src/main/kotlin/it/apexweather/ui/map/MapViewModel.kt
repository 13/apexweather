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
            _state.update { state ->
                state.copy(frames = frames, selected = state.selectionAfter(frames), loading = false)
            }
        }
    }

    fun select(index: Int) {
        pause()
        _state.update { it.copy(selected = index.coerceIn(0, (it.frames.size - 1).coerceAtLeast(0))) }
    }

    /**
     * Runs the loop, deciding each step from the state as it is *now* rather than as it was when
     * the step began.
     *
     * It used to read the selection, work out the next index, sleep, and only then write that index
     * down — so the number it wrote was decided before the sleep and applied after it, and it
     * replaced whatever the selection had become rather than following from it. A scrub is safe
     * from that, because `pause` cancels at the delay; a refresh landing mid-step is the case that
     * is not, and the list is rebuilt every ten minutes. I could not construct a deterministic
     * failure for it under virtual time, so this is written as a narrowing rather than sold as a
     * fixed bug: the step is a single `update` off the current value, it cannot leave the list's
     * bounds, and it stops itself the moment `playing` goes false.
     *
     * The bug that *was* reproducible is next door, in `refresh`: see [MapUiState.selectionAfter].
     * The other half of "it does not stop" is not here at all — it is that nothing paused this when
     * the tab was left. The bottom bar saves the map's back stack rather than popping it, so this
     * ViewModel outlives the trip; `MapScreen` now pauses on the way out.
     */
    fun play() {
        if (_state.value.frames.size < 2) return
        animation?.cancel()
        _state.update { it.copy(playing = true) }
        animation = viewModelScope.launch {
            while (true) {
                // A beat on the last frame, so the eye can find where the loop restarts.
                val atEnd = _state.value.let { it.selected >= it.frames.lastIndex }
                delay(if (atEnd) LOOP_PAUSE_MS else FRAME_MS)
                var running = true
                _state.update { s ->
                    if (!s.playing || s.frames.size < 2) {
                        running = false
                        s
                    } else {
                        s.copy(selected = if (s.selected >= s.frames.lastIndex) 0 else s.selected + 1)
                    }
                }
                if (!running) return@launch
            }
        }
    }

    fun pause() {
        animation?.cancel()
        animation = null
        _state.update { if (it.playing) it.copy(playing = false) else it }
    }

    override fun onCleared() {
        animation?.cancel()
    }

    private companion object {
        const val FRAME_MS = 400L
        const val LOOP_PAUSE_MS = 1200L
    }
}
