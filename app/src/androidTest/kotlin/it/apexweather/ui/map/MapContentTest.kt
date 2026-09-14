package it.apexweather.ui.map

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import it.apexweather.R
import it.apexweather.data.remote.NowcastCell
import it.apexweather.data.remote.NowcastKind
import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.NearbyStation
import it.apexweather.domain.Place
import it.apexweather.domain.RadarReading
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
        rule.onNodeWithTag("map_ribbon").assertIsDisplayed()
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
            .getString(R.string.map_kind_nowcast)
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

    /** A single frame has nothing to scrub through, and a ribbon with one bar cannot be dragged. */
    @Test
    fun oneFrameShowsNoRibbon() {
        rule.setContent { ApexTheme { MapContent(state(frames.first()), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_frame_time").assertIsDisplayed()
        rule.onNodeWithTag("map_ribbon").assertDoesNotExist()
    }

    /** The radar's word overrules the forecast's first hour near the place, and the card says so in a word. */
    @Test
    fun anUnconfirmedStepIsLabelledUncertainAndSaysWhy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val times = frames.map { it.time }
        val check = PlaceCheck(place.lat, place.lon, times.associateWith { RadarReading.NO_ECHO })
        val state = MapUiState(
            frames = MapUiState.timeline(frames, steps, check), check = check,
            selected = 13, playing = false, place = place, loading = false,
        )
        rule.setContent { ApexTheme { MapContent(state, onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithText(context.getString(R.string.map_kind_unconfirmed)).assertIsDisplayed()
        rule.onNodeWithTag("map_radar_overrule").assertIsDisplayed()
    }

    @Test
    fun theZoomChipsReportTheirChoice() {
        var chosen: MapZoom? = null
        rule.setContent { ApexTheme { MapContent(withForecast(selected = 0), onPlayPause = {}, onSelect = {}, onZoom = { chosen = it }) } }
        rule.onNodeWithTag("map_zoom_today").performClick()
        assertTrue(chosen == MapZoom.TODAY)
    }

    /** The place's own word is on the card, so "does it reach me" needs no playing. */
    @Test
    fun theCardNamesThePlaceAndItsRain() {
        rule.setContent { ApexTheme { MapContent(withForecast(selected = 16), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_place_word").assertIsDisplayed()
    }

    @Test
    fun whileTilesLoadThePlayButtonSaysSo() {
        rule.setContent { ApexTheme { MapContent(state(*frames.toTypedArray()), onPlayPause = {}, onSelect = {}, ready = false) } }
        rule.onNodeWithTag("map_play_loading").assertIsDisplayed()
    }

    /** Colour alone is not accessible; the chip that is on has to say so in its semantics too. */
    @Test
    fun theSelectedZoomChipSaysSo() {
        lateinit var mapState: MutableState<MapUiState>
        rule.setContent {
            mapState = remember { mutableStateOf(withForecast(selected = 0)) }
            ApexTheme { MapContent(mapState.value, onPlayPause = {}, onSelect = {}) }
        }
        rule.onNodeWithTag("map_zoom_now").assertIsSelected()
        rule.onNodeWithTag("map_zoom_today").assertIsNotSelected()
        rule.runOnIdle { mapState.value = mapState.value.withZoom(MapZoom.TODAY) }
        rule.onNodeWithTag("map_zoom_today").assertIsSelected()
        rule.onNodeWithTag("map_zoom_now").assertIsNotSelected()
    }

    /**
     * "vor 20 min" (or "in 20 min") on the very step the ribbon itself calls "jetzt" would
     * contradict it — the outlook is hourly and the present rarely lands on the hour, so the
     * jetzt step's own timestamp almost never equals [MapUiState.presentTime] exactly, even
     * though it is the present step.
     */
    @Test
    fun theJetztStepCarriesNoRelativeTime() {
        // The radar's last frame (presentTime) lands on the hour; the outlook's first step is
        // deliberately twenty minutes off it, exactly as GeoSphere's real hourly steps are.
        val outlookStart = Instant.parse("2026-09-10T11:20:00Z")
        val outlook = (0 until 24).map {
            MapFrame.Forecast(
                NowcastStep(outlookStart.plusSeconds(it * 3600L), listOf(NowcastCell(place.lat, place.lon, 0.0)), NowcastKind.OUTLOOK),
            )
        }
        val today = MapUiState(
            frames = MapUiState.timeline(frames, steps), outlook = outlook,
            zoom = MapZoom.TODAY, selected = 0, playing = false, place = place, loading = false,
        )
        rule.setContent { ApexTheme { MapContent(today, onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_frame_offset").assertDoesNotExist()
    }
}
