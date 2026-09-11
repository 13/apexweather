package it.apexweather.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import it.apexweather.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class NavigationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() {
        hilt.inject()
        rule.mainClock.autoAdvance = false // the sky's frame loop never idles
    }

    private fun settle() = rule.mainClock.advanceTimeBy(1_000)

    /**
     * The sky's frame loop means the clock is driven by hand, so waitForIdle would never return and
     * advancing the test clock does not make the real cache read finish. Poll both.
     */
    private fun awaitTag(tag: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            rule.mainClock.advanceTimeBy(200)
            // Early on there is no composition to query at all, which throws rather than returning
            // an empty list, and the previous test's activity can still be tearing down. Both are
            // "not yet", not a failure, so require a resumed activity and a node in it.
            val found = runCatching {
                rule.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
            if (found) return
            Thread.sleep(100)
        }
        throw AssertionError("'$tag' never appeared")
    }

    @Test
    fun bottomBarSwitchesScreensAndSettingsOpens() {
        settle()
        rule.onNodeWithTag("nav_compare").performClick(); settle()
        rule.onNodeWithTag("compare_chart").assertIsDisplayed()
        rule.onNodeWithTag("nav_bulletin").performClick(); settle()
        val hasText = rule.onAllNodesWithTag("bulletin_text").fetchSemanticsNodes().isNotEmpty()
        val hasEmpty = rule.onAllNodesWithTag("bulletin_empty").fetchSemanticsNodes().isNotEmpty()
        assertTrue("bulletin tab rendered neither text nor empty state", hasText || hasEmpty)
        rule.onNodeWithTag("nav_home").performClick(); settle()
        awaitTag("hero_temp")
        // Settings is a destination now, not a sheet: it can be left the way every other tab is.
        rule.onNodeWithTag("settings_button").performClick(); settle()
        rule.onNodeWithTag("settings_screen").assertIsDisplayed()
        rule.onNodeWithTag("nav_home").performClick(); settle()
        awaitTag("hero_temp")
    }

}
