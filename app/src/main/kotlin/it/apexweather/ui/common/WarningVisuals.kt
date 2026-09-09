package it.apexweather.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import it.apexweather.R
import it.apexweather.domain.model.WarningLevel
import it.apexweather.domain.model.WarningType

/**
 * How a civil-protection warning is written and coloured.
 *
 * The feed words its events in English only ("Orange Rain Warning"), so nothing from it is ever
 * shown as a label; the type and the level are translated here like every other string in the app.
 * Warnings reuse the one warning glyph rather than getting thirteen drawings of their own — the
 * level is what a reader has to take in first, and that is carried by the colour.
 *
 * Everything here has a resource-id form as well as a composable one, because the widget renders
 * outside a composition and shows the same warning.
 */
fun WarningType.labelRes(): Int =
    when (this) {
        WarningType.WIND -> R.string.warn_wind
        WarningType.RAIN -> R.string.warn_rain
        WarningType.THUNDERSTORM -> R.string.warn_thunderstorm
        WarningType.SNOW_ICE -> R.string.warn_snow_ice
        WarningType.FOG -> R.string.warn_fog
        WarningType.HIGH_TEMPERATURE -> R.string.warn_high_temperature
        WarningType.LOW_TEMPERATURE -> R.string.warn_low_temperature
        WarningType.COASTAL_EVENT -> R.string.warn_coastal_event
        WarningType.FOREST_FIRE -> R.string.warn_forest_fire
        WarningType.AVALANCHE -> R.string.warn_avalanche
        WarningType.RAIN_FLOOD -> R.string.warn_rain_flood
        WarningType.FLOOD -> R.string.warn_flood
        WarningType.OTHER -> R.string.warn_other
    }

fun WarningLevel.labelRes(): Int =
    when (this) {
        WarningLevel.YELLOW -> R.string.warn_level_yellow
        WarningLevel.ORANGE -> R.string.warn_level_orange
        WarningLevel.RED -> R.string.warn_level_red
    }

@Composable
@ReadOnlyComposable
fun WarningType.label(): String = stringResource(labelRes())

@Composable
@ReadOnlyComposable
fun WarningLevel.label(): String = stringResource(labelRes())

/** MeteoAlarm's own three colours, lifted enough to stay readable on the darkest sky. */
val WarningLevel.argb: Long
    get() = when (this) {
        WarningLevel.YELLOW -> 0xFFF6D34AL
        WarningLevel.ORANGE -> 0xFFFFA033L
        WarningLevel.RED -> 0xFFFF5A4EL
    }

val WarningLevel.color: Color get() = Color(argb.toInt())
