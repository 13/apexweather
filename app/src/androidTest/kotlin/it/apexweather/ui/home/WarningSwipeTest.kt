package it.apexweather.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import it.apexweather.domain.model.Warning
import it.apexweather.domain.model.WarningLevel
import it.apexweather.domain.model.WarningType
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Swiping the warning card away, one warning after another.
 *
 * The second dismissal is the one that matters. The swipe state used to be remembered outside the
 * key that identifies the warning, so it survived into whichever warning took the card next: the
 * first swipe worked, and every one after it did nothing, because the state was already sitting at
 * a dismissed value it could not move to again. Seen on the phone before this test existed.
 */
class WarningSwipeTest {
    @get:Rule val rule = createComposeRule()

    private val now: Instant = Instant.parse("2026-09-10T12:00:00Z")

    private fun warning(id: String) = Warning(
        identifier = id, type = WarningType.THUNDERSTORM, level = WarningLevel.ORANGE,
        areaDesc = "Trentino Alto Adige", onset = now, expires = now.plusSeconds(6 * 3600),
        headline = "Orange Thunderstorm Warning",
    )

    /** Drives the card the way the home screen does: dismissing removes it and the next takes over. */
    private fun show(onDismissed: (String) -> Unit) = rule.setContent {
        ApexTheme {
            var warnings by remember { mutableStateOf(listOf(warning("a"), warning("b"), warning("c"))) }
            WarningSection(
                warnings = warnings,
                now = now,
                onDismiss = { w -> onDismissed(w.identifier); warnings = warnings - w },
                onClick = {},
            )
        }
    }

    @Test
    fun eachWarningInTurnCanBeSwipedAway() {
        val dismissed = mutableListOf<String>()
        show { dismissed += it }

        repeat(3) {
            rule.onNodeWithTag("warning_card").performTouchInput { swipeLeft() }
            rule.waitForIdle()
        }
        assertEquals(listOf("a", "b", "c"), dismissed)
    }

    /** Both directions dismiss; the card offers no different meaning to either. */
    @Test
    fun swipingRightDismissesTheSameWay() {
        val dismissed = mutableListOf<String>()
        show { dismissed += it }

        rule.onNodeWithTag("warning_card").performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("warning_card").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(listOf("a", "b"), dismissed)
    }
}
