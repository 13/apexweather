package it.apexweather.data

import it.apexweather.ui.StaleRefresher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * When the app, open in front of a reader, asks each upstream again.
 *
 * The rule is pure so that these cases live here rather than on a phone — the same reason
 * `RefreshWorker.pinsToRefresh` and `ChartAxis` are. A loop is the worst thing to put inside an
 * untested class, and `StaleRefresher` was one; this is the half of it that can be reasoned about.
 */
class RefreshDueTest {

    private val t0: Instant = Instant.parse("2026-09-22T09:00:00Z")

    private fun at(minutes: Long): Instant = t0.plus(Duration.ofMinutes(minutes))

    @Test
    fun `a cold start asks for everything`() {
        val d = RefreshDue.next(t0, lastStation = null, lastFull = null)
        assertEquals(RefreshKind.FULL, d.kind)
    }

    @Test
    fun `ten minutes after a full refresh the station alone is due`() {
        val d = RefreshDue.next(at(10), lastStation = t0, lastFull = t0)
        assertEquals(RefreshKind.STATION, d.kind)
    }

    @Test
    fun `thirty-one minutes after a full refresh everything is due again`() {
        val d = RefreshDue.next(at(31), lastStation = at(20), lastFull = t0)
        assertEquals(RefreshKind.FULL, d.kind)
    }

    /**
     * The one that would otherwise fetch the station twice a minute forever.
     *
     * A full refresh has the station as one of its branches, so it resets that clock too. Reading
     * `lastStation` on its own would see "never" — or an old value — straight after a full refresh
     * and ask again for the reading that refresh had just written.
     */
    @Test
    fun `a full refresh satisfies the station too`() {
        val justAfterFull = RefreshDue.next(at(1), lastStation = null, lastFull = at(0))
        assertNull(justAfterFull.kind)

        val nineMinutesLater = RefreshDue.next(at(9), lastStation = null, lastFull = at(0))
        assertNull(nineMinutesLater.kind)

        val tenMinutesLater = RefreshDue.next(at(10), lastStation = null, lastFull = at(0))
        assertEquals(RefreshKind.STATION, tenMinutesLater.kind)
    }

    @Test
    fun `a metered connection asks the station every twenty minutes and leaves the rest alone`() {
        assertNull(RefreshDue.next(at(15), lastStation = t0, lastFull = t0, metered = true).kind)
        assertEquals(
            RefreshKind.STATION,
            RefreshDue.next(at(20), lastStation = t0, lastFull = t0, metered = true).kind,
        )
        // The full refresh keeps its own thirty whatever the connection costs.
        assertEquals(
            RefreshKind.FULL,
            RefreshDue.next(at(30), lastStation = at(25), lastFull = t0, metered = true).kind,
        )
    }

    @Test
    fun `offline decides nothing, whatever is overdue`() {
        val d = RefreshDue.next(at(600), lastStation = null, lastFull = null, online = false)
        assertNull(d.kind)
        assertEquals(RefreshDue.OFFLINE_WAIT, d.wait)
    }

    /** Asserted as a ladder rather than one case, because the shape is the point. */
    @Test
    fun `the backoff doubles to a ceiling of thirty minutes`() {
        val ladder = (1..8).map { RefreshDue.backoff(it).toMinutes() }
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L), ladder)
    }

    @Test
    fun `a failure delays the next attempt and decides nothing meanwhile`() {
        val d = RefreshDue.next(at(40), lastStation = t0, lastFull = t0, consecutiveFailures = 3)
        assertNull(d.kind)
        assertEquals(Duration.ofMinutes(4), d.wait)
    }

    /**
     * One bad minute on a pass must not leave a working app refreshing half as often for the rest
     * of the day. The counter is reset by the caller on any success; this pins that the rule then
     * returns to the ordinary cadence in one step rather than easing back into it.
     */
    @Test
    fun `a success after failures returns to the ordinary cadence at once`() {
        val d = RefreshDue.next(at(40), lastStation = t0, lastFull = t0, consecutiveFailures = 0)
        assertEquals(RefreshKind.FULL, d.kind)
    }

    /**
     * A zero wait is a busy loop on somebody's phone, and the arithmetic that produces it — a
     * duration subtracted from itself at the exact boundary — is reachable rather than theoretical.
     */
    @Test
    fun `no wait is ever zero or negative`() {
        val instants = listOf<Instant?>(null, t0, at(5), at(10), at(29), at(30), at(31), at(600))
        for (station in instants) {
            for (full in instants) {
                for (failures in listOf(0, 1, 5, 40)) {
                    for (metered in listOf(false, true)) {
                        for (online in listOf(false, true)) {
                            val d = RefreshDue.next(at(30), station, full, failures, metered, online)
                            assertTrue(
                                "wait was ${d.wait} for station=$station full=$full failures=$failures",
                                !d.wait.isZero && !d.wait.isNegative,
                            )
                        }
                    }
                }
            }
        }
    }

    /** A clock that has gone backwards is not a reason to hammer an upstream. */
    @Test
    fun `a timestamp in the future is treated as just now`() {
        val d = RefreshDue.next(t0, lastStation = at(60), lastFull = at(60))
        assertNull(d.kind)
    }

    @Test
    fun `the loop is told to wake at the nearer of the two deadlines`() {
        // Five minutes after a full refresh: the station is due in five, the full one in twenty-five.
        val d = RefreshDue.next(at(5), lastStation = t0, lastFull = t0)
        assertNull(d.kind)
        assertEquals(Duration.ofMinutes(5), d.wait)
    }

    @Test
    fun `the full refresh uses the same staleness the resume hook does`() {
        // Not a coincidence to be re-derived: opening the app and sitting in it must not come to
        // different conclusions about what stale means.
        assertEquals(
            RefreshKind.FULL,
            RefreshDue.next(t0.plus(StaleRefresher.STALE_ON_OPEN), lastStation = t0, lastFull = t0).kind,
        )
    }
}
