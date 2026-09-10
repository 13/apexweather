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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import it.apexweather.domain.SouthTyrol
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningSection(warnings: List<Warning>, now: Instant, onDismiss: (Warning) -> Unit, onClick: () -> Unit) {
    val top = warnings.firstOrNull() ?: return
    // Keyed on the warning, and the state is created *inside* the key. Remembered outside it, the
    // state — and the lambda it holds — survived into whichever warning took the card next: it
    // stayed at a dismissed value it could not move to again, and the lambda went on reporting the
    // first warning for ever. On the phone that looked like "the first swipe works and no other
    // one does".
    key(top.identifier) {
        val dismissState = rememberSwipeToDismissBoxState()

        // Dismissal happens here rather than in confirmValueChange, which is a predicate Compose
        // calls several times while a gesture settles — a side effect there dismissed the same
        // warning four times over. currentValue changes once, when the card has come to rest.
        LaunchedEffect(dismissState.currentValue) {
            if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) onDismiss(top)
        }

        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {},
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            WarningCard(top, warnings.size, now, onClick)
        }
    }
}

@Composable
private fun WarningCard(top: Warning, total: Int, now: Instant, onClick: () -> Unit) {
    val level = top.level.color
    val typeLabel = top.type.label()
    val levelLabel = top.level.label()
    val window = warningWindow(top, now)
    // The card merges its children, so this one string is everything a screen reader will hear —
    // including that there are more warnings behind it, which is otherwise only visible.
    val spoken = stringResource(R.string.warn_desc, typeLabel, levelLabel, window) +
        if (total > 1) ", " + pluralStringResource(R.plurals.warn_more, total - 1, total - 1) else ""

    Column(
        Modifier.fillMaxWidth()
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
        if (total > 1) {
            Spacer(Modifier.height(6.dp))
            Text(
                pluralStringResource(R.plurals.warn_more, total - 1, total - 1),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.testTag("warning_more"),
            )
        }
    }
}

/** Every warning in force, for the sheet the card opens. */
@Composable
fun WarningDetail(
    warnings: List<Warning>,
    now: Instant,
    isDismissed: (Warning) -> Boolean = { false },
    onDismiss: (Warning) -> Unit = {},
    onRestore: (Warning) -> Unit = {},
) {
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
            // A dismissed warning is dimmed, not removed: waving it away should stop it shouting on
            // the home screen, not put it beyond reach of the reader who wants to look again.
            val dismissed = isDismissed(w)
            val fade = if (dismissed) 0.45f else 1f
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("warning_row_$i"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Warning, contentDescription = null,
                    tint = w.level.color.copy(alpha = fade), modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.warn_headline, w.type.label(), w.level.label()),
                        style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = fade),
                    )
                    Text(
                        warningWindow(w, now),
                        style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f * fade),
                    )
                    // The feed's own English wording, kept verbatim so the reader can see the source's words.
                    Text(w.headline, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f * fade))
                    if (dismissed) {
                        Text(
                            stringResource(R.string.warn_dismissed),
                            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                }
                IconButton(
                    onClick = { if (dismissed) onRestore(w) else onDismiss(w) },
                    modifier = Modifier.testTag(if (dismissed) "warning_restore_$i" else "warning_dismiss_$i"),
                ) {
                    Icon(
                        if (dismissed) Icons.Filled.Visibility else Icons.Filled.Close,
                        contentDescription = stringResource(if (dismissed) R.string.warn_restore else R.string.warn_dismiss),
                        tint = Color.White.copy(alpha = 0.8f),
                    )
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
    val until = Format.timestamp(w.expires, SouthTyrol.ZONE, now, formats)
    return if (w.hasStartedAt(now)) stringResource(R.string.warn_until, until)
    else stringResource(R.string.warn_window, Format.timestamp(w.onset, SouthTyrol.ZONE, now, formats), until)
}
