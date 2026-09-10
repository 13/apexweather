package it.apexweather.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
            composable<CompareRoute> { Text("compare", Modifier.testTag("screen_compare")) }
            composable<BulletinRoute> { Text("bulletin", Modifier.testTag("screen_bulletin")) }
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
        listOf(CompareRoute, MapRoute, BulletinRoute, HomeRoute, MapRoute, BulletinRoute, CompareRoute, HomeRoute).forEach { route ->
            open(route)
            val tag = when (route) {
                HomeRoute -> "screen_home"
                CompareRoute -> "screen_compare"
                MapRoute -> "screen_map"
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
}
