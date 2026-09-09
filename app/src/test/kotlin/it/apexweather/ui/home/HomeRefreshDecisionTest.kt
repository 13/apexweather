package it.apexweather.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Opening the app used to refetch all seven models every single time, because the guard read the
 * holder's placeholder state — which reports no successful refresh — instead of the cached one.
 * The decision is pure now, so both sides of it can be pinned.
 */
class HomeRefreshDecisionTest {
    private val now: Instant = Instant.parse("2026-09-09T12:00:00Z")

    @Test
    fun `a cache that has never been filled is refreshed`() {
        assertTrue(HomeViewModel.shouldRefreshOnOpen(null, now))
    }

    @Test
    fun `a cache refreshed minutes ago is left alone`() {
        assertFalse(HomeViewModel.shouldRefreshOnOpen(now.minus(Duration.ofMinutes(5)), now))
    }

    @Test
    fun `a cache refreshed just inside the window is left alone`() {
        assertFalse(HomeViewModel.shouldRefreshOnOpen(now.minus(Duration.ofMinutes(30)), now))
    }

    @Test
    fun `a cache older than the window is refreshed`() {
        assertTrue(HomeViewModel.shouldRefreshOnOpen(now.minus(Duration.ofMinutes(31)), now))
    }
}
