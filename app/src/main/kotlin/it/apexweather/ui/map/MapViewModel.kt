package it.apexweather.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.NowcastRepository
import it.apexweather.data.RadarRepository
import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.Place
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit
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
class MapViewModel internal constructor(
    private val radar: RadarRepository,
    private val nowcast: NowcastRepository,
    holder: WeatherStateHolder,
    /** Where the ribbon's bars are worked out: off the main thread, and a test's own dispatcher in tests. */
    private val compute: CoroutineDispatcher,
) : ViewModel() {

    @Inject constructor(radar: RadarRepository, nowcast: NowcastRepository, holder: WeatherStateHolder) :
        this(radar, nowcast, holder, Dispatchers.Default)

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var animation: Job? = null

    /**
     * The refresh in flight. Init, the place collector and every resume all ask for one, and they
     * used to run side by side: a slow one's second phase could land after a newer one's first and
     * put an older timeline back. A new refresh cancels the one before it — unless that one is still
     * fetching for the same place, see [fetching].
     */
    private var refreshJob: Job? = null

    /**
     * The refresh still waiting on the radar list or the forecast, and the place it asked for.
     *
     * A first open asks three times within half a second, and cancelling threw away an INCA request
     * already in flight — 383 kB, 4,4 s on the phone — to start it over. A refresh for the same place
     * joins that one instead; once the forecast is on screen, a newer refresh cancels it as before.
     */
    private var fetching: Job? = null
    private var fetchingFor: String? = null

    /**
     * The last forecast put on screen, so the radar can go up beside it at once. It covers the
     * province, so it serves whichever place is chosen next.
     */
    private var shown: ShownForecast? = null

    private data class ShownForecast(val steps: List<NowcastStep>, val outlook: List<NowcastStep>)

    /** Every radar frame's tiles, kept here so a return to the tab does not download the loop again. */
    val radarTiles = RadarTileStore()

    init {
        viewModelScope.launch {
            holder.home.collect { home ->
                val changed = _state.value.place?.istat != home.place?.istat
                _state.update {
                    val next = it.copy(place = home.place, animations = home.settings.animations)
                    // The bars are the place's own rain: until the new place's refresh lands they
                    // would describe the place just left, so the ribbon goes empty instead.
                    if (changed) next.copy(nowBars = emptyList(), todayBars = emptyList()) else next
                }
                // The forecast covers the province, but the ribbon and the check are the place's own.
                if (changed && home.place != null) refresh()
            }
        }
        refresh()
    }

    fun refresh() {
        val asked = _state.value.place?.istat
        if (fetching?.isActive == true && fetchingFor == asked) return
        refreshJob?.cancel()
        // Lazy, so [fetching] names this job before its body runs: viewModelScope is Main.immediate.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val past = radar.frames()
            val place = _state.value.place
            // A held check from the same place is reused — its readings are keyed by frame time, so
            // they are still good — rather than left blank until the last phase below replaces it.
            val heldCheck = _state.value.check?.takeIf { place != null && it.lat == place.lat && it.lon == place.lon }
            // The radar goes on screen the moment its list is in, beside whatever forecast is already
            // held. It used to wait for both forecasts, and INCA alone took 4,4 s on the phone:
            // six seconds of bare basemap on a first open (measured 2026-09-14).
            val kept = shown
            publish(past, kept?.steps.orEmpty(), kept?.outlook.orEmpty(), heldCheck, place, doneLoading = past.isNotEmpty())
            // The forecast is only asked for once a place is known, because the timeline is read at
            // the place. A failure here leaves the radar loop intact: the map was worth looking at
            // without a forecast until now, and still is.
            // Each half goes on screen as it lands: INCA has taken 17 s where AROME took half a second.
            val held = place?.let {
                nowcast.current { partial -> publish(past, partial.steps, partial.outlook, heldCheck, place, doneLoading = true) }
            }
            val ahead = held?.steps.orEmpty()
            val outlook = held?.outlook.orEmpty()
            shown = place?.let { ShownForecast(ahead, outlook) }
            publish(past, ahead, outlook, heldCheck, place, doneLoading = true)
            if (fetching == coroutineContext.job) fetching = null
            if (place != null) {
                // The check against the place only annotates what is already on screen, and must
                // not hold that back.
                val readings = radar.readingsAt(place.lat, place.lon)
                val check = PlaceCheck(place.lat, place.lon, readings)
                // The reader may have switched place while the tiles were still in flight; a check for
                // the place they left must never land on the one they are looking at now.
                if (_state.value.place?.istat == place.istat) publish(past, ahead, outlook, check, place, doneLoading = true)
            }
        }
        refreshJob = job
        fetching = job
        fetchingFor = asked
        job.start()
    }

    /** Builds the timeline for both zooms and the ribbon's bars off Main, and puts them on screen. */
    private suspend fun publish(
        past: List<RadarFrame>,
        ahead: List<NowcastStep>,
        outlookSteps: List<NowcastStep>,
        check: PlaceCheck?,
        place: Place?,
        doneLoading: Boolean,
    ) {
        val frames = MapUiState.timeline(past, ahead, check)
        val present = past.lastOrNull()?.time ?: ahead.firstOrNull()?.time
        val lastSeen = past.maxOfOrNull { it.time }
        val hours = outlookSteps
            .filter { step -> present == null || (!step.time.isBefore(present.truncatedTo(ChronoUnit.HOURS)) && !step.time.isAfter(present.plus(MapUiState.TODAY_AHEAD))) }
        // Heute gets the same radar check as Jetzt, or the two zooms disagree about one hour.
        val outlook = MapUiState.markUnconfirmed(hours, lastSeen, check).map(MapFrame::Forecast)
        val bars = withContext(compute) { MapUiState(frames = frames, outlook = outlook, check = check, place = place).withBars() }
        _state.update { state ->
            val next = state.copy(
                frames = frames, outlook = outlook, check = check, nowBars = bars.nowBars, todayBars = bars.todayBars,
                loading = state.loading && !doneLoading,
            )
            next.copy(selected = state.selectionAfter(next.visible))
        }
    }

    fun select(index: Int) {
        pause()
        _state.update { it.copy(selected = index.coerceIn(0, (it.visible.size - 1).coerceAtLeast(0))) }
    }

    fun setZoom(zoom: MapZoom) {
        pause()
        _state.update { it.withZoom(zoom) }
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
        if (_state.value.visible.size < 2) return
        animation?.cancel()
        _state.update { it.copy(playing = true) }
        animation = viewModelScope.launch {
            while (true) {
                // A beat on the last frame and on "jetzt", so the eye can find where the loop
                // restarts and where the past gives way to the forecast.
                val s0 = _state.value
                val hold = s0.selected >= s0.visible.lastIndex || s0.selected == s0.nowIndex
                delay(if (hold) LOOP_PAUSE_MS else if (s0.zoom == MapZoom.NOW) NOW_FRAME_MS else TODAY_FRAME_MS)
                var running = true
                _state.update { s ->
                    if (!s.playing || s.visible.size < 2) {
                        running = false
                        s
                    } else {
                        s.copy(selected = if (s.selected >= s.visible.lastIndex) 0 else s.selected + 1)
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
        radarTiles.release()
    }

    private companion object {
        const val NOW_FRAME_MS = 350L
        const val TODAY_FRAME_MS = 450L
        const val LOOP_PAUSE_MS = 1200L
    }
}
