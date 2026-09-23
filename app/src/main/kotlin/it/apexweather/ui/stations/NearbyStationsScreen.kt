package it.apexweather.ui.stations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.CompactLabel
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import java.time.Instant

@Composable
fun NearbyStationsScreen(viewModel: NearbyStationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NearbyStationsContent(state)
}

@Composable
fun NearbyStationsContent(state: NearbyStationsUiState, now: Instant = Instant.now()) {
    val formats = LocalFormats.current
    if (state.loading) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxWidth().testTag("stations_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "${stringResource(R.string.stations_title)} · ${state.placeName}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        state.failed?.let { failure ->
            item {
                Text(
                    if (failure == NearbyStationsViewModel.NO_KEY) stringResource(R.string.stations_no_key)
                    else failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("stations_failed"),
                )
            }
        }
        items(state.rows, key = { it.code }) { row ->
            // The provincial station is under a rule, not among them: it is not a candidate for
            // anything, it is the fallback and the source of what an amateur station does not
            // publish.
            if (row.provincial) HorizontalDivider()
            StationBlock(row, formats, now)
        }
    }
}

@Composable
private fun StationBlock(row: StationRow, formats: it.apexweather.ui.common.Formats, now: Instant) {
    Column(Modifier.fillMaxWidth().testTag("station_${row.code}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactLabel {
                Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            }
            Text(
                "${formats.oneDecimal(row.distanceKm)} km",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Both numbers where they differ: the claim is what the station says about itself and
            // the other is what the catalogue measured off a DEM. Seeing them apart is the point.
            row.claimedAltitudeM?.let { claimed ->
                val verified = row.verifiedAltitudeM
                Text(
                    if (verified != null && verified != claimed) "$claimed→$verified m" else "$claimed m",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.chosen) {
                Text(
                    "● ${stringResource(R.string.stations_chosen)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("station_chosen"),
                )
            }
            if (row.provincial) {
                Text(
                    stringResource(R.string.stations_provincial),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val reading = row.reading
        Text(
            when {
                row.error != null -> row.error
                reading == null -> stringResource(R.string.stations_quiet)
                else -> values(reading, formats, now)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
        if (row.altitudeUnverified) {
            Text(
                stringResource(R.string.stations_altitude_unverified),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("station_unverified_${row.code}"),
            )
        }
    }
}

/**
 * A dash where a station publishes nothing, never a zero: ITIROL16 has no pyranometer and ITIROL26
 * does, and that difference decides whether StationSun has anything to work with. Printing 0 W/m²
 * for an instrument that does not exist would be inventing a measurement.
 */
private fun values(
    o: it.apexweather.domain.model.StationObservation,
    formats: it.apexweather.ui.common.Formats,
    now: Instant,
): String = listOfNotNull(
    o.tempC?.let { Format.tempDecimal(it, formats) },
    o.humidityPct?.let { "$it %" },
    o.precipTodayMm?.let { Format.mmValue(it, formats) },
    o.radiationWm2?.let { "${formats.oneDecimal(it)} W/m²" } ?: "— W/m²",
    Format.timestamp(o.time, SouthTyrol.ZONE, now, formats),
).joinToString("   ")
