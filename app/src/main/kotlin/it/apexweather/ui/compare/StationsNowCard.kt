package it.apexweather.ui.compare

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.LocalFormats
import java.time.Instant

/**
 * What every thermometer round the place is reading this minute, one column each.
 *
 * The comparison screen answers "what do the models say" thoroughly and cannot answer the question
 * a reader has after reading it: is any of this what it is like outside. Measured round Dorf Tirol
 * on 2026-09-23, four private stations within 1,3 km read 11,0°, 13,0°, 14,0° and 18,0° at the same
 * minute — a model spread of 1,5 K reads very differently beside that.
 *
 * The same horizontal scroll and the same column widths as the day table above it, so the two read
 * as the same kind of object.
 */
@Composable
fun StationsNowCard(
    state: StationsNowUiState,
    onOpenStations: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    if (!state.hasAnything) return
    val formats = LocalFormats.current
    GlassCard(modifier.testTag("stations_now")) {
        Text(
            stringResource(R.string.compare_stations_now),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                // The card is a way in as well as a read-out: the stations screen is where one of
                // these can actually be chosen, and two screens listing the same instruments should
                // not both be dead ends.
                .clickable(onClick = onOpenStations),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.width(LabelColumn))
                state.columns.forEach { c ->
                    Column(Modifier.width(ValueColumn)) {
                        Text(
                            c.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (c.chosen) MaterialTheme.colorScheme.primary else Color.White,
                            fontWeight = if (c.chosen) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                        Text(
                            "${formats.oneDecimal(c.distanceKm)} km",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // The units live in the row labels and the cells carry bare numbers — the same rule as
            // the per-source table above, and what Format.mmValue exists for. "390,9 W/m²" does not
            // fit an 84 dp column and was running into its neighbour.
            ValueRow(R.string.compare_stations_temp, state) { it.tempC?.let { v -> formats.oneDecimal(v) } }
            ValueRow(R.string.compare_stations_humidity, state) { c -> c.humidityPct?.let { formats.whole(it) } }
            ValueRow(R.string.compare_stations_rain, state) { it.precipTodayMm?.let { v -> Format.mmValue(v, formats) } }
            ValueRow(R.string.compare_stations_radiation, state) { c -> c.radiationWm2?.let { formats.oneDecimal(it) } }
            ValueRow(R.string.compare_stations_read, state) { c ->
                c.readAt?.let { Format.timestamp(it, SouthTyrol.ZONE, now, formats) }
            }
        }
        // One anchor, and it is the models' own number rather than the hero. The hero has been
        // through StationDownscale, StationFog, StationSun, StationDry and MeasuredRain; repeating
        // it here would be a second answer about one hour derived somewhere else, with no way to
        // tell which is right when the two disagree.
        state.modelsTempC?.let { models ->
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.18f))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.compare_stations_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Format.tempDecimal(models, formats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("stations_now_models"),
                )
            }
        }
    }
}

/**
 * One quantity across the columns.
 *
 * **A dash where a station publishes nothing, never a zero.** ITIROL16 has no pyranometer and
 * ITIROL26 does, and that difference decides whether StationSun has anything to work with; a zero
 * there would be inventing a measurement. A quiet station — HTTP 204, nothing in the last hour —
 * is dashes too, and its own row says when it was last read.
 */
@Composable
private fun ValueRow(
    labelRes: Int,
    state: StationsNowUiState,
    value: (StationColumn) -> String?,
) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(labelRes),
            Modifier.width(LabelColumn),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
        )
        state.columns.forEach { c ->
            Text(
                value(c) ?: "—",
                Modifier.width(ValueColumn).testTag("stations_now_${c.code}"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (value(c) == null) Color.White.copy(alpha = 0.45f) else Color.White,
                fontWeight = if (c.chosen) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/**
 * The value columns are the day table's own width, so the two cards read as the same kind of
 * object. The label column is wider than the table's 52 dp because these labels carry their unit —
 * at 84 dp "Regen heute mm" and "Strahlung W/m²" both wrapped to two lines.
 */
private val LabelColumn = 116.dp
private val ValueColumn = 84.dp
