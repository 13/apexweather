package it.apexweather.widget

import it.apexweather.R
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Condition
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.home.HomeUiState
import java.time.Duration
import java.time.ZoneId

data class WidgetHour(val label: String, val tempText: String, val iconRes: Int)

data class WidgetState(
    val hasData: Boolean,
    val tempText: String,
    val conditionRes: Int,
    val iconRes: Int,
    val hours: List<WidgetHour>,
    val updatedText: String,
    /** True once the data is old enough that the widget must say so rather than just show it. */
    val isStale: Boolean,
    val topColor: Long,
    val bottomColor: Long,
)

fun Condition.widgetIcon(phase: SunPhase): Int = when (this) {
    Condition.CLEAR, Condition.MOSTLY_CLEAR -> if (phase == SunPhase.NIGHT) R.drawable.ic_wx_moon else R.drawable.ic_wx_sun
    Condition.PARTLY_CLOUDY, Condition.CLOUDY -> R.drawable.ic_wx_cloud
    Condition.FOG -> R.drawable.ic_wx_fog
    Condition.DRIZZLE, Condition.RAIN, Condition.HEAVY_RAIN -> R.drawable.ic_wx_rain
    Condition.SLEET, Condition.SNOW, Condition.HEAVY_SNOW -> R.drawable.ic_wx_snow
    Condition.THUNDERSTORM -> R.drawable.ic_wx_storm
}

fun Condition.labelRes(): Int = when (this) {
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

object WidgetStateBuilder {
    /** Three missed hourly refreshes. Below that a widget is merely a few minutes behind. */
    private val STALE_AFTER: Duration = Duration.ofHours(3)

    fun build(home: HomeUiState, zone: ZoneId, formats: Formats): WidgetState {
        val hasData = !home.isEmpty && home.heroTempC != null
        return WidgetState(
            hasData = hasData,
            tempText = home.heroTempC?.let { Format.temp(it, formats) } ?: "–",
            conditionRes = if (hasData) home.heroCondition.labelRes() else R.string.empty_title,
            iconRes = if (hasData) home.heroCondition.widgetIcon(home.phase) else R.drawable.ic_wx_cloud,
            hours = if (hasData) home.upcomingHours.drop(1).take(6).map { h ->
                WidgetHour(Format.hour(h.time, zone, formats), Format.temp(h.tempC, formats), h.condition.widgetIcon(home.phaseAt(h.time)))
            } else emptyList(),
            updatedText = home.updatedAt?.let { Format.timestamp(it, zone, home.now, formats) } ?: "",
            isStale = home.updatedAt == null || Duration.between(home.updatedAt, home.now) > STALE_AFTER,
            topColor = home.palette.top,
            bottomColor = home.palette.bottom,
        )
    }
}
