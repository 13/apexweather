package it.apexweather.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.Warning
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.color
import it.apexweather.ui.common.label
import java.time.Instant

/**
 * The civil-protection warnings in force, worst first, as a card above everything else on the
 * screen — a red wind warning has to be readable before the reader has scrolled anywhere.
 *
 * The card carries the top warning in full and counts the rest; tapping it opens the list. It is
 * painted in MeteoAlarm's own colour for the level rather than in the sky palette, because that
 * colour is the one piece of the warning a reader already knows how to read.
 */
@Composable
fun WarningSection(warnings: List<Warning>, now: Instant, onClick: () -> Unit) {
    val top = warnings.firstOrNull() ?: return
    val level = top.level.color
    val typeLabel = top.type.label()
    val levelLabel = top.level.label()
    val window = warningWindow(top, now)
    // The card merges its children, so this one string is everything a screen reader will hear —
    // including that there are more warnings behind it, which is otherwise only visible.
    val spoken = stringResource(R.string.warn_desc, typeLabel, levelLabel, window) +
        if (warnings.size > 1) ", " + pluralStringResource(R.plurals.warn_more, warnings.size - 1, warnings.size - 1) else ""

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(level.copy(alpha = 0.20f))
            .border(BorderStroke(1.dp, level.copy(alpha = 0.55f)), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(14.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .testTag("warning_card"),
    ) {
        Row(
            // The settings button floats over the top-right corner of the screen and this card is
            // always the topmost thing under it, so the headline keeps clear of it.
            Modifier.padding(end = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = level, modifier = Modifier.size(22.dp))
            Text(
                stringResource(R.string.warn_headline, typeLabel, levelLabel),
                style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(window, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
        Text(
            stringResource(R.string.warn_area, top.areaDesc),
            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f),
        )
        if (warnings.size > 1) {
            Spacer(Modifier.height(6.dp))
            Text(
                pluralStringResource(R.plurals.warn_more, warnings.size - 1, warnings.size - 1),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.testTag("warning_more"),
            )
        }
    }
}

/** Every warning in force, for the sheet the card opens. */
@Composable
fun WarningDetail(warnings: List<Warning>, now: Instant) {
    // ModalBottomSheet scrolls nothing by itself, and a bad day in the mountains can put four or
    // five warnings in force at once; without this the last of them would simply be unreachable.
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp),
    ) {
        Text(
            stringResource(R.string.warnings_sheet_title, warnings.firstOrNull()?.areaDesc.orEmpty()),
            style = MaterialTheme.typography.headlineMedium, color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        warnings.forEachIndexed { i, w ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("warning_row_$i"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = w.level.color, modifier = Modifier.size(20.dp))
                Column {
                    Text(
                        stringResource(R.string.warn_headline, w.type.label(), w.level.label()),
                        style = MaterialTheme.typography.bodyLarge, color = Color.White,
                    )
                    Text(
                        warningWindow(w, now),
                        style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
                    )
                    // The feed's own English wording, kept verbatim so the reader can see the source's words.
                    Text(w.headline, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.warn_area, warnings.firstOrNull()?.areaDesc.orEmpty()),
            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f),
        )
    }
}

/**
 * "until 21:00" once a warning is already running, "09:00 to 21:00" while it is still ahead. A
 * warning in force does not need its start time repeated; one that has not started does.
 */
@Composable
private fun warningWindow(w: Warning, now: Instant): String {
    val formats = LocalFormats.current
    val until = Format.timestamp(w.expires, DorfTirol.ZONE, now, formats)
    return if (w.hasStartedAt(now)) stringResource(R.string.warn_until, until)
    else stringResource(R.string.warn_window, Format.timestamp(w.onset, DorfTirol.ZONE, now, formats), until)
}
