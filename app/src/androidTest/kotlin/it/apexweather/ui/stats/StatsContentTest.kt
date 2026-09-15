package it.apexweather.ui.stats

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.NearbyStation
import it.apexweather.domain.Place
import it.apexweather.domain.Predicted
import it.apexweather.domain.Quantity
import it.apexweather.domain.VerificationHour
import it.apexweather.domain.model.Source
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Tags and callbacks only: CI's emulators are en-US, so nothing here reads a word. */
class StatsContentTest {
    @get:Rule val rule = createComposeRule()
    private val now: Instant = Instant.parse("2026-09-15T12:00:00Z")

    private val withStation = Place(
        istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
        lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
        station = NearbyStation("23200MS", "Meran", 46.688, 11.1366, 330, 1.53),
    )
    private val withoutStation = withStation.copy(istat = "021115", station = null)

    /** Thirty hours: enough for ICON-D2 and AROME to be ranked. */
    private val hours = (1..30).map { i ->
        val obs = 15.0 + if (i % 5 < 2) 1.0 else -1.0
        VerificationHour(
            now.minusSeconds(i * 3600L), obs, 8.0, 0.0,
            mapOf(LeadBucket.SIX to mapOf(
                Source.ICON_D2 to Predicted(obs + 0.5, 0.0, 8.0),
                Source.GEOSPHERE_AROME to Predicted(obs - 1.5, 0.0, 9.0),
            )),
        )
    }

    private fun state(detail: Source? = null) =
        StatsStateBuilder.build(hours, withStation, Quantity.TEMPERATURE, StatsPeriod.MONTH, LeadBucket.SIX, now, detail)

    private var quantity: Quantity? = null
    private var opened: Source? = null
    private var closed = false
    private var openedSource: Source? = null

    private fun show(s: StatsUiState) = rule.setContent {
        ApexTheme {
            StatsContent(
                s,
                onQuantity = { quantity = it }, onPeriod = {}, onLead = {},
                onOpenDetail = { opened = it }, onCloseDetail = { closed = true },
                onOpenSource = { openedSource = it }, onBack = {},
            )
        }
    }

    @Test
    fun tappingRainChoosesRain() {
        show(state())
        rule.onNodeWithTag("stats_quantity_RAIN").performClick()
        assertEquals(Quantity.RAIN, quantity)
    }

    @Test
    fun tappingAModelRowOpensItsDetail() {
        show(state())
        rule.onNodeWithTag("stats_screen").performScrollToNode(hasTestTag("stats_row_ICON_D2"))
        rule.onNodeWithTag("stats_row_ICON_D2").performClick()
        assertEquals(Source.ICON_D2, opened)
    }

    @Test
    fun theDetailSheetClosesAndLeadsToTheSource() {
        show(state(detail = Source.ICON_D2))
        rule.onNodeWithTag("stats_detail_close").assertExists().performClick()
        assertTrue(closed)
        rule.onNodeWithTag("stats_detail_source").performClick()
        assertEquals(Source.ICON_D2, openedSource)
    }

    @Test
    fun aPlaceWithoutAStationSaysSo() {
        show(StatsStateBuilder.build(emptyList(), withoutStation, Quantity.TEMPERATURE, StatsPeriod.MONTH, LeadBucket.SIX, now, null))
        rule.onNodeWithTag("stats_no_station").assertExists()
        rule.onNodeWithTag("stats_empty").assertDoesNotExist()
    }

    /** Both defaults are true while loading; neither message may appear before the history is read. */
    @Test
    fun loadingClaimsNothing() {
        show(StatsUiState())
        rule.onNodeWithTag("stats_no_station").assertDoesNotExist()
        rule.onNodeWithTag("stats_empty").assertDoesNotExist()
        rule.onNodeWithTag("stats_quantity_RAIN").assertDoesNotExist()
    }
}
