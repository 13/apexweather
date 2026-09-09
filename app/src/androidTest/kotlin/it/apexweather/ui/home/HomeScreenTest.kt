package it.apexweather.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.theme.ApexTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    private fun fc(source: Source, offset: Double) = SourceForecast(
        source, t0, t0,
        hourly = (0 until 168).map { HourlyPoint(t0.plusSeconds(it * 3600L), 15.0 + offset + it % 8, precipMm = if (it % 5 == 0) 1.0 else 0.0, windKmh = 6.0, condition = Condition.PARTLY_CLOUDY) },
        daily = emptyList(),
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = mapOf(Source.ICON_CH1 to fc(Source.ICON_CH1, 0.0), Source.ICON_D2 to fc(Source.ICON_D2, 2.0)))
    private val state = HomeStateBuilder.build(snapshot, AppSettings(), ConsensusBlender().blend(snapshot.forecasts), t0.plusSeconds(60))

    @Test
    fun heroShowsConsensusTemperature() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("hero_temp").assertIsDisplayed().assertTextContains("16°")
        rule.onNodeWithTag("agreement_badge").assertIsDisplayed()
    }

    @Test
    fun hourlyStripScrollsAndOpensDetailSheet() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("hourly_strip").performTouchInput { swipeLeft() }
        // the swipe really scrolled the strip: the first column is off-screen now
        rule.onNodeWithTag("hour_column_0").assertIsNotDisplayed()
        rule.onNodeWithTag("hour_column_0").performScrollTo().performClick()
        rule.onNodeWithTag("hour_detail_sheet").assertIsDisplayed()
    }

    /** A day late in the week is the interesting one: its hours are outside the 48-hour strip. */
    @Test
    fun tappingADayOpensTheDaySheetWithItsHoursAndSources() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("day_row_4").performScrollTo().performClick()
        rule.onNodeWithTag("day_detail_sheet").assertIsDisplayed()
        rule.onNodeWithTag("day_hour_strip").assertIsDisplayed()
        rule.onNodeWithTag("day_hour_column_0").assertIsDisplayed()
    }

    @Test
    fun offlineBannerShowsLastUpdateTime() {
        rule.setContent { ApexTheme { HomeContent(state.copy(offline = true, updatedAt = t0), onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("offline_banner").assertIsDisplayed()
    }

    @Test
    fun emptyStateShowsRetry() {
        rule.setContent { ApexTheme { HomeContent(HomeUiState(loading = false, isEmpty = true), onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("empty_state").assertIsDisplayed()
    }
}
