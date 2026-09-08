package it.apexweather.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import it.apexweather.MainActivity
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

    @Test
    fun bottomBarSwitchesScreensAndSettingsOpens() {
        settle()
        rule.onNodeWithTag("nav_compare").performClick(); settle()
        rule.onNodeWithTag("compare_chart").assertIsDisplayed()
        rule.onNodeWithTag("nav_bulletin").performClick(); settle()
        rule.onNodeWithTag("nav_home").performClick(); settle()
        rule.onNodeWithTag("settings_button").performClick(); settle()
        rule.onNodeWithTag("settings_sheet").assertIsDisplayed()
    }
}
