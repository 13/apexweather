package it.apexweather.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.R
import it.apexweather.data.WindUnit
import it.apexweather.domain.Contender
import it.apexweather.domain.DayPart
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Quantity
import it.apexweather.domain.Score
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.Source
import it.apexweather.ui.common.CompactLabel
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.SourceColors
import it.apexweather.ui.compare.labelRes
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A score's numbers in the reader's units: K, km/h (or their wind unit), percent. */
@Composable
internal fun mainValue(quantity: Quantity, score: Score, windUnit: WindUnit, f: Formats): String = when (quantity) {
    Quantity.TEMPERATURE -> score.main?.let { f.oneDecimal(it) + " K" } ?: MISSING
    Quantity.WIND -> score.main?.let { Format.wind(it, windUnit, f) } ?: MISSING
    Quantity.RAIN -> percent(score.main, f)
}

@Composable
internal fun percent(share: Double?, f: Formats): String =
    share?.let { stringResource(R.string.stats_percent, f.whole((it * 100).roundToInt())) } ?: MISSING

@Composable
internal fun leanValue(quantity: Quantity, score: Score, windUnit: WindUnit, f: Formats): String = when (quantity) {
    Quantity.TEMPERATURE -> score.lean?.let { Format.kelvinDelta(it, f) } ?: MISSING
    Quantity.WIND -> score.lean?.let { (if (it > 0) "+" else "") + Format.wind(it, windUnit, f) } ?: MISSING
    Quantity.RAIN -> percent(score.lean, f)
}

private const val MISSING = "–"

/**
 * One value column's width. Header and cells share it so they line up, and it grows with the
 * reader's text size the way the hour strip's columns do; capped so three of them still fit a
 * phone's line at a 2x font scale, where the value group moves under the name instead.
 */
@Composable
internal fun statsColumnWidth(): Dp = (64.dp * maxOf(1f, LocalDensity.current.fontScale)).coerceAtMost(96.dp)

@Composable
fun StatsScreen(onBack: () -> Unit, onOpenSource: (Source) -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatsContent(
        state,
        onQuantity = viewModel::setQuantity,
        onPeriod = viewModel::setPeriod,
        onLead = viewModel::setLead,
        onOpenDetail = viewModel::openDetail,
        onCloseDetail = viewModel::closeDetail,
        onOpenSource = { viewModel.closeDetail(); onOpenSource(it) },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsContent(
    state: StatsUiState,
    onQuantity: (Quantity) -> Unit,
    onPeriod: (StatsPeriod) -> Unit,
    onLead: (LeadBucket) -> Unit,
    onOpenDetail: (Source) -> Unit,
    onCloseDetail: () -> Unit,
    onOpenSource: (Source) -> Unit,
    onBack: () -> Unit,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val formats = LocalFormats.current
    var infoOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().testTag("stats_screen"),
        contentPadding = PaddingValues(top = topInset + 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
                Text(
                    stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.headlineMedium, color = Color.White,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = { infoOpen = true }, modifier = Modifier.testTag("stats_info")) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.stats_info), tint = Color.White.copy(alpha = 0.85f))
                }
            }
        }
        // Both defaults below are true while loading, so nothing is said about the station or the
        // amount of data until the history has actually been read.
        if (state.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp)
                }
            }
            return@LazyColumn
        }
        if (!state.hasStation) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("stats_no_station")) {
                    Text(stringResource(R.string.stats_no_station), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
            return@LazyColumn
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Quantity.entries.forEachIndexed { i, q ->
                    SegmentedButton(
                        selected = state.quantity == q, onClick = { onQuantity(q) },
                        shape = SegmentedButtonDefaults.itemShape(i, Quantity.entries.size),
                        // No tick, as on the comparison screen: the fill already says which is on.
                        icon = {},
                        modifier = Modifier.testTag("stats_quantity_${q.name}"),
                    ) { CompactLabel { Text(quantityLabel(q), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                }
            }
        }
        item {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatsPeriod.entries.forEach { p ->
                    StatsChip(stringResource(R.string.stats_period, p.days.toInt()), state.period == p, "stats_period_${p.name}") { onPeriod(p) }
                }
                Spacer(Modifier.width(10.dp))
                LeadBucket.entries.forEach { l ->
                    val label = if (l == LeadBucket.NOW) stringResource(R.string.stats_lead_now)
                    else stringResource(R.string.stats_lead_hours, l.hours.toInt())
                    StatsChip(label, state.lead == l, "stats_lead_${l.name}") { onLead(l) }
                }
            }
        }
        val first = state.firstHour
        if (state.observedHours > 0 && first != null) {
            item {
                Text(
                    pluralStringResource(
                        R.plurals.stats_basis, state.observedHours, state.observedHours,
                        Format.dayMonth(first.atZone(SouthTyrol.ZONE).toLocalDate(), formats), state.stationName.orEmpty(),
                    ),
                    style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        if (state.rows.isEmpty() && state.unranked.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("stats_empty")) {
                    Text(stringResource(R.string.stats_not_enough), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
        } else {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    StatsHeaderRow(state.quantity)
                    state.rows.forEach { row ->
                        StatsRowView(row, state.quantity, state.windUnit, onClick = onOpenDetail)
                    }
                    state.unranked.forEach { row -> UnrankedRow(row) }
                }
            }
        }
    }

    if (infoOpen) {
        AlertDialog(
            onDismissRequest = { infoOpen = false },
            confirmButton = { TextButton(onClick = { infoOpen = false }) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.stats_info)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.stats_info_body))
                    if (state.excludedHours > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(pluralStringResource(R.plurals.stats_info_excluded, state.excludedHours, state.excludedHours))
                    }
                }
            },
        )
    }

    val detail = state.detail
    if (detail != null) {
        ModalBottomSheet(
            onDismissRequest = onCloseDetail,
            // Full height and a cross: the sheet scrolls (CLAUDE.md, bottom sheets).
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.testTag("stats_detail_sheet"),
        ) {
            StatsDetailSheet(
                detail,
                rank = state.rows.firstOrNull { it.contender == Contender.Model(detail.source) }?.rank,
                windUnit = state.windUnit,
                onClose = onCloseDetail,
                onOpenSource = onOpenSource,
            )
        }
    }
}

