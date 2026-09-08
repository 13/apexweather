package it.apexweather.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import it.apexweather.R
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Condition

fun Condition.icon(phase: SunPhase = SunPhase.DAY): ImageVector = when (this) {
    Condition.CLEAR -> if (phase == SunPhase.NIGHT) Icons.Rounded.NightsStay else Icons.Rounded.WbSunny
    Condition.MOSTLY_CLEAR -> if (phase == SunPhase.NIGHT) Icons.Rounded.NightsStay else Icons.Rounded.WbSunny
    Condition.PARTLY_CLOUDY -> Icons.Rounded.WbCloudy
    Condition.CLOUDY -> Icons.Rounded.Cloud
    Condition.FOG -> Icons.Rounded.Dehaze
    Condition.DRIZZLE -> Icons.Rounded.Grain
    Condition.RAIN -> Icons.Rounded.Opacity
    Condition.HEAVY_RAIN -> Icons.Rounded.Umbrella
    Condition.SLEET, Condition.SNOW, Condition.HEAVY_SNOW -> Icons.Rounded.AcUnit
    Condition.THUNDERSTORM -> Icons.Rounded.Bolt
}

@Composable
fun Condition.label(): String = stringResource(
    when (this) {
        Condition.CLEAR -> R.string.cond_clear
        Condition.MOSTLY_CLEAR -> R.string.cond_mostly_clear
        Condition.PARTLY_CLOUDY -> R.string.cond_partly_cloudy
        Condition.CLOUDY -> R.string.cond_cloudy
        Condition.FOG -> R.string.cond_fog
        Condition.DRIZZLE -> R.string.cond_drizzle
        Condition.RAIN -> R.string.cond_rain
        Condition.HEAVY_RAIN -> R.string.cond_heavy_rain
        Condition.SLEET -> R.string.cond_sleet
        Condition.SNOW -> R.string.cond_snow
        Condition.HEAVY_SNOW -> R.string.cond_heavy_snow
        Condition.THUNDERSTORM -> R.string.cond_thunderstorm
    }
)
