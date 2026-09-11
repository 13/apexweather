package it.apexweather.ui.home

import it.apexweather.ui.StaleRefresher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Opening the app used to refetch all seven models every single time, because the guard read the
 * holder's placeholder state — which reports no successful refresh — instead of the cached one.
 * The decision is pure now, so both sides of it can be pinned.
 *
 * The rule moved to [StaleRefresher] when it stopped being the home screen's business: the app can
 * be left on any tab, and coming back to it has to fetch today's weather from any of them.
 */
class HomeRefreshDecisionTest {
    private val now: Instant = Instant.parse("2026-09-09T12:00:00Z")

    @Test
    fun `a cache that has never been filled is refreshed`() {
        assertTrue(StaleRefresher.shouldRefresh(null, now))
    }

    @Test
    fun `a cache refreshed minutes ago is left alone`() {
        assertFalse(StaleRefresher.shouldRefresh(now.minus(Duration.ofMinutes(5)), now))
    }

    @Test
    fun `a cache refreshed just inside the window is left alone`() {
        assertFalse(StaleRefresher.shouldRefresh(now.minus(Duration.ofMinutes(30)), now))
    }

    @Test
    fun `a cache older than the window is refreshed`() {
        assertTrue(StaleRefresher.shouldRefresh(now.minus(Duration.ofMinutes(31)), now))
    }
}
