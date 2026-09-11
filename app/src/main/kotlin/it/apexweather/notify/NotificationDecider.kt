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
    /** [Warning.noticeKey]s, not bare identifiers: an upgraded warning is a new thing to be told. */
    val notifiedWarningKeys: Set<String> = emptySet(),
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

    /** [startsAt] is the quarter-hour the rain begins where a model publishes one, else the hour. */
    data class RainStarting(val hour: ConsensusHour, val startsAt: Instant) : WeatherNotification

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

    /**
     * Below these a shower is not worth a notification.
     *
     * [RAIN_MIN_PROB] is read against a number that has changed meaning since it was picked.
     * `ConsensusHour.precipProb` used to be the **maximum** across the models, so fifty meant "one
     * model of ten thinks it likely" — the single most alarmist run could fire this on its own. It
     * is the **mean** now, which is what a probability across equally good models actually is, and
     * fifty against that means five or six of ten calling it likely within the next three hours.
     * Left unchanged, the same constant quietly turned a heads-up into something that almost never
     * arrives.
     *
     * Thirty, because that is the app's own existing threshold for "wet enough to say so" expressed
     * as a probability: `ConsensusBlender.WET_SHARE_DENOMINATOR` calls an hour wet when a third of
     * the models put water in the sky, and a third of them at ninety per cent against the rest at
     * zero averages to thirty. The asymmetry is deliberate and runs the same way as every other one
     * in this app — being rained on unwarned is worse than carrying a jacket that was not needed —
     * and [RAIN_MIN_MM] still has to be met by the same hour, so this is never a trace shower.
     */
    private const val RAIN_MIN_PROB = 30
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
            // By [Warning.noticeKey] rather than the identifier: MeteoAlarm issuing a red warning
            // under the identifier of the yellow one it replaces would otherwise never be announced,
            // which is exactly the case the card's own dismissals have always keyed against.
            state.warnings.filter { it.noticeKey !in memory.notifiedWarningKeys }.forEach { add(WeatherNotification.Severe(it)) }
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
        // The hourly series can only say "some time in the 15:00 hour". Where the regional models
        // published a quarter-hourly one, it says 15:15, and that is what the reader is told.
        val precise = state.minutelyStart?.takeIf { !it.isBefore(now) && !it.isAfter(limit) } ?: onset.time
        return WeatherNotification.RainStarting(onset, precise)
    }

    /** The memory to store after [posted] went out, given what was remembered before. */
    fun remember(memory: NotifyMemory, posted: List<WeatherNotification>, activeWarningKeys: Set<String>): NotifyMemory =
        NotifyMemory(
            lastSummaryDate = posted.filterIsInstance<WeatherNotification.Summary>().firstOrNull()?.date ?: memory.lastSummaryDate,
            lastRainOnset = posted.filterIsInstance<WeatherNotification.RainStarting>().firstOrNull()?.hour?.time ?: memory.lastRainOnset,
            // Keys of warnings that have expired are dropped, so the set cannot grow without bound
            // and a warning re-issued under a new id is still announced.
            notifiedWarningKeys = (memory.notifiedWarningKeys + posted.filterIsInstance<WeatherNotification.Severe>().map { it.warning.noticeKey })
                .intersect(activeWarningKeys),
        )
}
