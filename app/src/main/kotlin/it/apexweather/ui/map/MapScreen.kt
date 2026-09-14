package it.apexweather.ui.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.BuildConfig
import it.apexweather.R
import it.apexweather.data.remote.NowcastKind
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.LocalFormats
import java.io.File
import kotlin.math.abs
import org.osmdroid.config.Configuration
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Frames are the last two hours of radar and the newest is the point of them, so a tab returned
    // to later must not still show the old loop — the ViewModel fetches once when it is created and
    // would otherwise never ask again. RadarRepository keeps its own ten-minute guard, which is
    // RainViewer's publishing interval, so asking on every resume costs nothing.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        // And stop the loop on the way out. The bottom bar saves this destination's back stack
        // rather than popping it, so the ViewModel — and its animation — outlive a trip to another
        // tab: without this the frames go on turning and the tiles go on downloading behind the
        // home screen, and the reader comes back to a map that has wandered off somewhere while
        // they were not looking.
        onPauseOrDispose { viewModel.pause() }
    }
    MapContent(
        state,
        onPlayPause = { if (state.playing) viewModel.pause() else viewModel.play() },
        onSelect = viewModel::select,
        onZoom = viewModel::setZoom,
    )
}

@Composable
fun MapContent(
    state: MapUiState,
    onPlayPause: () -> Unit,
    onSelect: (Int) -> Unit,
    onZoom: (MapZoom) -> Unit = {},
    ready: Boolean = true,
) {
    val recenter = remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().testTag("map_screen")) {
        RadarMap(state, recenter.value, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // Heavy rain is drawn in yellow and red, and white text on it is unreadable. The
                // attribution is not decoration — RainViewer's terms require it — so it gets a
                // ground of its own rather than relying on whatever the radar happens to paint.
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0B1020))))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Panning away is easy and finding a village again on a map of ninety valleys is not,
            // so the way back is one tap. It sits directly above the timeline rather than in the
            // far corner: everything the reader touches on this screen is then in one place, under
            // the thumb, and none of it is over the map they are trying to look at.
            if (state.place != null) {
                FilledIconButton(
                    onClick = { recenter.value++ },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xCC121A2E), contentColor = Color.White,
                    ),
                    modifier = Modifier.align(Alignment.End).size(44.dp).testTag("map_recenter"),
                ) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.map_recenter))
                }
            }
            if (state.radarUnavailable) {
                Text(
                    stringResource(R.string.map_radar_unavailable),
                    style = MaterialTheme.typography.labelMedium, color = Color.White,
                    modifier = Modifier.testTag("map_radar_unavailable"),
                )
            } else {
                Timeline(state, ready, onPlayPause, onSelect, onZoom)
            }
            Text(
                stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.testTag("map_attribution"),
            )
        }
    }
}

/**
 * The card: play, when and what, the place's own rain, the zoom, the ribbon and the legend.
 *
 * It answers in this order: when is this, is it seen or expected, and what does it mean for the
 * reader's place. The last used to take pressing play and watching the map.
 */
