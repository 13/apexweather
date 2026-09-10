package it.apexweather.ui.place

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import it.apexweather.domain.Place
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlacePickerTest {
    @get:Rule val rule = createComposeRule()

    private val places = listOf(
        Place("021101", "Dorf Tirol", "Tirolo", "Tirol", 46.691, 11.155, 594, 2),
        Place("021115", "Sterzing", "Vipiteno", "Vipiteno", 46.895, 11.434, 948, 5),
    )

    private fun show(
        state: PlacePickerUiState,
        onPick: (String) -> Unit = {},
        onQuery: (String) -> Unit = {},
        onBack: () -> Unit = {},
    ) = rule.setContent {
        ApexTheme { PlacePickerContent(state, onQuery = onQuery, onPick = onPick, onBack = onBack) }
    }

    @Test
    fun everyPlaceIsListedAndTheCurrentOneIsMarked() {
        show(PlacePickerUiState(places = places, selected = "021101"))
        rule.onNodeWithTag("place_row_021101").assertIsDisplayed()
        rule.onNodeWithTag("place_row_021115").assertIsDisplayed()
        rule.onNodeWithTag("place_selected_021101", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun typingReachesTheSearch() {
        var typed = ""
        show(PlacePickerUiState(places = places, selected = "021101"), onQuery = { typed = it })
        rule.onNodeWithTag("place_search").performTextInput("ster")
        assertEquals("ster", typed)
    }

    @Test
    fun choosingAPlaceReportsIt() {
        var picked: String? = null
        show(PlacePickerUiState(places = places, selected = "021101"), onPick = { picked = it })
        rule.onNodeWithTag("place_row_021115").performClick()
        assertEquals("021115", picked)
    }

    /** A search that matches nothing has to say so; an empty screen reads as a broken one. */
    @Test
    fun anEmptyResultSaysSoRatherThanShowingNothing() {
        show(PlacePickerUiState(places = emptyList(), selected = "021101", query = "zzz"))
        rule.onNodeWithTag("place_empty").assertIsDisplayed()
    }

    @Test
    fun backLeavesWithoutChoosing() {
        var back = false
        var picked: String? = null
        show(PlacePickerUiState(places = places, selected = "021101"), onPick = { picked = it }, onBack = { back = true })
        rule.onNodeWithTag("place_back").performClick()
        assertEquals(true, back)
        assertEquals(null, picked)
    }
}
