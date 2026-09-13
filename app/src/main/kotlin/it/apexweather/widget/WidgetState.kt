package it.apexweather.widget

import it.apexweather.R
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.iconRes
import it.apexweather.ui.common.argb
import it.apexweather.ui.common.labelRes
import it.apexweather.domain.SunPhase
import it.apexweather.ui.home.HomeUiState
import java.time.Duration
import java.time.ZoneId

data class WidgetHour(val label: String, val tempText: String, val iconRes: Int)

/**
 * One row of the tall widget: a day, its icon, its range and how likely it is to rain.
 *
 * The chance is here and not on the hour rows because it is the question a day is asked. See
 * [it.apexweather.domain.model.ConsensusDay.precipProb].
 */
data class WidgetDay(
    val label: String,
    val iconRes: Int,
    val minText: String,
    val maxText: String,
    val probText: String,
)

data class WidgetState(
    /** The place this is about, in the reader's language. */
    val placeName: String,
    val hasData: Boolean,
    val tempText: String,
    val conditionRes: Int,
    val iconRes: Int,
    val hours: List<WidgetHour>,
    /**
     * The days, for the tall widget only. Five of them: enough to be worth the height, and short of
     * the point where the list is two ECMWF runs and the badge the app draws to say so has no room
     * on a widget.
     */
    val days: List<WidgetDay> = emptyList(),
    val updatedText: String,
    /** True once the data is old enough that the widget must say so rather than just show it. */
    val isStale: Boolean,
    val topColor: Long,
    val bottomColor: Long,
    /** The worst warning in force, or null. Resource ids rather than text: the widget resolves them. */
    val warningTypeRes: Int? = null,
    val warningLevelRes: Int? = null,
    val warningColor: Long = 0xFFFFFFFFL,
)

object WidgetStateBuilder {
    /** Three missed hourly refreshes. Below that a widget is merely a few minutes behind. */
    private val STALE_AFTER: Duration = Duration.ofHours(3)

    /**
     * How many days the tall widget carries.
     *
     * Five, not fourteen. Past about day five only the globals reach and the app says so with a
     * badge the reader can ask about; a widget has neither the room for that badge nor anywhere to
     * put the explanation, so it stops where the answer is still a full consensus. The first row is
     * today, and its label is left empty rather than saying "today" — the temperature above it
     * already does.
     */
    const val TALL_DAYS = 5

    fun build(home: HomeUiState, zone: ZoneId, formats: Formats): WidgetState {
        val hasData = !home.isEmpty && home.heroTempC != null
        return WidgetState(
            placeName = home.place?.name(formats.locale).orEmpty(),
            hasData = hasData,
            tempText = home.heroTempC?.let { Format.temp(it, formats) } ?: "–",
            conditionRes = if (hasData) home.heroCondition.labelRes() else R.string.empty_title,
            iconRes = if (hasData) home.heroCondition.iconRes(home.phase) else R.drawable.ic_wx_cloud,
            hours = if (hasData) home.upcomingHours.drop(1).take(6).map { h ->
                WidgetHour(Format.hour(h.time, zone, formats), Format.temp(h.tempC, formats), h.condition.iconRes(home.phaseAt(h.time)))
            } else emptyList(),
            days = if (hasData) home.days.take(TALL_DAYS).mapIndexed { i, d ->
                WidgetDay(
                    label = if (i == 0) "" else Format.weekday(d.date, formats),
                    iconRes = d.condition.iconRes(SunPhase.DAY),
                    minText = Format.temp(d.minC, formats),
                    maxText = Format.temp(d.maxC, formats),
                    probText = if (d.precipProb > 0) "${d.precipProb} %" else "",
                )
            } else emptyList(),
            updatedText = home.updatedAt?.let { Format.timestamp(it, zone, home.now, formats) } ?: "",
            isStale = home.updatedAt == null || Duration.between(home.updatedAt, home.now) > STALE_AFTER,
            topColor = home.palette.top,
            bottomColor = home.palette.bottom,
            // Worst first out of the repository, so the first one is the one the widget has room for.
            warningTypeRes = home.warnings.firstOrNull()?.type?.labelRes(),
            warningLevelRes = home.warnings.firstOrNull()?.level?.labelRes(),
            warningColor = home.warnings.firstOrNull()?.level?.argb ?: 0xFFFFFFFFL,
        )
    }
}
