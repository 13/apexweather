package it.apexweather.notify

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import it.apexweather.R
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Warning
import it.apexweather.domain.model.WarningLevel
import it.apexweather.domain.model.WarningType
import it.apexweather.ui.common.Formats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * The posting half of the notification feature, on a device.
 *
 * `NotificationDeciderTest` covers every rule about *whether* to post; nothing about that needs
 * Android. What does need a device is everything here: that the three channels are created and
 * survive a second call, that a notification actually reaches the shade, and that its text is the
 * app's own translated wording rather than the feed's English.
 */
class WeatherNotifierDeviceTest {

    /**
     * POST_NOTIFICATIONS is a runtime permission only from API 33. Below that — and minSdk here is
     * 31, which is what CI's emulator runs — the platform does not know the name, granting it throws,
     * and notifications are enabled by default anyway. So the rule is only chained in where it means
     * something.
     */
    @get:Rule val permission: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RuleChain.emptyRuleChain()
        }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = WeatherNotifier(context)
    private val formats = Formats(Locale.GERMAN, use24Hour = true)
    private val now: Instant = Instant.parse("2026-09-09T12:00:00Z")

    @Before fun setUp() {
        manager.cancelAll()
        awaitCount(0)
        notifier.ensureChannels()
    }

    @After fun tearDown() = manager.cancelAll()

    /**
     * notify() and cancelAll() hand the work to the system process, so the shade catches up a moment
     * later. Reading activeNotifications straight after a post is a race that passes most of the time
     * and fails on the run you were not watching.
     */
    private fun awaitCount(expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (manager.activeNotifications.size != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(expected, manager.activeNotifications.size)
    }

    @Test
    fun theThreeChannelsExistAndCreatingThemAgainIsHarmless() {
        notifier.ensureChannels()
        val ids = manager.notificationChannels.map { it.id }
        listOf(WeatherNotifier.CHANNEL_SUMMARY, WeatherNotifier.CHANNEL_RAIN, WeatherNotifier.CHANNEL_WARNING)
            .forEach { assertTrue("channel $it is missing", it in ids) }
    }

    @Test
    fun aSummaryReachesTheShade() {
        val posted = notifier.post(
            listOf(WeatherNotification.Summary("Dorf Tirol", LocalDate.of(2026, 9, 9), Condition.RAIN, 11.0, 21.0, 4.0)),
            formats, now,
        )
        assertEquals(1, posted.size)
        awaitCount(1)
        val shown = manager.activeNotifications.single()
        assertEquals(WeatherNotifier.CHANNEL_SUMMARY, shown.notification.channelId)
        // The place name is the same in every language, so this one may be quoted directly.
        val title = shown.notification.extras.getString("android.title").orEmpty()
        assertTrue("title was '$title'", title.contains("Dorf Tirol"))
    }

    /** The feed says "Orange Thunderstorm Warning"; the reader must never see that. */
    @Test
    fun aWarningIsPostedInTheReadersOwnLanguage() {
        val warning = Warning(
            identifier = "2.49.0.0.380.3.IT.260909114620.117",
            type = WarningType.THUNDERSTORM, level = WarningLevel.ORANGE,
            areaDesc = "Trentino Alto Adige",
            onset = now, expires = now.plusSeconds(6 * 3600),
            headline = "Orange Thunderstorm Warning",
        )
        notifier.post(listOf(WeatherNotification.Severe(warning)), formats, now)
        awaitCount(1)
        val shown = manager.activeNotifications.single()
        assertEquals(WeatherNotifier.CHANNEL_WARNING, shown.notification.channelId)
        assertEquals(WeatherNotifier.warningId(warning.identifier), shown.id)
        // Against the resources, not against German: CI runs an en-US emulator, and a test that
        // hardcodes one language only checks that the developer's phone is set to it.
        val title = shown.notification.extras.getString("android.title").orEmpty()
        assertTrue("title was '$title'", title.contains(context.getString(R.string.warn_thunderstorm)))
        assertTrue("title was '$title'", title.contains(context.getString(R.string.warn_level_orange)))
    }

    /** Re-posting the same warning replaces its notification rather than stacking a second copy. */
    @Test
    fun theSameWarningDoesNotStackUp() {
        val warning = Warning(
            identifier = "same-id", type = WarningType.WIND, level = WarningLevel.RED,
            areaDesc = "Trentino Alto Adige", onset = now, expires = now.plusSeconds(3600),
            headline = "Red Wind Warning",
        )
        repeat(3) { notifier.post(listOf(WeatherNotification.Severe(warning)), formats, now) }
        awaitCount(1)
    }

    @Test
    fun nothingToPostPostsNothing() {
        assertTrue(notifier.post(emptyList(), formats, now).isEmpty())
        awaitCount(0)
    }
}
