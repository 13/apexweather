package it.apexweather.ui.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import it.apexweather.R
import it.apexweather.data.remote.NowcastCell
import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.NearbyStation
import it.apexweather.domain.Place
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * The map's own controls, driven from a hand-built state. The osmdroid view is not asserted on: a
 * tile-rendering assertion on an emulator tests the network and the tile server, not this app.
 */
class MapContentTest {
    @get:Rule val rule = createComposeRule()

    private val place = Place(
        istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
        lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
        station = NearbyStation("23200MS", "Meran", 46.688, 11.1366, 330, 1.53),
    )

    private val frames = (0 until 13).map {
        RadarFrame(
            Instant.parse("2026-09-10T09:00:00Z").plusSeconds(it * 600L),
            "https://tilecache.rainviewer.com/v2/radar/$it",
        )
    }

    private val steps = (1..6).map {
        NowcastStep(
            Instant.parse("2026-09-10T11:00:00Z").plusSeconds(it * 900L),
            listOf(NowcastCell(46.68, 11.15, 1.5)),
        )
    }

    private fun state(vararg frame: RadarFrame) = MapUiState(
        frames = MapUiState.timeline(frame.toList(), emptyList()),
        selected = 0, playing = false, place = place, loading = false,
    )

    private fun withForecast(selected: Int) = MapUiState(
        frames = MapUiState.timeline(frames, steps),
        selected = selected, playing = false, place = place, loading = false,
    )

    @Test
    fun theTimelineAndTheAttributionAreBothOnScreen() {
        rule.setContent { ApexTheme { MapContent(state(*frames.toTypedArray()), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_timeline").assertIsDisplayed()
        rule.onNodeWithTag("map_scrubber").assertIsDisplayed()
        rule.onNodeWithTag("map_frame_time").assertIsDisplayed()
        rule.onNodeWithTag("map_attribution").assertIsDisplayed()
    }

    @Test
    fun thePlayButtonReportsItsPress() {
        var pressed = false
        rule.setContent { ApexTheme { MapContent(state(*frames.toTypedArray()), onPlayPause = { pressed = true }, onSelect = {}) } }
        rule.onNodeWithTag("map_play_pause").performClick()
        assertTrue(pressed)
    }

    /** No radar is a state the map is built for: the basemap and the marker are still worth drawing. */
    @Test
    fun withNoFramesTheMapSaysSoAndKeepsTheAttribution() {
        rule.setContent { ApexTheme { MapContent(MapUiState(place = place, loading = false), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_radar_unavailable").assertIsDisplayed()
        rule.onNodeWithTag("map_attribution").assertIsDisplayed()
        rule.onNodeWithTag("map_view").assertIsDisplayed()
    }

    /**
     * The legend is not optional furniture: without it the map is a picture, and a reader can see
     * that something is orange over the Pustertal with no way to learn whether that is a shower or
     * a flood.
     */
    @Test
    fun theLegendIsOnScreenWheneverThereIsRainToRead() {
        rule.setContent { ApexTheme { MapContent(state(*frames.toTypedArray()), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_legend").assertIsDisplayed()
    }

    /**
     * Confusing "this happened" with "this is expected" is the one mistake a radar map can make that
     * matters, so the card says which it is showing in a word.
     *
     * The word itself is read out of the resources rather than typed here: CI's emulators run in
     * en-US and the app's default language is German.
     */
    @Test
    fun anObservedFrameIsLabelledAsRadar() {
        val expected = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.map_kind_radar)
        rule.setContent { ApexTheme { MapContent(withForecast(selected = 0), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_frame_kind").assertIsDisplayed()
        rule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun aFrameBeyondTheRadarIsLabelledAsForecast() {
        val expected = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.map_kind_forecast)
        rule.setContent { ApexTheme { MapContent(withForecast(selected = 16), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_frame_kind").assertIsDisplayed()
        rule.onNodeWithText(expected).assertIsDisplayed()
    }

    /** The reader can wander off; the way back is one tap and always in the same corner. */
    @Test
    fun theRecenterButtonIsThereWhenAPlaceIs() {
        rule.setContent { ApexTheme { MapContent(state(*frames.toTypedArray()), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_recenter").assertIsDisplayed()
    }

    /** A single frame has nothing to scrub through, and a slider with one stop cannot be dragged. */
    @Test
    fun oneFrameShowsNoScrubber() {
        rule.setContent { ApexTheme { MapContent(state(frames.first()), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_frame_time").assertIsDisplayed()
        rule.onNodeWithTag("map_scrubber").assertDoesNotExist()
    }
}
