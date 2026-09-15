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
import it.apexweather.domain.BiasCorrector
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
        // A displayName that already carries the provider ("MeteoSwiss ICON-CH1") must not repeat it
        // on the line below; the KMOS province name and the website button still use it.
        if (!state.source.displayName.startsWith(provider)) {
            Text(provider, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
        }

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
                // A Failed status already carries its full reason directly below; the line above must
                // not say the same thing twice as statusText's own truncated "Fehler: …" prefix.
                if (state.status is SourceStatus.Failed) stringResource(R.string.source_failed)
                else statusText(state.source, state.status, state.now, formats),
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
                failed.lastIssuedAt?.let { Line(stringResource(R.string.source_last_good, Format.dayTime(it, SouthTyrol.ZONE, state.now, formats))) }
            }
            state.reachUntil?.let { Line(stringResource(R.string.source_reach, Format.dayTime(it, SouthTyrol.ZONE, state.now, formats))) }
            MetaLines(meta, state, formats)
            Line(stringResource(R.string.source_stale_after, state.staleAfterHours), dim = true)
        }

        // A source that has never loaded has nothing to say about its share of the consensus or its
        // error at the station: both blocks need a status to describe.
        if (state.status != null) {
            Block(R.string.source_block_share, tag = "source_detail_share") {
                state.weight?.let { w ->
                    Line(
                        if (state.familySize > 1) pluralStringResource(R.plurals.source_weight_shared, state.familySize, formats.oneDecimal(w), state.familySize, state.source.family.name)
                        else stringResource(R.string.source_weight_alone, state.source.family.name),
                    )
                }
                Line(stringResource(if (state.inConsensus) R.string.source_in_consensus else R.string.source_not_in_consensus))
                // Only while it is actually filling a gap: a stale, failed or otherwise excluded
                // global counts nothing right now, and this sentence must not claim otherwise.
                if (state.onlyFillsGaps) {
                    Line(stringResource(R.string.source_fills_gaps), dim = true, modifier = Modifier.testTag("source_detail_fills_gaps"))
                }
            }

            when (val station = state.station) {
                StationBlock.NoStation -> Unit
                StationBlock.NeverCheckable -> Block(R.string.source_block_station, tag = "source_detail_station") {
                    Line(stringResource(R.string.compare_never_checkable), dim = true)
                }
                is StationBlock.Checked -> Block(R.string.source_block_station, tag = "source_detail_station") {
                    val windowDays = BiasCorrector.WINDOW.toDays().toInt()
                    Line(pluralStringResource(R.plurals.source_station, windowDays, station.stationName, windowDays), dim = true)
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
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = { uri.openUri(info.website) }, modifier = Modifier.fillMaxWidth().testTag("source_detail_website")) {
            Text(stringResource(R.string.source_website, provider))
        }
    }
}

@Composable
private fun MetaLines(meta: SourceMetaUi, state: SourceDetailState, formats: Formats) {
    // Meta for a source that is not the one this sheet shows is stale — the sheet switched sources
    // faster than the fetch answered — and reads as still loading rather than as this source's fact.
    val forThisSource = when (meta) {
        is SourceMetaUi.Loading -> if (meta.source == state.source) meta else SourceMetaUi.Loading(state.source)
        is SourceMetaUi.Loaded -> if (meta.source == state.source) meta else SourceMetaUi.Loading(state.source)
        is SourceMetaUi.Unavailable -> if (meta.source == state.source) meta else SourceMetaUi.Loading(state.source)
        SourceMetaUi.NotApplicable -> meta
    }
    when (forThisSource) {
        SourceMetaUi.NotApplicable -> Unit
        is SourceMetaUi.Loading -> Line(stringResource(R.string.source_run_loading), dim = true)
        is SourceMetaUi.Unavailable -> Line(stringResource(R.string.source_run_unavailable), dim = true)
        is SourceMetaUi.Loaded -> {
            // A provider's clock and the phone's are not the same clock; a run stamped a minute ahead
            // is shown as now rather than in the future. dayTime rather than timestamp: a run from
            // yesterday or before must carry a weekday, not the full date "14.09.2026" reads as.
            fun stamp(t: java.time.Instant) = Format.dayTime(minOf(t, state.now), SouthTyrol.ZONE, state.now, formats)
            val run = forThisSource.meta.runStartedAt
            val published = forThisSource.meta.publishedAt
            when {
                run != null && published != null -> Line(stringResource(R.string.source_run_published, stamp(run), stamp(published)))
                run != null -> Line(stringResource(R.string.source_run, stamp(run)))
            }
            forThisSource.meta.updateEvery?.let { Line(stringResource(R.string.source_update_every, Format.shortDuration(it, formats))) }
        }
    }
}

@Composable
private fun Block(title: Int, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(6.dp))
    Column(modifier = if (tag != null) Modifier.testTag(tag) else Modifier, content = content)
}

@Composable
private fun Line(text: String, modifier: Modifier = Modifier, dim: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = if (dim) 0.65f else 0.9f),
        modifier = modifier.padding(top = 2.dp),
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