@Composable
private fun Timeline(state: MapUiState, ready: Boolean, onPlayPause: () -> Unit, onSelect: (Int) -> Unit, onZoom: (MapZoom) -> Unit) {
    val formats = LocalFormats.current
    val bars = remember(state.visible, state.check, state.place) { RibbonModel.bars(state) }
    val bar = bars.getOrNull(state.selected)
    val word = bar?.let(RibbonModel::word)
    GlassCard(Modifier.fillMaxWidth().testTag("map_timeline")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Box(contentAlignment = Alignment.Center) {
                FilledIconButton(
                    onClick = onPlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White),
                    modifier = Modifier.size(40.dp).testTag("map_play_pause"),
                ) {
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (state.playing) R.string.map_pause else R.string.map_play),
                    )
                }
                if (!ready) {
                    CircularProgressIndicator(
                        color = Color.White, strokeWidth = 2.dp,
                        modifier = Modifier.size(40.dp).testTag("map_play_loading"),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                val present = state.presentTime
                val time = state.frame?.time
                // On its own line, not beside the time in a Row: at a 2x font scale the kind chip
                // and the play button already take most of the card's width, and a Row squeezed that
                // narrow wrapped "vor 20 min" onto two lines and drew it over the time it belongs to.
                Text(
                    time?.let { Format.dayTime(it, SouthTyrol.ZONE, present ?: it, formats) }.orEmpty(),
                    style = MaterialTheme.typography.titleLarge, color = Color.White,
                    modifier = Modifier.testTag("map_frame_time"),
                )
                if (time != null && present != null && time != present) {
                    val d = java.time.Duration.between(present, time)
                    Text(
                        stringResource(if (d.isNegative) R.string.map_ago else R.string.map_in, Format.shortDuration(d, formats)),
                        style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f),
                    )
                }
                if (state.place != null && word != null) {
                    val upTo = bar.takeIf { state.zoom == MapZoom.TODAY }?.upperMmPerHour?.takeIf { it >= PrecipColors.RAIN_FROM_MM }
                    Text(
                        buildString {
                            append(state.place.name(formats.locale))
                            append(" · ")
                            append(stringResource(word.labelRes()))
                            if (upTo != null) append(", ").append(stringResource(R.string.map_up_to, Format.mmValue(upTo, formats)))
                        },
                        style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.testTag("map_place_word"),
                    )
                }
            }
            FrameKindChip(state.frame, state.unconfirmedHere)
        }
        val drySince = state.radarDrySince
        if (state.unconfirmedHere && drySince != null) {
            Text(
                stringResource(R.string.map_radar_sees_nothing, Format.time(drySince, SouthTyrol.ZONE, formats)),
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.testTag("map_radar_overrule"),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZoomChip(R.string.map_zoom_now, state.zoom == MapZoom.NOW, "map_zoom_now") { onZoom(MapZoom.NOW) }
            ZoomChip(R.string.map_zoom_today, state.zoom == MapZoom.TODAY, "map_zoom_today") { onZoom(MapZoom.TODAY) }
        }
        if (bars.size > 1) {
            val nowLabel = stringResource(R.string.map_now)
            val labels = RibbonModel.labelIndices(bars, state.zoom)
                .filter { abs(it - state.nowIndex) > 1 }
                .map { it to Format.hour(bars[it].time, SouthTyrol.ZONE, formats) } + (state.nowIndex to nowLabel)
            RainRibbon(
                bars = bars, selected = state.selected, nowIndex = state.nowIndex,
                labels = labels.filter { it.first in bars.indices }.sortedBy { it.first },
                stateDescription = listOfNotNull(
                    state.frame?.time?.let { Format.dayTime(it, SouthTyrol.ZONE, state.presentTime ?: it, formats) },
                    word?.let { stringResource(it.labelRes()) },
                ).joinToString(", "),
                onSelect = onSelect,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        PrecipLegend()
    }
}

@Composable
private fun ZoomChip(label: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color(0xFF111111) else Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(tag),
    )
}

