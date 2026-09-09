package it.apexweather.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.R
import it.apexweather.data.CompareVariable
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.SourceColors
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CompareScreen(viewModel: CompareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CompareContent(state, viewModel::toggleSource, viewModel::setVariable)
}

@Composable
fun CompareContent(state: CompareUiState, onToggleSource: (Source) -> Unit, onVariable: (CompareVariable) -> Unit) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val locale = LocalConfiguration.current.locales[0]
    val formats = LocalFormats.current
    LazyColumn(Modifier.fillMaxSize().testTag("compare_list"), contentPadding = PaddingValues(top = topInset + 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp))
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                CompareVariable.entries.forEachIndexed { i, v ->
                    SegmentedButton(
                        selected = state.variable == v, onClick = { onVariable(v) },
                        shape = SegmentedButtonDefaults.itemShape(i, CompareVariable.entries.size),
                        modifier = Modifier.testTag("variable_${v.name}"),
                    ) { Text(variableLabel(v)) }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                MultiLineChart(
                    series = state.series, consensus = state.consensusLine, band = state.band,
                    from = state.now.truncatedTo(ChronoUnit.HOURS), hours = 72,
                    unitLabel = when (state.variable) {
                        CompareVariable.TEMPERATURE -> "°"
                        CompareVariable.PRECIPITATION -> " mm"
                        CompareVariable.WIND -> Format.windUnitLabel(state.settings.windUnit)
                    },
                    nonNegative = state.variable != CompareVariable.TEMPERATURE,
                    variableName = variableLabel(state.variable),
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Source.entries.forEach { s ->
                        val on = s in state.selected
                        FilterChip(
                            selected = on, onClick = { onToggleSource(s) },
                            label = { Text(s.displayName) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(SourceColors.of(s))) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SourceColors.of(s).copy(alpha = 0.25f), labelColor = Color.White, selectedLabelColor = Color.White),
                            modifier = Modifier.testTag("chip_${s.name}"),
                        )
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("day_table")) {
                Text(stringResource(R.string.compare_table), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))
                val scroll = rememberScrollState()
                Column(Modifier.horizontalScroll(scroll)) {
                    Row {
                        Text("", Modifier.width(52.dp))
                        Text(stringResource(R.string.consensus), Modifier.width(84.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                        state.selected.sortedBy { it.ordinal }.forEach { s -> Text(s.displayName.substringAfter(' ').take(9), Modifier.width(84.dp), style = MaterialTheme.typography.labelSmall, color = SourceColors.of(s)) }
                    }
                    state.dayRows.forEach { row ->
                        // IntrinsicSize.Max so a missing source's one-line placeholder does not leave
                        // its neighbours' tinted backgrounds standing taller than it.
                        Row(Modifier.padding(vertical = 6.dp).height(IntrinsicSize.Max), verticalAlignment = Alignment.CenterVertically) {
                            Text(Format.weekday(row.date, formats), Modifier.width(52.dp), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            DayCellText(row.consensus, deviation = 0.0, bold = true, sourceName = stringResource(R.string.consensus), date = Format.weekday(row.date, formats))
                            state.selected.sortedBy { it.ordinal }.forEach { s ->
                                val c = row.cells[s]
                                if (c == null) MissingCell(s.displayName, Format.weekday(row.date, formats))
                                else DayCellText(c, deviation = abs(c.maxC - row.consensus.maxC), bold = false, sourceName = s.displayName, date = Format.weekday(row.date, formats))
                            }
                        }
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("status_list")) {
                Text(stringResource(R.string.compare_status), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))
                Source.entries.forEach { s ->
                    val st = state.statuses[s]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(SourceColors.of(s)))
                            Text(s.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        }
                        Text(statusText(s, st, state.now, formats), style = MaterialTheme.typography.labelSmall, color = statusColor(st))
                    }
                }
            }
        }
    }
}

/**
 * One model's take on one day. How far it sits from the consensus is shown three ways, so it
 * survives without colour vision and without sight: the tint, a caret marker on the temperatures,
 * and a spoken description.
 */
@Composable
private fun DayCellText(c: DayCell, deviation: Double, bold: Boolean, sourceName: String, date: String) {
    val formats = LocalFormats.current
    val (tint, marker) = when {
        deviation < 1.0 -> Color.Transparent to ""
        deviation < 3.0 -> Color(0x33FFD166) to " ›"
        else -> Color(0x40FF8A80) to " »"
    }
    val away = pluralStringResource(R.plurals.compare_cell_degrees, deviation.roundToInt(), deviation.roundToInt())
    val spoken = stringResource(
        R.string.compare_cell_desc, sourceName, date,
        Format.temp(c.minC, formats), Format.temp(c.maxC, formats), Format.mm(c.precipMm, formats), away,
    )
    Column(
        Modifier.width(84.dp).fillMaxHeight().background(tint).padding(horizontal = 4.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Text("${Format.temp(c.minC, formats)} / ${Format.temp(c.maxC, formats)}$marker", style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        Text(Format.mm(c.precipMm, formats), style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9D2F5))
    }
}

/** A model that does not reach this day. Same two-line shape as a filled cell, so the row stays even. */
@Composable
private fun MissingCell(sourceName: String, date: String) {
    val spoken = stringResource(R.string.compare_cell_missing, sourceName, date)
    Column(
        Modifier.width(84.dp).fillMaxHeight().padding(horizontal = 4.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Text("–", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.4f))
        Text("", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun variableLabel(v: CompareVariable) = stringResource(
    when (v) { CompareVariable.TEMPERATURE -> R.string.var_temperature; CompareVariable.PRECIPITATION -> R.string.var_precipitation; CompareVariable.WIND -> R.string.var_wind }
)

@Composable
private fun statusText(source: Source, st: SourceStatus?, now: java.time.Instant, formats: it.apexweather.ui.common.Formats): String = when (st) {
    null -> stringResource(R.string.status_none)
    // Only the sources that publish a run time can claim one; for the rest the timestamp is the fetch time.
    is SourceStatus.Ok -> stringResource(
        if (source.hasRunTime) R.string.status_ok else R.string.status_fetched,
        Format.timestamp(st.issuedAt, DorfTirol.ZONE, now, formats),
    )
    is SourceStatus.Stale -> stringResource(R.string.status_stale, Format.timestamp(st.issuedAt, DorfTirol.ZONE, now, formats))
    is SourceStatus.Failed -> stringResource(R.string.status_failed, st.reason.take(40))
}

private fun statusColor(st: SourceStatus?): Color = when (st) {
    is SourceStatus.Ok -> Color(0xFF7CE0A5)
    is SourceStatus.Stale -> Color(0xFFFFD166)
    else -> Color(0xFFFF8A80)
}
