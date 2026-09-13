package it.apexweather.ui.place

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import it.apexweather.domain.Place
import it.apexweather.ui.theme.ApexTheme
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        onFavourite: (String, Boolean) -> Unit = { _, _ -> },
        onBack: () -> Unit = {},
    ) = rule.setContent {
        ApexTheme {
            PlacePickerContent(state, onQuery = onQuery, onPick = onPick, onFavourite = onFavourite, onBack = onBack)
        }
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

    /**
     * Starring while searching, which is how anybody actually pins a place: you type the name of
     * the valley you are going to, and star it in the results.
     *
     * It did not work, and the state is why. `favourites` is the *section* at the head of the list
     * and is deliberately empty while the reader is typing — a pinned Bozen floating over a search
     * for "Brixen" is noise — and the row was reading its pinned-ness out of that same field. So
     * during a search every row believed itself unpinned, which broke three things at once. This is
     * the worst of them: with four pins already spent, `canPinMore` is false and no row can claim
     * to be one of the four, so every star in the results is *disabled*. Tapping did nothing at all.
     */
    @Test
    fun aPinnedPlaceCanBeUnpinnedFromSearchResultsWithTheListFull() {
        var call: Pair<String, Boolean>? = null
        show(
            PlacePickerUiState(
                places = places,
                selected = "021101",
                query = "tir",
                // The section is empty while searching; membership is not.
                favourites = emptyList(),
                pinnedIstats = setOf("021101"),
                canPinMore = false,
            ),
            onFavourite = { istat, on -> call = istat to on },
        )
        rule.onNodeWithTag("place_pin_021101", useUnmergedTree = true).performClick()
        assertEquals("a pinned place must be unpinnable from search results", "021101" to false, call)
    }

    /** And the star has to show the truth while searching, not a hollow one for everything. */
    @Test
    fun searchResultsShowWhichPlacesArePinned() {
        show(
            PlacePickerUiState(
                places = places,
                selected = "021101",
                query = "t",
                favourites = emptyList(),
                pinnedIstats = setOf("021115"),
                canPinMore = true,
            ),
        )
        val pinned = rule.onNodeWithTag("place_row_021115").fetchSemanticsNode()
            .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
            ?.joinToString().orEmpty()
        assertTrue("a pinned place must say so while searching: $pinned", pinned.contains("gemerkt", ignoreCase = true) || pinned.contains("pinned", ignoreCase = true))
    }

    /** A place that is not pinned, with room left, still pins normally from the results. */
    @Test
    fun anUnpinnedPlacePinsFromSearchResults() {
        var call: Pair<String, Boolean>? = null
        show(
            PlacePickerUiState(places = places, selected = "021101", query = "ster", pinnedIstats = emptySet()),
            onFavourite = { istat, on -> call = istat to on },
        )
        rule.onNodeWithTag("place_pin_021115", useUnmergedTree = true).performClick()
        assertEquals("021115" to true, call)
    }
}
