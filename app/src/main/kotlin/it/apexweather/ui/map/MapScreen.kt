package it.apexweather.ui.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.BuildConfig
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.LocalFormats
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
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
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
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
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // The OSM raster style is a daylight map and this app is a night sky. Darkened and
            // desaturated it stops fighting the radar drawn over it.
            overlayManager.tilesOverlay.setColorFilter(
                ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        setSaturation(0.35f)
                        postConcat(
                            ColorMatrix(
                                floatArrayOf(
                                    0.55f, 0f, 0f, 0f, 0f,
                                    0f, 0.55f, 0f, 0f, 0f,
                                    0f, 0f, 0.62f, 0f, 0f,
                                    0f, 0f, 0f, 1f, 0f,
                                ),
                            ),
                        )
                    },
                ),
            )
            minZoomLevel = MIN_ZOOM
            // Past this the z7 radar is upscaled past the point of meaning anything.
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
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        },
                    )
                }
            }
            // One radar overlay at a time: the frame changes several times a second while playing,
            // and overlays left behind would stack every frame on top of the last.
            map.overlays.filterIsInstance<TilesOverlay>().forEach { map.overlays.remove(it) }
            state.frame?.let { frame ->
                map.overlays.add(
                    0,
                    TilesOverlay(MapTileProviderBasic(map.context, RadarTileSource(frame)), map.context)
                        .apply { loadingBackgroundColor = android.graphics.Color.TRANSPARENT },
                )
            }
            map.invalidate()
        },
    )
}

private const val MIN_ZOOM = 6.0
private const val MAX_ZOOM = 11.0
private const val START_ZOOM = 8.5