@Composable
private fun StatsChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color.White.copy(alpha = 0.22f),
            labelColor = Color.White.copy(alpha = 0.75f),
            selectedLabelColor = Color.White,
        ),
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun quantityLabel(q: Quantity): String = stringResource(
    when (q) {
        Quantity.TEMPERATURE -> R.string.stats_quantity_temperature
        Quantity.RAIN -> R.string.stats_quantity_rain
        Quantity.WIND -> R.string.stats_quantity_wind
    },
)

@Composable
private fun columnLabels(quantity: Quantity): List<String> =
    if (quantity == Quantity.RAIN) listOf(
        stringResource(R.string.stats_col_rain_right), stringResource(R.string.stats_col_detected), stringResource(R.string.stats_col_false_alarm),
    ) else listOf(
        stringResource(R.string.stats_col_error), stringResource(R.string.stats_col_hits), stringResource(R.string.stats_col_lean),
    )

@Composable
private fun columnValues(quantity: Quantity, score: Score, windUnit: WindUnit, f: Formats): List<String> =
    if (quantity == Quantity.RAIN) listOf(mainValue(quantity, score, windUnit, f), percent(score.hitRate, f), percent(score.lean, f))
    else listOf(mainValue(quantity, score, windUnit, f), percent(score.hitRate, f), leanValue(quantity, score, windUnit, f))

/** The rank column and the space a colour dot takes, so names line up whether a row has one or not. */
private val RankWidth = 28.dp
private val DotSlot = 16.dp

