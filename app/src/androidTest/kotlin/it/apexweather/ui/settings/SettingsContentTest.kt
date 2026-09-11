package it.apexweather.ui.settings

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import it.apexweather.BuildConfig
import it.apexweather.data.AppSettings
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsContentTest {
    @get:Rule val rule = createComposeRule()

    private fun show(
        settings: AppSettings = AppSettings(),
        notificationsAllowed: Boolean = true,
        onNotifySummary: (Boolean) -> Unit = {},
        onNotifySummaryHour: (Int) -> Unit = {},
    ) = rule.setContent {
        ApexTheme {
            SettingsContent(
                settings = settings,
                onLanguage = {}, onWindUnit = {}, onAnimations = {}, onRefresh = {},
                notificationsAllowed = notificationsAllowed,
                onNotifySummary = onNotifySummary,
                onNotifySummaryHour = onNotifySummaryHour,
            )
        }
    }

    private fun textUnder(node: SemanticsNode): String =
        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }) +
            node.children.joinToString(" ") { textUnder(it) }

    /** The build line is what a bug report quotes, so it has to name this exact build. */
    @Test
    fun theBuildLineNamesTheVersionAndTheCommitItCameFrom() {
        show()
        // The sheet has grown past a small screen and scrolls now; the build line is the last thing
        // on it, so it has to be scrolled to. Reproduced at 720x1280.
        rule.onNodeWithTag("about_build").performScrollTo().assertIsDisplayed()
        val line = textUnder(rule.onNodeWithTag("about_build").fetchSemanticsNode())

        listOf(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toString(),
            BuildConfig.BUILD_TYPE,
            BuildConfig.GIT_HASH,
            BuildConfig.GIT_DATE,
        ).forEach { assertTrue("build line is missing '$it': $line", line.contains(it)) }
    }

    /** Every notification is off until it is asked for, and the sheet is where that happens. */
    @Test
    fun theThreeNotificationSwitchesAreOffByDefault() {
        show()
        listOf("notify_summary", "notify_rain", "notify_warning").forEach {
            rule.onNodeWithTag(it).performScrollTo().assertIsDisplayed().assertIsOff()
        }
    }

    @Test
    fun switchingOnTheSummaryAsksForIt() {
        var asked: Boolean? = null
        show(onNotifySummary = { asked = it })
        rule.onNodeWithTag("notify_summary").performScrollTo().performClick()
        assertTrue("the switch did not report being turned on", asked == true)
    }

    /** The hour only appears once the summary is on; there is nothing to schedule otherwise. */
    @Test
    fun theHourIsOfferedOnlyWhileTheSummaryIsOn() {
        show()
        rule.onNodeWithTag("notify_summary_hour").assertDoesNotExist()
    }

    /** Midnight steps down to the late evening rather than sticking. */
    @Test
    fun theHourWrapsRoundTheClock() {
        var chosen: Int? = null
        show(settings = AppSettings(notifySummary = true, notifySummaryHour = 0), onNotifySummaryHour = { chosen = it })
        rule.onNodeWithTag("notify_hour_down").performScrollTo().performClick()
        assertEquals(23, chosen)
    }

    /**
     * Android's permission is a second gate, and the hint about it only makes sense once the reader
     * has actually asked for a notification.
     */
    @Test
    fun thePermissionHintWaitsUntilSomethingIsSwitchedOn() {
        show(notificationsAllowed = false)
        rule.onNodeWithTag("notify_permission_hint").assertDoesNotExist()
    }

    @Test
    fun thePermissionHintAppearsOnceSomethingIsSwitchedOn() {
        show(settings = AppSettings(notifyWarnings = true), notificationsAllowed = false)
        rule.onNodeWithTag("notify_grant").performScrollTo().assertIsDisplayed()
    }
}
