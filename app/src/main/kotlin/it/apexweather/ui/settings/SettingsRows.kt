package it.apexweather.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import it.apexweather.ui.common.GlassCard

/**
 * One group of settings: a heading, and a card holding the rows that belong to it.
 *
 * The heading sits **above** the card rather than between controls. It used to be a `labelSmall`
 * floating in a column spaced by a flat 16 dp, which put a heading exactly as far from its own
 * control as from the unrelated one before it — so nothing on the screen was grouped with anything.
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        GlassCard(Modifier.fillMaxWidth(), content = content)
    }
}

/** The height a row is held to, so a finger has something to hit and the column keeps a rhythm. */
private val RowMinHeight = 48.dp

/** The icon column's width, so every label in a card starts at the same x. */
private val IconColumn = 36.dp

private val IconSize = 20.dp

/**
 * One setting: an icon, a label, an optional second line, and whatever operates it on the right.
 *
 * The icons are **decorative** (`contentDescription = null`). Every one sits beside a label that
 * already says the same thing, and a screen reader announcing "Ort-Symbol, Ort" is worse than
 * silence. What the icon column buys is a form that can be scanned instead of read.
 */
@Composable
fun SettingRow(
    icon: ImageVector,
    label: String,
    // Before `supporting`, because lint requires the modifier to be the first optional parameter.
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        // heightIn, never height: a fixed height clips a two-line row at a large font scale, which
        // is the same reason DayRowMinHeight is a floor rather than a size.
        modifier.fillMaxWidth().heightIn(min = RowMinHeight).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(IconSize),
        )
        Spacer(Modifier.size(IconColumn - IconSize))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            }
        }
        trailing()
    }
}

/**
 * A setting that is on or off, where **the whole row toggles it**.
 *
 * A switch is about 52 dp of target at the right edge of a 360 dp row, and the label is the obvious
 * thing to press. The switch keeps its own semantics and its own test tag; the row's click is what
 * makes the rest of it live.
 */
@Composable
fun SwitchRow(
    icon: ImageVector,
    label: String,
    supporting: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    switchModifier: Modifier = Modifier,
) {
    SettingRow(
        icon = icon,
        label = label,
        supporting = supporting,
        modifier = modifier.clickable { onChange(!checked) },
    ) {
        Switch(checked = checked, onCheckedChange = onChange, modifier = switchModifier)
    }
}

/** A setting that lives somewhere else: its current value, and a chevron into it. */
@Composable
fun NavRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
) {
    SettingRow(
        icon = icon,
        label = label,
        modifier = modifier.clickable(onClickLabel = onClickLabel, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            value?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
        }
    }
}

/**
 * The icon and label of a setting whose control is too wide to sit beside them.
 *
 * Not a [SettingRow] with an empty trailing slot: that pays [RowMinHeight] for a row with nothing
 * on its right, and at a 2x font scale the wasted 48 dp pushed the key field below the fold.
 */
@Composable
fun SettingLabel(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(IconSize),
        )
        Spacer(Modifier.size(IconColumn - IconSize))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}
