package it.apexweather.ui.stations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class NearbyStationsContentTest {
    @get:Rule val rule = createComposeRule()

    private val now: Instant = Instant.parse("2026-09-23T06:50:00Z")

    private fun row(code: String, chosen: Boolean = false, selectable: Boolean = true) = StationRow(
        code = code, name = code, distanceKm = 0.5, lat = 46.69, lon = 11.15,
        claimedAltitudeM = 634, demAltitudeM = 639, reading = null,
        chosen = chosen, selectable = selectable,
    )

    private fun show(
        state: NearbyStationsUiState,
        onBack: () -> Unit = {},
        onChoose: (StationRow) -> Unit = {},
    ) = rule.setContent {
        ApexTheme { NearbyStationsContent(state, onBack = onBack, onChoose = onChoose, now = now) }
    }

    @Test
    fun theBackArrowReportsIt() {
        var backed = false
        show(NearbyStationsUiState(loading = false, rows = listOf(row("ITIROL16"))), onBack = { backed = true })
        rule.onNodeWithTag("stations_back").performClick()
        assertTrue("the back arrow did not report", backed)
    }

    @Test
    fun choosingACardReportsThatStationAndNoOther() {
        var picked: String? = null
        show(
            NearbyStationsUiState(loading = false, rows = listOf(row("ITIROL16"), row("ITIROL26"))),
            onChoose = { picked = it.code },
        )
        rule.onNodeWithTag("station_choose_ITIROL26").performClick()
        assertEquals("ITIROL26", picked)
    }

    /** The one already in use is not offered as something to pick. */
    @Test
    fun theChosenCardOffersNoAction() {
        show(NearbyStationsUiState(loading = false, rows = listOf(row("ITIROL16", chosen = true, selectable = false))))
        rule.onNodeWithTag("station_ITIROL16").assertIsDisplayed()
        rule.onNodeWithTag("station_choose_ITIROL16").assertDoesNotExist()
        rule.onNodeWithTag("station_chosen_ITIROL16").assertIsDisplayed()
    }

    /** A chosen amateur station has no skyline, and the card says so rather than leaving it to a
     * December afternoon when the sun goes behind a ridge the app does not know about. */
    @Test
    fun aChosenAmateurStationSaysItHasNoHorizon() {
        show(NearbyStationsUiState(loading = false, rows = listOf(row("ITIROL16", chosen = true, selectable = false))))
        rule.onNodeWithTag("station_no_horizon_ITIROL16").assertIsDisplayed()
    }

    @Test
    fun withNoGroundNothingCanBeChosenAndTheReasonIsOnScreen() {
        show(
            NearbyStationsUiState(
                loading = false, heightsUnknown = true,
                rows = listOf(row("ITIROL16", selectable = false), row("ITIROL26", selectable = false)),
            ),
        )
        rule.onNodeWithTag("stations_heights_unknown").assertIsDisplayed()
        rule.onNodeWithTag("station_choose_ITIROL16").assertDoesNotExist()
        rule.onNodeWithTag("station_choose_ITIROL26").assertDoesNotExist()
    }

    @Test
    fun aDisputedHeightIsMarkedOnItsCardAndOnNoOther() {
        show(
            NearbyStationsUiState(
                loading = false,
                rows = listOf(
                    row("ITIROL16"),
                    row("ITIROL25").copy(claimedAltitudeM = 182, demAltitudeM = 631, heightDisputed = true),
                ),
            ),
        )
        rule.onNodeWithTag("station_disputed_ITIROL25").assertIsDisplayed()
        rule.onNodeWithTag("station_disputed_ITIROL16").assertDoesNotExist()
    }
}
