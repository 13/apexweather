package it.apexweather.widget

import it.apexweather.R
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.iconRes
import it.apexweather.ui.common.labelRes
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

object WidgetStateBuilder {
    /** Three missed hourly refreshes. Below that a widget is merely a few minutes behind. */
    private val STALE_AFTER: Duration = Duration.ofHours(3)

    fun build(home: HomeUiState, zone: ZoneId, formats: Formats): WidgetState {
        val hasData = !home.isEmpty && home.heroTempC != null
        return WidgetState(
            hasData = hasData,
            tempText = home.heroTempC?.let { Format.temp(it, formats) } ?: "–",
            conditionRes = if (hasData) home.heroCondition.labelRes() else R.string.empty_title,
            iconRes = if (hasData) home.heroCondition.iconRes(home.phase) else R.drawable.ic_wx_cloud,
            hours = if (hasData) home.upcomingHours.drop(1).take(6).map { h ->
                WidgetHour(Format.hour(h.time, zone, formats), Format.temp(h.tempC, formats), h.condition.iconRes(home.phaseAt(h.time)))
            } else emptyList(),
            updatedText = home.updatedAt?.let { Format.timestamp(it, zone, home.now, formats) } ?: "",
            isStale = home.updatedAt == null || Duration.between(home.updatedAt, home.now) > STALE_AFTER,
            topColor = home.palette.top,
            bottomColor = home.palette.bottom,
        )
    }
}
