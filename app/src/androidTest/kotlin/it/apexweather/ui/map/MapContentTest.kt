package it.apexweather.ui.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

    private fun state(vararg frame: RadarFrame) = MapUiState(
        frames = frame.toList(), selected = 0, playing = false, place = place, loading = false,
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

    /** A single frame has nothing to scrub through, and a slider with one stop cannot be dragged. */
    @Test
    fun oneFrameShowsNoScrubber() {
        rule.setContent { ApexTheme { MapContent(state(frames.first()), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_frame_time").assertIsDisplayed()
        rule.onNodeWithTag("map_scrubber").assertDoesNotExist()
    }
}
