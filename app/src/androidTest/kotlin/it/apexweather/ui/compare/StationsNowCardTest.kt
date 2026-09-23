package it.apexweather.ui.compare

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class StationsNowCardTest {
    @get:Rule val rule = createComposeRule()

    private val now: Instant = Instant.parse("2026-09-23T11:00:00Z")

    private fun column(
        code: String,
        temp: Double? = 11.0,
        radiation: Double? = 148.0,
        chosen: Boolean = false,
        quiet: Boolean = false,
    ) = StationColumn(
        code = code, name = code, distanceKm = 0.5,
        tempC = temp, humidityPct = 58, precipTodayMm = 0.0, radiationWm2 = radiation,
        readAt = now.minusSeconds(120), chosen = chosen, quiet = quiet,
    )

    private fun show(state: StationsNowUiState, onOpenStations: () -> Unit = {}) = rule.setContent {
        ApexTheme { StationsNowCard(state, onOpenStations = onOpenStations, now = now) }
    }

    @Test
    fun everyStationGetsAColumn() {
        show(StationsNowUiState(columns = listOf(column("ITIROL16"), column("ITIROL26")), modelsTempC = 12.4))
        rule.onNodeWithTag("stations_now").assertIsDisplayed()
        rule.onNodeWithText("ITIROL16").assertIsDisplayed()
        rule.onNodeWithText("ITIROL26").assertIsDisplayed()
    }

    /** The one anchor: what the models say here, never the hero. */
    @Test
    fun theModelsRowIsShown() {
        show(StationsNowUiState(columns = listOf(column("ITIROL16")), modelsTempC = 12.4))
        rule.onNodeWithTag("stations_now_models").assertIsDisplayed()
    }

    /** Nothing to compare, nothing to draw — an empty card is worse than no card. */
    @Test
    fun withNoStationsThereIsNoCard() {
        show(StationsNowUiState(columns = emptyList(), modelsTempC = 12.4))
        rule.onNodeWithTag("stations_now").assertDoesNotExist()
    }

    /**
     * A station with no pyranometer draws a dash, not a zero: a zero is a measurement nobody took.
     */
    @Test
    fun aQuantityAStationDoesNotPublishIsADash() {
        show(StationsNowUiState(columns = listOf(column("ITIROL16", radiation = null)), modelsTempC = 12.4))
        rule.onAllNodesWithText("—").fetchSemanticsNodes().let {
            assertTrue("no dash was drawn for the missing radiation", it.isNotEmpty())
        }
    }

    /** The card is a way in as well as a read-out. */
    @Test
    fun tappingTheCardOpensTheStationsScreen() {
        var opened = false
        show(StationsNowUiState(columns = listOf(column("ITIROL16")), modelsTempC = 12.4)) { opened = true }
        rule.onNodeWithTag("stations_now").performClick()
        assertTrue("the card did not report being tapped", opened)
    }
}
