package it.apexweather.ui.stations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.CompactLabel
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.LocalFormats
import java.time.Instant

@Composable
fun NearbyStationsScreen(onBack: () -> Unit, viewModel: NearbyStationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NearbyStationsContent(state, onBack = onBack, onChoose = viewModel::choose)
}

/**
 * Every instrument round the place, with what each is reading and which one the app uses.
 *
 * Two questions at once, and they belong together: "what do the neighbours say" is how a reader
 * judges whether the one being read is any good, and this is the only screen that can answer both.
 */
@Composable
fun NearbyStationsContent(
    state: NearbyStationsUiState,
    onBack: () -> Unit,
    onChoose: (StationRow) -> Unit,
    now: Instant = Instant.now(),
) {
    val formats = LocalFormats.current
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("nearby_stations")) {
        // The same shape as PlacePickerContent's header, for the same reason: this is a screen with
        // a back-stack entry and it needs a visible way out, not only the system gesture.
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("stations_back")) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                )
            }
            Column {
                Text(
                    stringResource(R.string.stations_title),
                    style = MaterialTheme.typography.titleMedium, color = Color.White,
                )
                if (state.placeName.isNotBlank()) {
                    Text(
                        state.placeName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
        if (state.loading) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Column
        }
        val private = state.rows.filterNot { it.provincial }
        val official = state.rows.filter { it.provincial }
        LazyColumn(
            Modifier.fillMaxWidth().testTag("stations_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
            if (state.heightsUnknown) {
                item {
                    Text(
                        stringResource(R.string.stations_heights_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("stations_heights_unknown"),
                    )
                }
            }
            if (private.isNotEmpty()) {
                item { SectionHeading(stringResource(R.string.stations_private_heading)) }
                items(private, key = { it.code }) { StationCard(it, formats, now, onChoose) }
            }
            if (official.isNotEmpty()) {
                item { SectionHeading(stringResource(R.string.stations_official_heading)) }
                items(official, key = { it.code }) { StationCard(it, formats, now, onChoose) }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun StationCard(
    row: StationRow,
    formats: Formats,
    now: Instant,
    onChoose: (StationRow) -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth().testTag("station_${row.code}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CompactLabel {
                Text(
                    row.reading?.stationName ?: row.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = if (row.chosen) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            when {
                row.chosen -> Text(
                    "● ${stringResource(R.string.stations_in_use)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("station_chosen_${row.code}"),
                )
                row.selectable -> TextButton(
                    onClick = { onChoose(row) },
                    modifier = Modifier.testTag("station_choose_${row.code}"),
                ) { Text(stringResource(R.string.stations_choose)) }
            }
        }
        // Two of the stations round Dorf Tirol are both called "Tirol"; the name alone cannot tell
        // them apart.
        Text(
            stringResource(R.string.stations_code, row.code),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${formats.oneDecimal(row.distanceKm)} km · ${heightText(row, formats)}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(4.dp))
        Quantities(row, formats, now)
        if (row.heightDisputed) {
            Text(
                stringResource(R.string.stations_height_disputed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("station_disputed_${row.code}"),
            )
        }
        // A chosen amateur station has no skyline, because a skyline is tens of thousands of DEM
        // samples and a generator job. Said here rather than left to be discovered on a December
        // afternoon when the sun goes behind a ridge the app does not know about.
        if (row.chosen && !row.provincial) {
            Text(
                stringResource(R.string.stations_no_horizon),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.testTag("station_no_horizon_${row.code}"),
            )
        }
    }
}

/**
 * Both numbers where they differ, because seeing them apart is how a reader understands the mark.
 *
 * `Format.metres` rounds to the nearest 50 m, which is right for a freezing level and wrong here:
 * the whole point of this line is 634 against 639, and a 50 m grid would print them the same.
 */
@Composable
private fun heightText(row: StationRow, formats: Formats): String {
    // The height the app would act on, and the one the station claims, in that order of authority.
    val ground = row.demAltitudeM ?: row.claimedAltitudeM ?: return "—"
    val shown = stringResource(R.string.unit_metres, formats.whole(ground))
    val claimed = row.claimedAltitudeM
    return if (row.demAltitudeM != null && claimed != null && claimed != row.demAltitudeM) {
        stringResource(R.string.stations_height_both, formats.whole(claimed), shown)
    } else {
        shown
    }
}

/**
 * A dash where a station publishes nothing, never a zero: ITIROL16 has no pyranometer and ITIROL26
 * does, and that difference decides whether StationSun has anything to work with. Printing 0 W/m²
 * for an instrument that does not exist would be inventing a measurement.
 *
 * Laid out as separate labels in a [FlowRow] rather than joined into one string, so they wrap at a
 * large font scale instead of running off the card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Quantities(row: StationRow, formats: Formats, now: Instant) {
    val reading = row.reading
    if (row.error != null) {
        Text(row.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        return
    }
    if (reading == null) {
        Text(
            stringResource(R.string.stations_quiet),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        return
    }
    val values = listOfNotNull(
        reading.tempC?.let { Format.tempDecimal(it, formats) },
        reading.humidityPct?.let { stringResource(R.string.unit_percent, it) },
        reading.precipTodayMm?.let { Format.mmValue(it, formats) },
        reading.radiationWm2?.let { "${formats.oneDecimal(it)} W/m²" } ?: "— W/m²",
        Format.timestamp(reading.time, SouthTyrol.ZONE, now, formats),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        values.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}
