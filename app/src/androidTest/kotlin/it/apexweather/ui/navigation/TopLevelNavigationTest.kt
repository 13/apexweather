package it.apexweather.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import it.apexweather.domain.model.Source
import org.junit.Rule
import org.junit.Test

/**
 * The tab bar's contract, tested against the real routes and a stub screen each, so it does not
 * depend on the weather cache or on the sky's never-idle frame loop.
 *
 * The bulletin has two entrances: its tab, and the teaser card on the home screen. The card used to
 * push it with a plain navigate while the tabs used popUpTo with saved state, and after the card's
 * version of the push the home tab could not bring itself back.
 */
class TopLevelNavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController

    private fun show() = rule.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = HomeRoute) {
            composable<HomeRoute> { Text("home", Modifier.testTag("screen_home")) }
            composable<MapRoute> { Text("map", Modifier.testTag("screen_map")) }
            composable<CompareRoute> { entry ->
                // The same receiver the real comparison screen uses.
                var received by remember { mutableStateOf("") }
                ReceiveSourceHandoff(entry) { received = it.name }
                Text("compare", Modifier.testTag("screen_compare"))
                Text(received, Modifier.testTag("compare_received"))
            }
            composable<StatsRoute> { Text("stats", Modifier.testTag("screen_stats")) }
            composable<BulletinRoute> { Text("bulletin", Modifier.testTag("screen_bulletin")) }
            composable<SettingsRoute> { Text("settings", Modifier.testTag("screen_settings")) }
        }
    }

    private fun open(route: Any) = rule.runOnIdle { nav.openTopLevel(route) }

    @Test
    fun theHomeTabReturnsAfterTheBulletinIsOpenedFromTheHomeScreen() {
        show()
        rule.onNodeWithTag("screen_home").assertIsDisplayed()
        open(BulletinRoute)
        rule.onNodeWithTag("screen_bulletin").assertIsDisplayed()
        open(HomeRoute)
        rule.onNodeWithTag("screen_home").assertIsDisplayed()
    }

    @Test
    fun everyTabReachesEveryOtherTab() {
        show()
        listOf(CompareRoute, MapRoute, SettingsRoute, BulletinRoute, HomeRoute, MapRoute, SettingsRoute, BulletinRoute, CompareRoute, HomeRoute).forEach { route ->
            open(route)
            val tag = when (route) {
                HomeRoute -> "screen_home"
                CompareRoute -> "screen_compare"
                MapRoute -> "screen_map"
                SettingsRoute -> "screen_settings"
                else -> "screen_bulletin"
            }
            rule.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    /** The map is a tab like the others: leaving it must not strand the home tab. */
    @Test
    fun theHomeTabReturnsAfterTheMap() {
        show()
        open(MapRoute)
        rule.onNodeWithTag("screen_map").assertIsDisplayed()
        open(HomeRoute)
        rule.onNodeWithTag("screen_home").assertIsDisplayed()
    }

    /** Pressing the tab you are already on must not push a second copy or land somewhere else. */
    @Test
    fun openingTheTabYouAreAlreadyOnStaysPut() {
        show()
        open(BulletinRoute)
        open(BulletinRoute)
        rule.onNodeWithTag("screen_bulletin").assertIsDisplayed()
        open(HomeRoute)
        rule.onNodeWithTag("screen_home").assertIsDisplayed()
    }

    /**
     * "Details zur Quelle" leaves the source on the comparison entry and pops back to it. What
     * matters is that the comparison screen actually receives it: it used to be written to the
     * entry's handle and read from the view model's own, which is a different object.
     */
    @Test
    fun theStatisticsScreenHandsASourceBackToTheComparison() {
        show()
        open(CompareRoute)
        rule.runOnIdle { nav.navigate(StatsRoute) }
        rule.onNodeWithTag("screen_stats").assertIsDisplayed()
        rule.runOnIdle { nav.handSourceToCompare(Source.ICON_D2) }
        rule.onNodeWithTag("screen_compare").assertIsDisplayed()
        rule.onNodeWithTag("compare_received").assertTextEquals(Source.ICON_D2.name)
    }

    /** A double tap on "Details zur Quelle" must not pop the comparison screen as well. */
    @Test
    fun aDoubleTapOnTheHandoffPopsOnce() {
        show()
        open(CompareRoute)
        rule.runOnIdle { nav.navigate(StatsRoute) }
        rule.runOnIdle {
            nav.handSourceToCompare(Source.ICON_D2)
            nav.handSourceToCompare(Source.ICON_D2)
        }
        rule.onNodeWithTag("screen_compare").assertIsDisplayed()
    }

    /** The comparison tab is selected while the statistics show; tapping it must lead back. */
    @Test
    fun theComparisonTabLeadsBackFromTheStatistics() {
        show()
        open(CompareRoute)
        rule.runOnIdle { nav.navigate(StatsRoute) }
        rule.onNodeWithTag("screen_stats").assertIsDisplayed()
        rule.runOnIdle { nav.selectTab(CompareRoute) }
        rule.onNodeWithTag("screen_compare").assertIsDisplayed()
    }
}
