package it.apexweather.ui.settings

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import it.apexweather.BuildConfig
import it.apexweather.data.AppSettings
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import it.apexweather.R
import it.apexweather.data.WuKeyVerdict
import androidx.test.platform.app.InstrumentationRegistry

class SettingsContentTest {
    @get:Rule val rule = createComposeRule()

    private fun show(
        settings: AppSettings = AppSettings(),
        notificationsAllowed: Boolean = true,
        onNotifySummary: (Boolean) -> Unit = {},
        onNotifySummaryHour: (Int) -> Unit = {},
        onWuApiKey: (String) -> Unit = {},
        onRefresh: () -> Unit = {},
        onOpenStations: (() -> Unit)? = {},
    ) = rule.setContent {
        ApexTheme {
            SettingsContent(
                settings = settings,
                onLanguage = {}, onWindUnit = {}, onAnimations = {}, onAmateurStations = {},
                onWuApiKey = onWuApiKey, onRefresh = onRefresh,
                onOpenStations = onOpenStations,
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

    @Test
    fun theKeyFieldIsShownWhenPrivateStationsAreOn() {
        show(AppSettings(amateurStations = true))
        rule.onNodeWithTag("setting_wu_key").assertIsDisplayed()
    }

    /** With the feature off the field is noise: there is nothing for a key to do. */
    @Test
    fun theKeyFieldIsHiddenWhenPrivateStationsAreOff() {
        show(AppSettings(amateurStations = false))
        rule.onNodeWithTag("setting_wu_key").assertDoesNotExist()
    }

    /**
     * The failure this pins is not "the callback is wrong" but "the field forgot the last
     * character". One `performTextInput` is a single commit and passes either way; two in a row,
     * against a state that has not come back yet, is what the store's round trip actually looks
     * like on a phone, and against a field reading `settings.wuApiKey` the second input is
     * committed onto an empty value and the first two characters are simply gone.
     */
    @Test
    fun theFieldKeepsWhatWasTypedWhileTheStoreIsStillCatchingUp() {
        val sent = mutableListOf<String>()
        show(AppSettings(amateurStations = true), onWuApiKey = { sent += it })
        rule.onNodeWithTag("setting_wu_key").performTextInput("ab")
        rule.onNodeWithTag("setting_wu_key").performTextInput("cd")
        assertEquals("abcd", sent.last())
        rule.onNodeWithTag("setting_wu_key").assertTextContains("abcd")
    }

    /** Nothing is claimed about a key nothing has tried. */
    @Test
    fun anUncheckedKeySaysNothing() {
        show(AppSettings(amateurStations = true, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.UNCHECKED))
        rule.onNodeWithTag("wu_key_verdict").assertDoesNotExist()
    }

    @Test
    fun aRefusedKeySaysSo() {
        show(AppSettings(amateurStations = true, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.REFUSED))
        rule.onNodeWithTag("wu_key_verdict").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anExhaustedQuotaSaysSo() {
        show(AppSettings(amateurStations = true, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.OVER_QUOTA))
        rule.onNodeWithTag("wu_key_verdict").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aConnectionFailureSaysSo() {
        show(AppSettings(amateurStations = true, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.OFFLINE))
        rule.onNodeWithTag("wu_key_verdict").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aGoodKeySaysSo() {
        show(
            AppSettings(
                amateurStations = true, wuApiKey = "k",
                wuKeyVerdict = WuKeyVerdict.GOOD, wuKeyCheckedAtMs = 1790146074000L,
            ),
        )
        rule.onNodeWithTag("wu_key_verdict").performScrollTo().assertIsDisplayed()
    }

    /** With the feature off there is no key field and therefore no verdict under it. */
    @Test
    fun noVerdictIsShownWhilePrivateStationsAreOff() {
        show(AppSettings(amateurStations = false, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.GOOD))
        rule.onNodeWithTag("wu_key_verdict").assertDoesNotExist()
    }

    /**
     * Every state but UNCHECKED needs its own sentence. A reader whose quota is gone must not be
     * sent off to re-type a key that is perfectly good, which is what one shared "funktioniert
     * nicht" would do. This renders nothing; it fails the day a seventh verdict is added and left
     * reading like another one.
     */
    @Test
    fun everyVerdictButUncheckedHasItsOwnWording() {
        val shown = WuKeyVerdict.entries.filterNot { it == WuKeyVerdict.UNCHECKED }
        val strings = listOf(
            R.string.wu_key_checking, R.string.wu_key_good, R.string.wu_key_refused,
            R.string.wu_key_over_quota, R.string.wu_key_offline,
        )
        assertEquals(shown.size, strings.size)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val texts = strings.map { context.getString(it, "08:47") }
        assertEquals("two verdicts read the same: $texts", texts.size, texts.toSet().size)
    }

    @Test
    fun typingAKeyReachesTheCallback() {
        var typed: String? = null
        show(AppSettings(amateurStations = true), onWuApiKey = { typed = it })
        rule.onNodeWithTag("setting_wu_key").performTextInput("abc123")
        assertEquals("abc123", typed)
    }

    /** Five groups, and the reader should be able to see which is which. */
    @Test
    fun theGroupHeadingsAreOnScreen() {
        show()
        listOf("group_place", "group_display", "group_stations", "notification_settings", "group_app")
            .forEach { rule.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
    }

    /**
     * The way to the stations screen, where somebody goes looking for it. It is otherwise reachable
     * only from the station card four scrolls down the home screen.
     */
    @Test
    fun theStationsRowIsOfferedWhenThereIsSomewhereToGo() {
        var opened = false
        show(AppSettings(amateurStations = true, wuApiKey = "k"), onOpenStations = { opened = true })
        rule.onNodeWithTag("settings_nearby_stations").performScrollTo().performClick()
        assertTrue("the stations row did not report", opened)
    }

    /** No key, no neighbourhood to list, so no row promising one. */
    @Test
    fun theStationsRowIsAbsentWithoutSomewhereToGo() {
        show(AppSettings(amateurStations = true, wuApiKey = "k"), onOpenStations = null)
        rule.onNodeWithTag("settings_nearby_stations").assertDoesNotExist()
    }

    /** Refreshing is a row in the App group now, not a filled button in the middle of the page. */
    @Test
    fun refreshingIsStillReachable() {
        var refreshed = false
        show(onRefresh = { refreshed = true })
        rule.onNodeWithTag("refresh_now").performScrollTo().performClick()
        assertTrue("the refresh row did not report", refreshed)
    }
}
