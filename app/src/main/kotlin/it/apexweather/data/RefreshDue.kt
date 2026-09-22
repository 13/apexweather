package it.apexweather.data

import it.apexweather.ui.StaleRefresher
import java.time.Duration
import java.time.Instant

/** What the foreground loop should fetch now. */
enum class RefreshKind {
    /** The station alone — one small call, the reading that changes fastest. */
    STATION,

    /** Everything: models, ensembles, bulletin, warnings, and the station with them. */
    FULL,
}

/**
 * What to do now, and how long to sleep before asking again.
 *
 * [wait] is always positive, whatever [kind] is: a zero would be a busy loop on somebody's phone.
 */
data class RefreshDecision(val kind: RefreshKind?, val wait: Duration)

/**
 * When the app, sitting open in front of a reader, should ask each upstream again.
 *
 * **Every decision about *when* to fetch lives here rather than inside the loop**, for the reason
 * `RefreshWorker.pinsToRefresh` and `NotificationDecider` are pure: this is the file somebody reads
 * when they want to know why the app made a request, and it has to be answerable without a phone.
 * The loop in [StaleRefresher] does what this says and nothing else.
 *
 * The cadences are not preferences and are not round numbers. They are each upstream's own
 * publication rate:
 *
 * - **The station every ten minutes**, because SIAG republishes every ten to twenty and sends
 *   `max-age=600` with it. Inside those ten minutes OkHttp answers from its own disk cache without
 *   a network call at all, so a faster poll would only re-read the same bytes; past them a
 *   conditional request costs a `304` with no body against about 80 kB (measured 2026-09-16).
 * - **Everything else every thirty**, which is [StaleRefresher.STALE_ON_OPEN] — the same constant
 *   the resume hook uses, deliberately, so opening the app and sitting in it cannot come to
 *   different conclusions about what stale means.
 */
object RefreshDue {

    /** How often the station is asked while the app is open. See the class note: SIAG's own rate. */
    val STATION_GAP: Duration = Duration.ofMinutes(10)

    /**
     * And on a metered connection.
     *
     * The same asymmetry as `RefreshWorker.pinsToRefresh`: on Wi-Fi this is nothing, and on a phone
     * roaming over a pass it is somebody's money. Twenty rather than off entirely, because the
     * station reading is the one number on the screen that is a measurement.
     */
    val STATION_GAP_METERED: Duration = Duration.ofMinutes(20)

    /** The first backoff step after a failure, doubling from here. */
    val BACKOFF_BASE: Duration = Duration.ofMinutes(1)

    /** And the ceiling. Past this the reader is better served by opening the app again. */
    val BACKOFF_MAX: Duration = Duration.ofMinutes(30)

    /**
     * How long to sleep while there is no network.
     *
     * A floor rather than a schedule: `StaleRefresher`'s `NetworkCallback` is what actually wakes
     * the loop when signal returns, and without it a reader walking back into coverage would wait
     * out a whole backoff.
     */
    val OFFLINE_WAIT: Duration = Duration.ofMinutes(5)

    /**
     * What is due now, and — **only when nothing is** — how long to sleep before asking again.
     *
     * The wait beside a [RefreshKind] is a floor and not a schedule: after acting, the caller asks
     * again rather than sleeping on it. Sleeping on the wait returned with a `FULL` was the bug
     * this shape prevents — a full refresh is due every thirty minutes, so the loop performed one
     * and then slept for thirty, and the station was never polled at ten and twenty at all.
     *
     * Failures are deliberately **not** an input. A rule that returned "nothing due" while a phone
     * was failing could never come back, because the count that suppresses it only clears on a
     * success it is refusing to attempt. The backoff belongs to the caller, which knows whether its
     * last attempt worked; [backoff] is the ladder it uses.
     *
     * @param lastStation when the station last answered, or null if it never has
     * @param lastFull when a full refresh last succeeded, or null
     */
    fun next(
        now: Instant,
        lastStation: Instant?,
        lastFull: Instant?,
        metered: Boolean = false,
        online: Boolean = true,
    ): RefreshDecision {
        if (!online) return RefreshDecision(null, OFFLINE_WAIT)

        val fullAge = age(lastFull, now)
        if (fullAge == null || fullAge >= StaleRefresher.STALE_ON_OPEN) {
            return RefreshDecision(RefreshKind.FULL, StaleRefresher.STALE_ON_OPEN)
        }

        // A full refresh fetches the station as one of its branches, so it resets this clock too.
        // Taking `lastStation` on its own here would ask for the station again within a minute of
        // every full refresh, for a reading the full refresh had just written.
        val stationGap = if (metered) STATION_GAP_METERED else STATION_GAP
        val effectiveStation = latest(lastStation, lastFull)
        val stationAge = age(effectiveStation, now)
        if (stationAge == null || stationAge >= stationGap) {
            return RefreshDecision(RefreshKind.STATION, stationGap)
        }

        val untilStation = stationGap - stationAge
        val untilFull = StaleRefresher.STALE_ON_OPEN - fullAge
        return RefreshDecision(null, minOf(untilStation, untilFull).atLeastATick())
    }

    /** 1, 2, 4, 8, 16, 30, 30 … minutes. */
    fun backoff(consecutiveFailures: Int): Duration {
        if (consecutiveFailures <= 0) return BACKOFF_BASE
        // Shifting past 30 steps would overflow long before the cap is interesting, so it is capped
        // on the exponent as well as on the result.
        val doublings = (consecutiveFailures - 1).coerceAtMost(30)
        val grown = BACKOFF_BASE.multipliedBy(1L shl doublings)
        return minOf(grown, BACKOFF_MAX)
    }

    private fun age(t: Instant?, now: Instant): Duration? =
        t?.let { Duration.between(it, now).takeIf { d -> !d.isNegative } ?: Duration.ZERO }

    private fun latest(a: Instant?, b: Instant?): Instant? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    /** No wait is ever zero: the loop would spin. */
    private fun Duration.atLeastATick(): Duration = if (this < MIN_WAIT) MIN_WAIT else this

    private val MIN_WAIT: Duration = Duration.ofSeconds(1)
}
