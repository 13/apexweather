package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import it.apexweather.domain.model.Warning
import it.apexweather.domain.model.WarningLevel
import it.apexweather.domain.model.WarningType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WarningDismissalsTest {

    private val store = WarningDismissals(ApplicationProvider.getApplicationContext())
    private val now: Instant = Instant.parse("2026-09-10T12:00:00Z")

    private fun warning(id: String, level: WarningLevel = WarningLevel.ORANGE) = Warning(
        identifier = id, type = WarningType.THUNDERSTORM, level = level, areaDesc = "Trentino Alto Adige",
        onset = now, expires = now.plusSeconds(6 * 3600), headline = "Orange Thunderstorm Warning",
    )

    /** The store is shared between test methods under Robolectric, so each one starts by clearing it. */
    @Before fun reset() = runTest { store.prune(emptyList()) }

    @Test
    fun `a dismissed warning is remembered and can be restored`() = runTest {
        val w = warning("a")
        store.dismiss(w)
        assertTrue(WarningDismissals.key(w) in store.dismissed.first())
        store.restore(w)
        assertFalse(WarningDismissals.key(w) in store.dismissed.first())
    }

    /** Otherwise the set grows for ever and yesterday's silence outlives the warning it was about. */
    @Test
    fun `pruning forgets warnings that are no longer in force`() = runTest {
        store.dismiss(warning("gone"))
        store.dismiss(warning("still-here"))
        store.prune(listOf(warning("still-here")))
        assertEquals(setOf(WarningDismissals.key(warning("still-here"))), store.dismissed.first())
    }

    /**
     * The feed does not change a warning's level in place today — an upgrade arrives as its own
     * entry — but if it ever did, the upgrade must not inherit the silence of the yellow one.
     */
    @Test
    fun `an upgraded warning is not covered by the dismissal of the milder one`() = runTest {
        store.dismiss(warning("a", WarningLevel.YELLOW))
        val dismissed = store.dismissed.first()
        assertTrue(WarningDismissals.key(warning("a", WarningLevel.YELLOW)) in dismissed)
        assertFalse(WarningDismissals.key(warning("a", WarningLevel.RED)) in dismissed)
    }

    @Test
    fun `dismissing twice is the same as dismissing once`() = runTest {
        store.dismiss(warning("a"))
        store.dismiss(warning("a"))
        assertEquals(1, store.dismissed.first().size)
    }
}
