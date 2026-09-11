package it.apexweather.ui.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
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
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.LocalFormats
import java.io.File
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
    )
}

@Composable
fun MapContent(state: MapUiState, onPlayPause: () -> Unit, onSelect: (Int) -> Unit) {
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
                Timeline(state, onPlayPause, onSelect)
            }
            Text(
                stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.testTag("map_attribution"),
            )
        }
    }
}

/**
 * The scrubber, the clock and the legend, in one card.
 *
 * The card has to answer three questions at a glance and in this order: what am I looking at, when
 * is it, and is it something that happened or something expected. The last of those is why the
 * label beside the time is not decoration — a forecast frame drawn exactly like a radar frame is
 * the one mistake a map like this can make that matters.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Timeline(state: MapUiState, onPlayPause: () -> Unit, onSelect: (Int) -> Unit) {
    val formats = LocalFormats.current
    GlassCard(Modifier.fillMaxWidth().testTag("map_timeline")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledIconButton(
                onClick = onPlayPause,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White,
                ),
                modifier = Modifier.size(40.dp).testTag("map_play_pause"),
            ) {
                Icon(
                    if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (state.playing) R.string.map_pause else R.string.map_play),
                )
            }
            // The clock alone is not enough now that the timeline runs a day out: at eleven in the
            // morning a forecast frame reading "10:00" would be read as an hour ago. `dayTime` puts
            // the weekday in front on any day but the present one, and the present here is the
            // newest frame a radar actually saw rather than a clock this composable would have to
            // be handed.
            val present = state.frames.getOrNull(state.nowIndex)?.time ?: state.frame?.time
            Text(
                state.frame?.time?.let { Format.dayTime(it, SouthTyrol.ZONE, present ?: it, formats) }.orEmpty(),
                style = MaterialTheme.typography.titleMedium, color = Color.White,
                modifier = Modifier.testTag("map_frame_time"),
            )
            FrameKindChip(state.showingForecast)
        }
        if (state.frames.size > 1) {
            Slider(
                value = state.selected.toFloat(),
                onValueChange = { onSelect(it.toInt()) },
                valueRange = 0f..(state.frames.size - 1).toFloat(),
                steps = state.frames.size - 2,
                track = { TimelineTrack(state) },
                colors = SliderDefaults.colors(thumbColor = Color.White),
                modifier = Modifier.testTag("map_scrubber"),
            )
        }
        PrecipLegend()
    }
}

/** Whether this frame was seen or is expected, said in a word rather than left to the colours. */
@Composable
private fun FrameKindChip(forecast: Boolean) {
    val colour = if (forecast) MaterialTheme.colorScheme.primary else Color(0xFF9CC9FF)
    Row(
        Modifier.clip(CircleShape).background(colour.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 3.dp).testTag("map_frame_kind"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(colour))
        Text(
            stringResource(if (forecast) R.string.map_kind_forecast else R.string.map_kind_radar),
            style = MaterialTheme.typography.labelSmall, color = Color.White,
        )
    }
}

/**
 * The slider's own track, drawn so the future looks like the future.
 *
 * Material's default track is one bar with a filled part and an empty part, which says how far along
 * the reader is and nothing about what they are scrubbing through. This one splits at the present:
 * the observed stretch is solid, the forecast stretch is dashed, and the join carries a tick. The
 * Slider around it keeps its own dragging and its own accessibility; only the painting changes.
 */
@Composable
private fun TimelineTrack(state: MapUiState) {
    val nowFraction = if (state.frames.size < 2) 1f
    else (state.nowIndex.coerceAtLeast(0)).toFloat() / (state.frames.size - 1).toFloat()
    val forecastColour = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(14.dp).testTag("map_timeline_track")) {
        val y = size.height / 2f
        val thickness = 4.dp.toPx()
        val split = size.width * nowFraction
        drawLine(
            Color.White.copy(alpha = 0.85f), Offset(0f, y), Offset(split, y),
            strokeWidth = thickness, cap = StrokeCap.Round,
        )
        if (nowFraction < 1f) {
            drawLine(
                forecastColour.copy(alpha = 0.75f), Offset(split, y), Offset(size.width, y),
                strokeWidth = thickness, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
            // The present, which is the one instant on this track a reader looks for.
            drawLine(
                Color.White, Offset(split, y - 6.dp.toPx()), Offset(split, y + 6.dp.toPx()),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * What the colours mean.
 *
 * Without it the map is a picture: a reader can see that something is orange over the Pustertal and
 * has no way to learn whether that is a shower or a flood. The scale is the one both layers are
 * drawn in — see [PrecipColors] — and it is labelled light to heavy rather than in millimetres,
 * because RainViewer does not publish what its own colours mean in rate and the app will not invent
 * numbers for somebody else's scale.
 */
@Composable
private fun PrecipLegend() {
    Column(Modifier.fillMaxWidth().padding(top = 2.dp).testTag("map_legend")) {
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            drawRoundRect(
                brush = Brush.horizontalGradient(PrecipColors.RAMP),
                cornerRadius = CornerRadius(size.height / 2f),
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.map_legend_light),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                stringResource(R.string.map_legend_heavy),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
            )
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
