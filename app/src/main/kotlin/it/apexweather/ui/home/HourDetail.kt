package it.apexweather.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.SourceColors
import it.apexweather.ui.common.iconRes
import it.apexweather.ui.common.label
import it.apexweather.ui.theme.fromArgb

/** Shown where a model publishes no value at all, so absence never reads as zero. */
private const val MISSING = "–"

/** One labelled number in the grid, with an optional second line under it. */
private data class Stat(
    val label: String,
    val value: String,
    val sub: String? = null,
    val tag: String,
    val subTag: String? = null,
)

/**
 * The whole of one hour: what the models agree on, and what each of them says on its own. Five
 * quantities used to share one sentence, which could not be scanned and had nowhere to put the
 * gust; they are a grid now, and the per-source numbers are columns rather than a run of digits.
 */
@Composable
fun HourDetail(hour: ConsensusHour, state: HomeUiState, onClose: () -> Unit = {}) {
    val formats = LocalFormats.current
    val unit = state.settings.windUnit
    val accent = Color.fromArgb(state.palette.accent)

    // Ten sources under five tiles run past a phone screen at a large font scale, and the cross is
    // what the reader is left with once scrolling has taken the downward drag away from the sheet.
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                painterResource(hour.condition.iconRes(state.phaseAt(hour.time))),
                contentDescription = null, tint = accent, modifier = Modifier.size(28.dp),
            )
            Text(
                "${Format.time(hour.time, SouthTyrol.ZONE, formats)} · ${hour.condition.label()}",
                style = MaterialTheme.typography.headlineSmall, color = Color.White,
                modifier = Modifier.weight(1f),
            )
            // The ensemble's own spread where it reaches this hour, the model band otherwise —
            // the same order of preference the hero's badge follows.
            AgreementBadge(
                halfWidth = hour.ensembleHalfWidthC ?: ((hour.tempMaxC - hour.tempMinC) / 2),
                agreement = hour.agreement,
                sourceCount = hour.sourceCount,
                tag = "hour_agreement_badge",
            )
            IconButton(onClick = onClose, modifier = Modifier.testTag("hour_detail_close")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White.copy(alpha = 0.8f))
            }
        }
        Text(
            pluralStringResource(R.plurals.now_from_consensus, hour.sourceCount, hour.sourceCount),
            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(12.dp))

        // A tile whose value nobody publishes is left out rather than drawn with a dash. Wind is the
        // exception: that no model publishes wind for this hour is itself worth saying, where the
        // other quantities are.
        val stats = buildList {
            add(
                Stat(
                    label = stringResource(R.string.stat_temp),
                    value = Format.temp(hour.tempC, formats),
                    sub = "${Format.temp(hour.tempMinC, formats)} – ${Format.temp(hour.tempMaxC, formats)}",
                    tag = "hour_stat_temp",
                )
            )
            hour.feelsLikeC?.let {
                add(Stat(stringResource(R.string.stat_feels), Format.temp(it, formats), tag = "hour_stat_feels"))
            }
            add(
                Stat(
                    label = stringResource(R.string.stat_precip),
                    value = Format.mm(hour.precipMm, formats),
                    sub = stringResource(R.string.stat_chance, hour.precipProb),
                    tag = "hour_stat_precip",
                )
            )
            add(
                Stat(
                    label = stringResource(R.string.stat_wind),
                    value = hour.windKmh?.let { Format.wind(it, unit, formats) } ?: MISSING,
                    sub = hour.gustKmh?.let { stringResource(R.string.stat_gust, Format.wind(it, unit, formats)) },
                    tag = "hour_stat_wind",
                    subTag = "hour_stat_gust",
                )
            )
        }
        stats.chunked(2).forEach { row ->
            // The longest labels ("Precipitazioni") need a gutter against a half-width column, or
            // they run into the tile beside them.
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { StatTile(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // Not a tile: its wording flips between "Nullgradgrenze 3.200 m" and "Schneefallgrenze bis
        // 1.100 m", and a fixed label above it would contradict the second of those.
        hour.freezingLevelM?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                freezingLevelText(it, formats),
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.testTag("hour_freezing_level"),
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.per_source), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(6.dp))
        // The header is what lets the cells drop their units, and the columns are fixed widths so a
        // model reading four degrees warm than the rest is visible without reading every row.
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("hour_source_header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.col_model), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
            Cell(stringResource(R.string.col_temp), MaterialTheme.typography.labelSmall, Color.White.copy(alpha = 0.6f))
            Cell(stringResource(R.string.col_precip), MaterialTheme.typography.labelSmall, Color.White.copy(alpha = 0.6f))
            Cell(Format.windUnitLabel(unit).trim(), MaterialTheme.typography.labelSmall, Color.White.copy(alpha = 0.6f))
        }
        hour.perSource.entries.sortedBy { it.key.ordinal }.forEach { (source, p) ->
            // The cells themselves are unit-free — the units live in the header row, a separate
            // semantics node — so TalkBack would otherwise read "17,8°, 0,3, 11" with no unit
            // anywhere. Merge the row into one node and speak the unit-carrying forms instead.
            val spoken = stringResource(
                R.string.hour_source_desc, source.displayName,
                Format.tempDecimal(p.tempC, formats), Format.mm(p.precipMm, formats),
                p.windKmh?.let { Format.wind(it, unit, formats) } ?: MISSING,
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The same colour the compare chart draws this model in, so the two screens agree.
                Box(Modifier.size(7.dp).clip(CircleShape).background(SourceColors.of(source)))
                Spacer(Modifier.width(8.dp))
                Text(source.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f))
                Cell(Format.tempDecimal(p.tempC, formats), MaterialTheme.typography.bodyMedium, Color.White.copy(alpha = 0.85f))
                Cell(Format.mmValue(p.precipMm, formats), MaterialTheme.typography.bodyMedium, Color.White.copy(alpha = 0.85f))
                Cell(p.windKmh?.let { Format.windValue(it, unit, formats) } ?: MISSING, MaterialTheme.typography.bodyMedium, Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun StatTile(stat: Stat, modifier: Modifier = Modifier) {
    // Label, value and sub-line were three separate TalkBack stops for one reading. Merging them
    // reads as one utterance; see the report for what that does to the sub-line's own tag.
    Column(modifier.testTag(stat.tag).semantics(mergeDescendants = true) {}) {
        Text(stat.label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
        Text(stat.value, style = MaterialTheme.typography.titleLarge, color = Color.White)
        stat.sub?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f),
                modifier = stat.subTag?.let { tag -> Modifier.testTag(tag) } ?: Modifier,
            )
        }
    }
}

/** One right-aligned column of the per-source table. The widths are shared by header and rows. */
@Composable
private fun Cell(text: String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    // The width was fixed in dp while the text inside is sp, so at a large font scale "17,8°"
    // soft-wrapped mid-number. Scaling the width with the font (rather than capping maxLines, which
    // would clip a digit) keeps the column wide enough for its own text.
    val width = 62.dp * LocalDensity.current.fontScale
    Text(text, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(width))
}
