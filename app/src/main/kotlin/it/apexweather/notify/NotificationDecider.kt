package it.apexweather.notify

import it.apexweather.data.AppSettings
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.Warning
import it.apexweather.ui.home.HomeUiState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the app has already told the reader about, so it never says the same thing twice. */
data class NotifyMemory(
    val lastSummaryDate: LocalDate? = null,
    /** The hour a rain notification was posted for, not the time it was posted. */
    val lastRainOnset: Instant? = null,
    val notifiedWarningIds: Set<String> = emptySet(),
)

/** Something worth interrupting the reader for. */
sealed interface WeatherNotification {
    data class Summary(
        /** The place this is about, so a reader who has switched is not told about the old one. */
        val placeName: String,
        val date: LocalDate,
        val condition: Condition,
        val minC: Double,
        val maxC: Double,
        val precipMm: Double,
    ) : WeatherNotification

    data class RainStarting(val hour: ConsensusHour) : WeatherNotification

    data class Severe(val warning: Warning) : WeatherNotification
}

/**
 * Decides what to post, from state the caller already has. Pure on purpose: notifications are the
 * one part of the app that cannot be checked by looking at the screen, so the rules that fire them
 * are testable without a device.
 *
 * Called once per background refresh, which is hourly.
 */
object NotificationDecider {

    /** How far ahead a rain notification looks. Beyond this it is a forecast, not a heads-up. */
    val RAIN_LOOKAHEAD: Duration = Duration.ofHours(3)

    /** Below these a shower is not worth a notification. */
    private const val RAIN_MIN_PROB = 50
    private const val RAIN_MIN_MM = 0.2

    /**
     * A summary posted long after its hour is noise: a phone that was off all morning should not be
     * told at six in the evening what the day was going to be like.
     */
    private const val SUMMARY_GRACE_HOURS = 4

    fun decide(
        state: HomeUiState,
        settings: AppSettings,
        memory: NotifyMemory,
        now: Instant,
        zone: ZoneId,
        /**
         * The place's name in the reader's language. Passed in rather than read off the place here,
         * because which language that is belongs to the caller's configuration, not to the JVM's
         * default — the same reason [Formats] is passed everywhere else in this app.
         */
        placeName: String,
    ): List<WeatherNotification> = buildList {
        summary(state, settings, memory, now, zone, placeName)?.let(::add)
        rain(state, settings, memory, now)?.let(::add)
        if (settings.notifyWarnings) {
            state.warnings.filter { it.identifier !in memory.notifiedWarningIds }.forEach { add(WeatherNotification.Severe(it)) }
        }
    }

    private fun summary(
        state: HomeUiState,
        settings: AppSettings,
        memory: NotifyMemory,
        now: Instant,
        zone: ZoneId,
        placeName: String,
    ): WeatherNotification.Summary? {
        if (!settings.notifySummary) return null
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        if (memory.lastSummaryDate == today) return null
        val hoursSinceDue = local.hour - settings.notifySummaryHour
        if (hoursSinceDue < 0 || hoursSinceDue > SUMMARY_GRACE_HOURS) return null
        val day = state.days.firstOrNull { it.date == today } ?: return null
        return WeatherNotification.Summary(placeName, today, day.condition, day.minC, day.maxC, day.precipMm)
    }

    private fun rain(
        state: HomeUiState,
        settings: AppSettings,
        memory: NotifyMemory,
        now: Instant,
    ): WeatherNotification.RainStarting? {
        if (!settings.notifyRain) return null
        // Already raining: the reader can see that out of the window.
        val current = state.currentHour ?: return null
        if (current.precipMm >= RAIN_MIN_MM) return null
        val limit = now.plus(RAIN_LOOKAHEAD)
        val onset = state.upcomingHours.firstOrNull { h ->
            h.time.isAfter(now) && !h.time.isAfter(limit) && h.precipProb >= RAIN_MIN_PROB && h.precipMm >= RAIN_MIN_MM
        } ?: return null
        if (memory.lastRainOnset == onset.time) return null
        return WeatherNotification.RainStarting(onset)
    }

    /** The memory to store after [posted] went out, given what was remembered before. */
    fun remember(memory: NotifyMemory, posted: List<WeatherNotification>, activeWarningIds: Set<String>): NotifyMemory =
        NotifyMemory(
            lastSummaryDate = posted.filterIsInstance<WeatherNotification.Summary>().firstOrNull()?.date ?: memory.lastSummaryDate,
            lastRainOnset = posted.filterIsInstance<WeatherNotification.RainStarting>().firstOrNull()?.hour?.time ?: memory.lastRainOnset,
            // Ids of warnings that have expired are dropped, so the set cannot grow without bound and
            // a warning re-issued under a new id is still announced.
            notifiedWarningIds = (memory.notifiedWarningIds + posted.filterIsInstance<WeatherNotification.Severe>().map { it.warning.identifier })
                .intersect(activeWarningIds),
        )
}
