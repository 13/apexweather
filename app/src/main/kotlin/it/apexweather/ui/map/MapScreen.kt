package it.apexweather.ui.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.BuildConfig
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.LocalFormats
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Frames are the last two hours of radar and the newest is the point of them, so a tab returned
    // to later must not still show the old loop — the ViewModel fetches once when it is created and
    // would otherwise never ask again. RadarRepository keeps its own ten-minute guard, which is
    // RainViewer's publishing interval, so asking on every resume costs nothing.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    MapContent(
        state,
        onPlayPause = { if (state.playing) viewModel.pause() else viewModel.play() },
        onSelect = viewModel::select,
    )
}

@Composable
fun MapContent(state: MapUiState, onPlayPause: () -> Unit, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxSize().testTag("map_screen")) {
        RadarMap(state, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // Heavy rain is drawn in yellow and red, and white text on it is unreadable. The
                // attribution is not decoration — both upstreams require it — so it gets a ground
                // of its own rather than relying on whatever the radar happens to paint.
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0B1020))))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.radarUnavailable) {
                Text(
                    stringResource(R.string.map_radar_unavailable),
                    style = MaterialTheme.typography.labelMedium, color = Color.White,
                    modifier = Modifier.testTag("map_radar_unavailable"),
                )
            } else {
                Timeline(state, onPlayPause, onSelect)
            }
            // Both halves of this are required: OpenStreetMap's tile policy and RainViewer's free
            // terms each ask for their credit to be visible where the map is, not behind a toggle.
            Text(
                stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.testTag("map_attribution"),
            )
        }
    }
}

@Composable
private fun Timeline(state: MapUiState, onPlayPause: () -> Unit, onSelect: (Int) -> Unit) {
    val formats = LocalFormats.current
    GlassCard(Modifier.fillMaxWidth().testTag("map_timeline")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onPlayPause, modifier = Modifier.testTag("map_play_pause")) {
                Icon(
                    if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (state.playing) R.string.map_pause else R.string.map_play),
                    tint = Color.White,
                )
            }
            Text(
                state.frame?.time?.let { Format.time(it, SouthTyrol.ZONE, formats) }.orEmpty(),
                style = MaterialTheme.typography.labelLarge, color = Color.White,
                modifier = Modifier.testTag("map_frame_time"),
            )
        }
        if (state.frames.size > 1) {
            Slider(
                value = state.selected.toFloat(),
                onValueChange = { onSelect(it.toInt()) },
                valueRange = 0f..(state.frames.size - 1).toFloat(),
                steps = state.frames.size - 2,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = 0.8f)),
                modifier = Modifier.testTag("map_scrubber"),
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
private fun RadarMap(state: MapUiState, modifier: Modifier = Modifier) {
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
            // One radar overlay at a time: the frame changes several times a second while playing,
            // and overlays left behind would stack every frame on top of the last.
            map.overlays.filterIsInstance<TilesOverlay>().forEach { map.overlays.remove(it) }
            state.frame?.let { frame ->
                val provider = MapTileProviderBasic(map.context, RadarTileSource(frame)).apply {
                    // A standalone provider has a handler of its own, and nothing tells the map when
                    // one of its tiles arrives. Without this the radar appears only after the reader
                    // happens to pan, because a pan is what forces the redraw.
                    setTileRequestCompleteHandler(map.tileRequestCompleteHandler)
                }
                map.overlays.add(
                    0,
                    TilesOverlay(provider, map.context).apply {
                        loadingBackgroundColor = android.graphics.Color.TRANSPARENT
                        // Rain you can see the ground through. RainViewer's tiles are painted
                        // opaque, which hides the valley the rain is sitting in — and the valley is
                        // half the information on a map of this province. The alpha is on the
                        // colour matrix rather than on the overlay because osmdroid's TilesOverlay
                        // has no alpha of its own.
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