@Composable
private fun StatsHeaderRow(quantity: Quantity) {
    val col = statsColumnWidth()
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text("#", Modifier.width(RankWidth), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.width(DotSlot))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            // A column's width is not the label's own, so the label is held to CompactLabel's
            // ceiling: at a 2x font scale "Tendenz" and "Fehlalarm" otherwise ran into their
            // neighbours, a single word having nowhere to wrap.
            columnLabels(quantity).forEach { label ->
                CompactLabel {
                    Text(
                        label, Modifier.width(col).padding(start = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/**
 * One line of the table. A model's row opens its detail; a reference row is there to be beaten and
 * opens nothing. At a large text size the three values move under the name rather than squeezing it.
 */
@Composable
internal fun StatsRowView(
    row: StatsRow,
    quantity: Quantity,
    windUnit: WindUnit,
    onClick: ((Source) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val f = LocalFormats.current
    val col = statsColumnWidth()
    val model = (row.contender as? Contender.Model)?.source
    val name = when (row.contender) {
        is Contender.Model -> row.contender.source.shortName
        Contender.Consensus -> stringResource(R.string.stats_consensus)
        Contender.SameAsYesterday -> stringResource(R.string.stats_persistence)
    }
    val labels = columnLabels(quantity)
    val values = columnValues(quantity, row.score, windUnit, f)
    val summary = labels.zip(values).joinToString(", ") { (l, v) -> "$l $v" }
    val description = if (model != null && row.rank != null) stringResource(R.string.stats_row_desc_ranked, row.rank, name, summary)
    else stringResource(R.string.stats_row_desc_other, name, summary)
    val tag = when (row.contender) {
        is Contender.Model -> "stats_row_${row.contender.source.name}"
        Contender.Consensus -> "stats_row_consensus"
        Contender.SameAsYesterday -> "stats_row_yesterday"
    }
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(if (model != null && onClick != null) Modifier.clickable(role = Role.Button) { onClick(model) } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.rank?.let { f.whole(it) }.orEmpty(), Modifier.width(RankWidth),
            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f),
        )
        Box(Modifier.width(DotSlot)) {
            if (model != null) Box(Modifier.size(8.dp).clip(CircleShape).background(SourceColors.of(model)))
        }
        // The name takes what the values leave; when the values do not fit beside it they move to a
        // line of their own, still right-aligned under their headers.
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (model != null) Color.White else Color.White.copy(alpha = 0.8f),
                fontStyle = if (model != null) FontStyle.Normal else FontStyle.Italic,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.End) {
                values.forEach { v ->
                    Text(v, Modifier.width(col), style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun UnrankedRow(row: StatsRow) {
    val source = (row.contender as? Contender.Model)?.source ?: return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).testTag("stats_unranked"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(RankWidth))
        Box(Modifier.width(DotSlot)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(SourceColors.of(source).copy(alpha = 0.55f)))
        }
        Text(
            "${source.shortName} · ${stringResource(R.string.stats_unranked, row.score.hours)}",
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f),
        )
    }
}

/** One model's record, split by the part of the day and, for temperature and wind, by day. */
@Composable
internal fun StatsDetailSheet(
    detail: StatsDetail,
    rank: Int?,
    windUnit: WindUnit,
    onClose: () -> Unit,
    onOpenSource: (Source) -> Unit,
) {
    val f = LocalFormats.current
    val col = statsColumnWidth()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                detail.source.displayName,
                style = MaterialTheme.typography.headlineMedium, color = Color.White,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onClose, modifier = Modifier.testTag("stats_detail_close")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.height(12.dp))
        StatsHeaderRow(detail.quantity)
        StatsRowView(
            StatsRow(Contender.Model(detail.source), rank, detail.score ?: Score(0, null, null, null)),
            detail.quantity, windUnit, onClick = null,
        )

        SheetHeading(R.string.stats_detail_by_part)
        DayPart.entries.forEach { part ->
            val score = detail.byPart[part] ?: Score(0, null, null, null)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(part.labelRes()), style = MaterialTheme.typography.bodyMedium, color = Color.White,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text(mainValue(detail.quantity, score, windUnit, f), Modifier.width(col), style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.End)
                Text(percent(score.hitRate, f), Modifier.width(col), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.End)
            }
        }

        if (detail.quantity != Quantity.RAIN && detail.daily.size >= 2) {
            SheetHeading(R.string.stats_detail_daily)
            DailyChart(detail, windUnit)
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { onOpenSource(detail.source) }, modifier = Modifier.fillMaxWidth().testTag("stats_detail_source")) {
            Text(stringResource(R.string.stats_detail_open_source))
        }
    }
}

@Composable
private fun SheetHeading(title: Int) {
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun DailyChart(detail: StatsDetail, windUnit: WindUnit) {
    val f = LocalFormats.current
    val values = detail.daily.map { it.second }
    val top = ceil(values.max()).coerceAtLeast(1.0)
    fun label(v: Double): String = if (detail.quantity == Quantity.WIND) Format.wind(v, windUnit, f) else f.oneDecimal(v) + " K"
    val firstDate = Format.dayMonth(detail.daily.first().first, f)
    val lastDate = Format.dayMonth(detail.daily.last().first, f)
    val description = listOf(
        stringResource(R.string.stats_detail_daily), "$firstDate – $lastDate", "${label(values.min())} – ${label(values.max())}",
    ).joinToString(" · ")
    val color = SourceColors.of(detail.source)
    val axis = Color.White.copy(alpha = 0.35f)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.dp.toPx()
                val baseline = size.height - stroke / 2
                drawLine(axis, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())
                val step = size.width / (values.size - 1)
                val path = Path()
                values.forEachIndexed { i, v ->
                    val x = i * step
                    val y = baseline - (v / top).toFloat() * (baseline - stroke)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = stroke))
            }
            Text(label(top), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(firstDate, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            Text(lastDate, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
}
