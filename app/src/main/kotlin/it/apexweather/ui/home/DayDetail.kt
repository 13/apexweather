package it.apexweather.ui.home

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.iconRes
import it.apexweather.ui.common.label
import it.apexweather.ui.theme.fromArgb

/**
 * The whole of one day: its headline numbers, how the hours run, and what each model says on its
 * own. Days late in the week are reached by fewer models, and that shows here as fewer rows rather
 * than as invented agreement.
 */
@Composable
fun DayDetail(day: ConsensusDay, state: HomeUiState) {
    val locale = LocalConfiguration.current.locales[0]
    val formats = LocalFormats.current
    val accent = Color.fromArgb(state.palette.accent)
    val hours = state.hoursByDate[day.date].orEmpty()
    val sources = state.sourcesForDay(day.date)

    // ModalBottomSheet adds no scrolling of its own, and this content runs past a phone screen in
    // landscape or at a large font scale, where the per-source rows would simply be unreachable.
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp),
    ) {
        Text(
            Format.weekdayFull(day.date, formats) + ", " + Format.dayMonth(day.date, formats),
            style = MaterialTheme.typography.headlineMedium, color = Color.White,
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(day.condition.iconRes(SunPhase.DAY)), contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            Text(day.condition.label(), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Format.temp(day.minC, formats), style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.7f))
            Text(Format.temp(day.maxC, formats), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
            AgreementBadge(halfWidth = 0.0, agreement = day.agreement, showSpread = false)
        }
        Spacer(Modifier.height(6.dp))

        Text(
            stringResource(R.string.day_precip_total, Format.mm(day.precipMm, formats)),
            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
        )
        if (day.sunrise != null && day.sunset != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.day_sun, Format.time(day.sunrise, DorfTirol.ZONE, formats), Format.time(day.sunset, DorfTirol.ZONE, formats)),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f),
            )
        }

        if (hours.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.section_day_hours), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            HourStrip(
                hours = hours,
                phaseAt = state::phaseAt,
                accent = accent,
                tagPrefix = "day_hour_column",
                labelFirstAsNow = false,
                modifier = Modifier.testTag("day_hour_strip"),
            )
        }

        if (sources.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.per_source), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            sources.forEach { (source, p) ->
                val spoken = stringResource(
                    R.string.day_source_desc, source.displayName,
                    Format.temp(p.minC, formats), Format.temp(p.maxC, formats), Format.mm(p.precipMm, formats),
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = spoken },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(source.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${Format.temp(p.minC, formats)} / ${Format.temp(p.maxC, formats)}",
                            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
                        )
                        Text(
                            Format.mm(p.precipMm, formats),
                            style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB9D2F5),
                            modifier = Modifier.width(64.dp),
                        )
                    }
                }
            }
        }
    }
}
