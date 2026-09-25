package it.apexweather.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.apexweather.R
import it.apexweather.data.WindUnit
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.StrongWind
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats

/**
 * The strong-wind marks: the hero's line, a strip column's gust, a day row's glyph. The rule of
 * when is [StrongWind]'s; this is only how they look.
 *
 * Storm gusts take the warning card's orange, because a force-9 gust is the thing a wind warning
 * is issued for. Strong ones stay white: amber is forecast chrome in this app, never an alarm.
 */
internal fun windColor(level: StrongWind.Level): Color = when (level) {
    StrongWind.Level.STORM -> Color(0xFFFFA033)
    StrongWind.Level.STRONG -> Color.White.copy(alpha = 0.9f)
}

@Composable
internal fun windLabel(level: StrongWind.Level): String = stringResource(
    when (level) {
        StrongWind.Level.STORM -> R.string.wind_storm
        StrongWind.Level.STRONG -> R.string.wind_strong
    },
)

/** "Böen bis 60 km/h ab 15:00", or without the time where the wind is already blowing. */
@Composable
internal fun windLineText(line: StrongWind.Line, unit: WindUnit): String {
    val formats = LocalFormats.current
    val speed = Format.wind(line.peakKmh, unit, formats)
    return line.from
        ?.let { stringResource(R.string.wind_gusts_from, speed, Format.time(it, SouthTyrol.ZONE, formats)) }
        ?: stringResource(R.string.wind_gusts_now, speed)
}

/**
 * A strip column's gust: the glyph and the bare number in the setting's unit. The glyph carries
 * "Starke Böen" as its description, which the column merges into what it speaks. Empty, at the
 * same height, on a calm hour, so the columns stay level.
 */
@Composable
internal fun WindCell(gustKmh: Double?, unit: WindUnit, modifier: Modifier = Modifier) {
    val level = StrongWind.levelOf(gustKmh)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (level == null) {
            Text("", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
        } else {
            val tint = windColor(level)
            WindGlyph(tint, 11.dp, contentDescription = windLabel(level))
            Text(
                Format.windValue(gustKmh!!, unit, LocalFormats.current),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = tint,
            )
        }
    }
}

@Composable
internal fun WindGlyph(tint: Color, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Icon(Icons.Rounded.Air, contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}
