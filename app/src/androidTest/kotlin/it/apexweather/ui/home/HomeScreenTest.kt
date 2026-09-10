package it.apexweather.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertContentDescriptionContains
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    /** Assertions about wording go through the resources: CI runs an en-US emulator, not a German phone. */
    private val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    private fun fc(source: Source, offset: Double) = SourceForecast(
        source, t0, t0,
        hourly = (0 until 168).map { HourlyPoint(t0.plusSeconds(it * 3600L), 15.0 + offset + it % 8, precipMm = if (it % 5 == 0) 1.0 else 0.0, windKmh = 6.0, condition = Condition.PARTLY_CLOUDY) },
        daily = emptyList(),
    )
    private val dorfTirol = it.apexweather.domain.Place(
        istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
        lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
        station = it.apexweather.domain.NearbyStation("23200MS", "Meran", 46.688, 11.1366, 330, 1.53),
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = mapOf(Source.ICON_CH1 to fc(Source.ICON_CH1, 0.0), Source.ICON_D2 to fc(Source.ICON_D2, 2.0)))
    private val state = HomeStateBuilder.build(dorfTirol, snapshot, AppSettings(), ConsensusBlender().blend(snapshot.forecasts), t0.plusSeconds(60))

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

    private fun warning(id: String, level: it.apexweather.domain.model.WarningLevel) =
        it.apexweather.domain.model.Warning(
            identifier = id,
            type = it.apexweather.domain.model.WarningType.THUNDERSTORM,
            level = level,
            areaDesc = "Trentino Alto Adige",
            onset = t0.minusSeconds(3600),
            expires = t0.plusSeconds(6 * 3600),
            headline = "Orange Thunderstorm Warning",
        )

    /** A warning nobody has to scroll to is the whole point of the card. */
    @Test
    fun aWarningInForceSitsAboveTheHero() {
        val warned = state.copy(warnings = listOf(warning("a", it.apexweather.domain.model.WarningLevel.ORANGE)))
        rule.setContent { ApexTheme { HomeContent(warned, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("warning_card").assertIsDisplayed()
        rule.onNodeWithTag("hero_temp").assertIsDisplayed()
    }

    @Test
    fun theCardCountsTheOtherWarningsAndOpensTheList() {
        val warned = state.copy(
            warnings = listOf(
                warning("a", it.apexweather.domain.model.WarningLevel.RED),
                warning("b", it.apexweather.domain.model.WarningLevel.ORANGE),
                warning("c", it.apexweather.domain.model.WarningLevel.YELLOW),
            ),
        )
        rule.setContent { ApexTheme { HomeContent(warned, onRefresh = {}, onOpenBulletin = {}) } }
        // The card merges its children into one node, so the count is reached unmerged — and is
        // part of what the card says out loud, which is the only way a screen reader learns of it.
        rule.onNodeWithTag("warning_more", useUnmergedTree = true).assertIsDisplayed()
        val more = context.resources.getQuantityString(it.apexweather.R.plurals.warn_more, 2, 2)
        rule.onNodeWithTag("warning_card").assertContentDescriptionContains(more, substring = true)
        rule.onNodeWithTag("warning_card").performClick()
        rule.onNodeWithTag("warning_sheet").assertIsDisplayed()
        // The sheet opens half height; the last warning is below the fold until it is scrolled to.
        rule.onNodeWithTag("warning_row_2").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun nothingInForceMeansNoCard() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("warning_card").assertDoesNotExist()
    }

    /**
     * The station reading is a measurement, not a forecast, and the card is the only place the app
     * shows what was actually measured — wind, gusts, humidity and pressure appear nowhere else.
     */
    @Test
    fun theStationCardShowsWhatWasMeasured() {
        val observed = state.copy(
            station = it.apexweather.domain.model.StationObservation(
                stationName = "Meran", time = t0, tempC = 18.8, humidityPct = 100, windKmh = 13.0,
                windDir = "SW", gustKmh = 35.0, precipTodayMm = 1.2, pressureHpa = 1013.0,
            ),
        )
        rule.setContent { ApexTheme { HomeContent(observed, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("station_card").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("station_measured_at").assertExists()
    }

    @Test
    fun noStationReadingMeansNoStationCard() {
        rule.setContent { ApexTheme { HomeContent(state.copy(station = null), onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("station_card").assertDoesNotExist()
    }

    /** A warning waved away stops shouting on the home screen. */
    @Test
    fun aDismissedWarningLeavesTheCard() {
        val w = warning("a", it.apexweather.domain.model.WarningLevel.ORANGE)
        val warned = state.copy(
            warnings = listOf(w),
            dismissedWarnings = setOf(it.apexweather.data.WarningDismissals.key(w)),
        )
        rule.setContent { ApexTheme { HomeContent(warned, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("warning_card").assertDoesNotExist()
    }

    /** But it is still in the sheet, dimmed, with a way back — dismissing is not deleting. */
    @Test
    fun aDismissedWarningIsStillListedAndCanBeRestored() {
        val w = warning("a", it.apexweather.domain.model.WarningLevel.ORANGE)
        var restored: it.apexweather.domain.model.Warning? = null
        val warned = state.copy(
            warnings = listOf(w, warning("b", it.apexweather.domain.model.WarningLevel.YELLOW)),
            dismissedWarnings = setOf(it.apexweather.data.WarningDismissals.key(w)),
        )
        rule.setContent {
            ApexTheme {
                HomeContent(warned, onRefresh = {}, onOpenBulletin = {}, onRestoreWarning = { restored = it })
            }
        }
        // The undismissed one still has a card; opening it shows both.
        rule.onNodeWithTag("warning_card").performClick()
        rule.onNodeWithTag("warning_row_0").assertIsDisplayed()
        rule.onNodeWithTag("warning_restore_0").performScrollTo().performClick()
        assertEquals("a", restored?.identifier)
    }

    @Test
    fun dismissingFromTheSheetReportsTheWarning() {
        var dismissed: it.apexweather.domain.model.Warning? = null
        val warned = state.copy(warnings = listOf(warning("a", it.apexweather.domain.model.WarningLevel.ORANGE)))
        rule.setContent {
            ApexTheme {
                HomeContent(warned, onRefresh = {}, onOpenBulletin = {}, onDismissWarning = { dismissed = it })
            }
        }
        rule.onNodeWithTag("warning_card").performClick()
        rule.onNodeWithTag("warning_dismiss_0").performScrollTo().performClick()
        assertEquals("a", dismissed?.identifier)
    }
}