/** Seen, expected, or expected and not confirmed — said in a word rather than left to colour. */
@Composable
private fun FrameKindChip(frame: MapFrame?, unconfirmed: Boolean) {
    val label = when {
        unconfirmed -> R.string.map_kind_unconfirmed
        frame is MapFrame.Forecast && frame.step.kind == NowcastKind.OUTLOOK -> R.string.map_kind_ensemble
        frame is MapFrame.Forecast -> R.string.map_kind_nowcast
        else -> R.string.map_kind_radar
    }
    val colour = when {
        unconfirmed -> Color.White.copy(alpha = 0.7f)
        frame is MapFrame.Forecast -> RibbonColors.FORECAST
        else -> Color(0xFF9CC9FF)
    }
    Row(
        Modifier.clip(CircleShape).background(colour.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 3.dp).testTag("map_frame_kind"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(colour))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private fun RainWord.labelRes(): Int = when (this) {
    RainWord.DRY -> R.string.map_rain_dry
    RainWord.POSSIBLE -> R.string.map_rain_possible
    RainWord.LIGHT -> R.string.map_rain_light
    RainWord.MODERATE -> R.string.map_rain_moderate
    RainWord.HEAVY -> R.string.map_rain_heavy
}

/** What the colours mean, in words: the numbers are Marshall–Palmer's, and "leicht" claims less. */
@Composable
private fun PrecipLegend() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("map_legend")) {
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            drawRoundRect(brush = Brush.horizontalGradient(PrecipColors.RAMP), cornerRadius = CornerRadius(size.height / 2f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(R.string.map_legend_light, R.string.map_legend_moderate, R.string.map_legend_heavy).forEach {
                Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

/**
 * osmdroid's MapView, which is a View and therefore has a lifecycle of its own to drive. The
 * `DisposableEffect` below is the single most likely place for this screen to leak: without
 * `onPause`/`onDetach` the map keeps its tile threads running after the tab is left.
 */
@Composable
private fun RadarMap(state: MapUiState, recenter: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The frame's provider, so a pan or a zoom can fetch that frame's own zoom-7 tiles again.
    val radarProvider = remember { mutableStateOf<MapTileProviderBasic?>(null) }

    val mapView = remember {
        // A unique user agent is what the OpenStreetMap tile policy asks for first; the okhttp
        // default is named there as one that gets blocked. The cache goes in the app's own cache
        // directory so no storage permission is involved.
        Configuration.getInstance().apply {
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = File(context.cacheDir, "osmdroid-tiles")
        }
        MapView(context).apply {
            setTileSource(SouthTyrolTileSource())
            setMultiTouchControls(true)
            // osmdroid shows a pair of stock +/- buttons by default. They sat over the province in
            // a grey that belongs to no part of this app, and pinching is how anybody zooms a map
            // on a phone — the buttons were a decade-old default, not a decision.
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // The province's map is already grey and already made to be drawn over, so all this
            // does now is take it down to the app's own night. The OSM raster style needed
            // desaturating as well, because it was a green-and-beige daylight road map.
            overlayManager.tilesOverlay.setColorFilter(
                ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            0.62f, 0f, 0f, 0f, 0f,
                            0f, 0.62f, 0f, 0f, 0f,
                            0f, 0f, 0.70f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                ),
            )
            // The map is the province's map. Outside it the basemap has nothing to draw and the
            // radar is somebody else's weather, so the reader cannot wander off the edge.
            setScrollableAreaLimitDouble(
                BoundingBox(SouthTyrol.NORTH, SouthTyrol.EAST, SouthTyrol.SOUTH, SouthTyrol.WEST),
            )
            addMapListener(object : MapListener {
                // A pan or a zoom brings different ground into view, and its zoom-7 tiles have to be
                // fetched before the rain over it can be drawn.
                override fun onScroll(event: ScrollEvent?): Boolean {
                    radarProvider.value?.fetchRadarParents(this@apply)
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    radarProvider.value?.fetchRadarParents(this@apply)
                    return false
                }
            })
            minZoomLevel = MIN_ZOOM
            maxZoomLevel = MAX_ZOOM
            controller.setZoom(START_ZOOM)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Tapping the corner button brings the map back to the place, wherever the reader has wandered.
    // Keyed on the counter rather than on the place, so pressing it twice works the second time.
    LaunchedEffect(recenter, state.place) {
        if (recenter > 0) state.place?.let { mapView.controller.animateTo(GeoPoint(it.lat, it.lon)) }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.testTag("map_view"),
        update = { map ->
            state.place?.let { place ->
                val point = GeoPoint(place.lat, place.lon)
                if (map.overlays.none { it is Marker }) {
                    map.controller.setCenter(point)
                    map.overlays.add(
                        Marker(map).apply {
                            position = point
                            // A ring, centred on the place, rather than osmdroid's stock green pin
                            // with a pointing hand in it: a pin covers the ground it points at, and
                            // which valley the village sits in is half of what this map is for.
                            icon = androidx.core.content.ContextCompat.getDrawable(map.context, R.drawable.ic_map_place)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = null
                            // osmdroid pops an empty speech bubble on a tap otherwise; the place
                            // name is already on the home screen and there is nothing to say here.
                            infoWindow = null
                        },
                    )
                }
            }
            // One rain overlay at a time, of either kind: the frame changes several times a second
            // while playing, and overlays left behind would stack every frame on top of the last.
            map.overlays.filterIsInstance<TilesOverlay>().forEach { map.overlays.remove(it) }
            map.overlays.filterIsInstance<NowcastOverlay>().forEach { map.overlays.remove(it) }
            radarProvider.value = null
            when (val frame = state.frame) {
                is MapFrame.Observed -> {
                    val provider = MapTileProviderBasic(map.context, RadarTileSource(frame.radar)).apply {
                        // A standalone provider has a handler of its own, and nothing tells the map
                        // when one of its tiles arrives. Without this the radar appears only after
                        // the reader happens to pan, because a pan is what forces the redraw.
                        setTileRequestCompleteHandler(map.tileRequestCompleteHandler)
                    }
                    map.overlays.add(
                        0,
                        TilesOverlay(provider, map.context).apply {
                            loadingBackgroundColor = android.graphics.Color.TRANSPARENT
                            // Rain you can see the ground through. RainViewer's tiles are painted
                            // opaque, which hides the valley the rain is sitting in — and the valley
                            // is half the information on a map of this province. The alpha is on the
                            // colour matrix rather than on the overlay because osmdroid's
                            // TilesOverlay has no alpha of its own.
                            setColorFilter(
                                ColorMatrixColorFilter(
                                    ColorMatrix(
                                        floatArrayOf(
                                            1f, 0f, 0f, 0f, 0f,
                                            0f, 1f, 0f, 0f, 0f,
                                            0f, 0f, 1f, 0f, 0f,
                                            0f, 0f, 0f, RADAR_ALPHA, 0f,
                                        ),
                                    ),
                                ),
                            )
                        },
                    )
                    // Without this the map draws no rain at all above zoom 7; see fetchRadarParents.
                    provider.fetchRadarParents(map)
                    radarProvider.value = provider
                }
                // The forecast is this app's own drawing rather than somebody's tiles, so it is an
                // overlay of squares and needs no provider and no parent fetching.
                is MapFrame.Forecast -> map.overlays.add(0, NowcastOverlay(frame.step, NOWCAST_ALPHA))
                null -> Unit
            }
            map.invalidate()
        },
    )
}

/** The whole province on one screen. There is nothing under the map further out than this. */
private const val MIN_ZOOM = 7.0

/**
 * As deep as the province's basemap draws streets.
 *
 * It used to be 11, because the basemap was OSM's raster style and the radar is RainViewer's zoom
 * 7 upscaled — and the second of those has not changed. Rain drawn at 16 is a 20 km pixel smeared
 * across a village, and that is exactly why it is now drawn at [RADAR_ALPHA]: a wash of colour over
 * ground the reader can still read is honest about being a wash, where an opaque block of it is
 * not.
 */
private const val MAX_ZOOM = 16.0
private const val START_ZOOM = 10.0

/** How much of the radar is let through, so the valley under the rain stays visible. */
private const val RADAR_ALPHA = 0.62f

/**
 * And how much of the forecast, out of 255.
 *
 * A shade lighter than the radar on purpose. The two never appear together — the timeline shows one
 * frame at a time — but the reader moves between them with one drag, and a forecast that arrived
 * looking more solid than the observation it follows would be claiming more than it knows.
 */
private const val NOWCAST_ALPHA = 130
