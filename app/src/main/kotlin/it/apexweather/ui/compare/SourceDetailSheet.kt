package it.apexweather.ui.compare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.domain.DayPart
import it.apexweather.domain.Delivery
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.SourceStatus
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.LocalFormats

/**
 * One source, in four blocks: what it is, whether it is working, how much it counts, and how wrong
 * it has been at the station.
 *
 * Everything but the run line is already in the app and shows at once. The sheet scrolls and skips
 * its half state, so it carries a cross (see CLAUDE.md on bottom sheets).
 */
@Composable
fun SourceDetailSheet(state: SourceDetailState, meta: SourceMetaUi, onClose: () -> Unit) {
    val formats = LocalFormats.current
    val uri = LocalUriHandler.current
    val info = state.info
    val provider = info.provider ?: stringResource(R.string.source_provider_province)
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp)
            .testTag("source_detail"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.source.displayName,
                style = MaterialTheme.typography.headlineMedium, color = Color.White,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onClose, modifier = Modifier.testTag("source_detail_close")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White.copy(alpha = 0.8f))
            }
        }
        Text(provider, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))

        Block(R.string.source_block_what) {
            Line(stringResource(R.string.source_family, state.source.family.name))
            Line(
                when (val km = info.gridKm) {
                    null -> stringResource(R.string.source_grid_municipality)
                    else -> stringResource(if (state.source.regional) R.string.source_grid_regional else R.string.source_grid_global, kilometres(km, formats))
                },
            )
            Line(
                stringResource(
                    when (info.delivery) {
                        Delivery.OPEN_METEO -> R.string.source_delivery_open_meteo
                        Delivery.GEOSPHERE -> R.string.source_delivery_geosphere
                        Delivery.SIAG -> R.string.source_delivery_siag
                    },
                ),
            )
            Line(stringResource(R.string.source_licence, info.licence))
        }

        Block(R.string.source_block_state) {
            Text(
                statusText(state.source, state.status, state.now, formats),
                style = MaterialTheme.typography.bodyMedium, color = statusColor(state.status),
            )
            (state.status as? SourceStatus.Failed)?.let { failed ->
                SelectionContainer {
                    Text(
                        failed.reason,
                        style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 4.dp).testTag("source_detail_error"),
                    )
                }
                failed.lastIssuedAt?.let { Line(stringResource(R.string.source_last_good, Format.timestamp(it, SouthTyrol.ZONE, state.now, formats))) }
            }
            state.reachUntil?.let { Line(stringResource(R.string.source_reach, Format.dayTime(it, SouthTyrol.ZONE, state.now, formats))) }
            MetaLines(meta, state, formats)
            Line(stringResource(R.string.source_stale_after, state.staleAfterHours), dim = true)
        }

        Block(R.string.source_block_share) {
            state.weight?.let { w ->
                Line(
                    if (state.familySize > 1) pluralStringResource(R.plurals.source_weight_shared, state.familySize, formats.oneDecimal(w), state.familySize, state.source.family.name)
                    else stringResource(R.string.source_weight_alone, state.source.family.name),
                )
            }
            Line(stringResource(if (state.inConsensus) R.string.source_in_consensus else R.string.source_not_in_consensus))
            if (!state.source.regional) Line(stringResource(R.string.source_fills_gaps), dim = true)
        }

        when (val station = state.station) {
            StationBlock.NoStation -> Unit
            StationBlock.NeverCheckable -> Block(R.string.source_block_station) {
                Line(stringResource(R.string.compare_never_checkable), dim = true)
            }
            is StationBlock.Checked -> Block(R.string.source_block_station) {
                Line(stringResource(R.string.source_station, station.stationName), dim = true)
                station.cells.forEach { cell ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(stringResource(cell.part.labelRes()), style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f))
                        Text(
                            cell.kelvin?.let { Format.kelvinDelta(it, formats) } ?: stringResource(R.string.source_bias_too_few),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (cell.kelvin == null) Color.White.copy(alpha = 0.55f) else Color.White,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = { uri.openUri(info.website) }, modifier = Modifier.fillMaxWidth().testTag("source_detail_website")) {
            Text(stringResource(R.string.source_website, provider))
        }
    }
}

@Composable
private fun MetaLines(meta: SourceMetaUi, state: SourceDetailState, formats: Formats) {
    when (meta) {
        SourceMetaUi.NotApplicable -> Unit
        SourceMetaUi.Loading -> Line(stringResource(R.string.source_run_loading), dim = true)
        SourceMetaUi.Unavailable -> Line(stringResource(R.string.source_run_unavailable), dim = true)
        is SourceMetaUi.Loaded -> {
            // A provider's clock and the phone's are not the same clock; a run stamped a minute ahead
            // is shown as now rather than in the future.
            fun stamp(t: java.time.Instant) = Format.timestamp(minOf(t, state.now), SouthTyrol.ZONE, state.now, formats)
            val run = meta.meta.runStartedAt
            val published = meta.meta.publishedAt
            when {
                run != null && published != null -> Line(stringResource(R.string.source_run_published, stamp(run), stamp(published)))
                run != null -> Line(stringResource(R.string.source_run, stamp(run)))
            }
            meta.meta.updateEvery?.let { Line(stringResource(R.string.source_update_every, Format.shortDuration(it, formats))) }
        }
    }
}

@Composable
private fun Block(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(6.dp))
    Column(content = content)
}

@Composable
private fun Line(text: String, dim: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = if (dim) 0.65f else 0.9f),
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Whole kilometres without a decimal ("2"), the rest with one ("2,5"). */
private fun kilometres(km: Double, f: Formats): String =
    if (km % 1.0 == 0.0) f.whole(km.toInt()) else f.oneDecimal(km)

private fun DayPart.labelRes(): Int = when (this) {
    DayPart.NIGHT -> R.string.source_part_night
    DayPart.MORNING -> R.string.source_part_morning
    DayPart.AFTERNOON -> R.string.source_part_afternoon
    DayPart.EVENING -> R.string.source_part_evening
}
