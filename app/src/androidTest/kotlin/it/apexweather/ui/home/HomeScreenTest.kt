package it.apexweather.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import it.apexweather.R
import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.common.labelRes
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    /** Assertions about wording go through the resources: CI runs an en-US emulator, not a German phone. */
    private val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    private fun fc(source: Source, offset: Double, gustKmh: Double? = 21.0) = SourceForecast(
        source, t0, t0,
        hourly = (0 until 168).map {
            HourlyPoint(
                t0.plusSeconds(it * 3600L), 15.0 + offset + it % 8,
                feelsLikeC = 13.0 + offset + it % 8,
                precipMm = if (it % 5 == 0) 1.0 else 0.0,
                windKmh = 6.0, gustKmh = gustKmh, freezingLevelM = 3100.0,
                condition = Condition.PARTLY_CLOUDY,
            )
        },
        daily = emptyList(),
    )
    private val dorfTirol = it.apexweather.domain.Place(
        istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
        lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
        station = it.apexweather.domain.NearbyStation("23200MS", "Meran", 46.688, 11.1366, 330, 1.53),
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = mapOf(Source.ICON_CH1 to fc(Source.ICON_CH1, 0.0), Source.ICON_D2 to fc(Source.ICON_D2, 2.0)))
    private val state = HomeStateBuilder.build(dorfTirol, snapshot, AppSettings(), ConsensusBlender().blend(snapshot.forecasts), t0.plusSeconds(60))

    /** No model publishing a gust is the case where the tile has to be gone, not drawn empty. */
    private val noGustSnapshot = WeatherSnapshot.EMPTY.copy(
        forecasts = mapOf(Source.ICON_CH1 to fc(Source.ICON_CH1, 0.0, gustKmh = null)),
    )
    private val noGustState = HomeStateBuilder.build(
        dorfTirol, noGustSnapshot, AppSettings(),
        ConsensusBlender().blend(noGustSnapshot.forecasts), t0.plusSeconds(60),
    )

    /** Opens the sheet on the first hour of the strip. */
    private fun openHourSheet() {
        rule.onNodeWithTag("hour_column_0").performScrollTo().performClick()
        rule.onNodeWithTag("hour_detail_sheet").assertIsDisplayed()
    }

    /**
     * The station card is a reference, not a headline, and it used to interrupt the forecast between
     * the 48-hour strip and the day list. A section list is exactly the kind of thing that gets
     * reshuffled by accident, so the order is asserted rather than assumed.
     */
    @Test
    fun theStationCardSitsBelowTheDayList() {
        rule.setContent { ApexTheme { HomeContent(withStation, onRefresh = {}, onOpenBulletin = {}) } }
        // Comparing the two cards' coordinates would not work: this is a LazyColumn, and whichever
        // of them is off screen is not in the semantics tree to be measured at all. How far the
        // list had to travel to reach each is the thing that actually says which comes first.
        val list = rule.onNodeWithTag("home_list")
        list.performScrollToNode(hasTestTag("daily_list"))
        val toDayList = scrollOffset()
        list.performScrollToNode(hasTestTag("station_card"))
        val toStation = scrollOffset()
        assertTrue("the station card is above the day list", toStation > toDayList)
    }

    /** How far down the home list is scrolled, in pixels from the top. */
    private fun scrollOffset(): Float =
        rule.onNodeWithTag("home_list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

    /** The station card draws nothing without an observation; the class's other fixtures are forecasts. */
    private val withStation = state.copy(
        station = it.apexweather.domain.model.StationObservation(
            stationName = "Meran", time = t0, tempC = 18.4, humidityPct = 62, windKmh = 7.0,
            windDir = "NO", gustKmh = 19.0, precipTodayMm = 1.2, pressureHpa = 1012.0,
        ),
    )

    @Test
    fun heroShowsConsensusTemperature() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("hero_temp").assertIsDisplayed().assertTextContains("16°")
    }

    /** The hero carries no spread badge; that lives in the hour and day sheets. */
    @Test
    fun heroHasNoAgreementBadge() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("agreement_badge").assertDoesNotExist()
    }

    /**
     * Where the number came from and when it was fetched stand as two lines at the card's left edge,
     * with the temperature. Geometry, not wording — CI's emulator is en-US.
     */
    @Test
    fun heroQuietLinesSitOnTheLeft() {
        rule.setContent { ApexTheme { HomeContent(state.copy(updatedAt = t0), onRefresh = {}, onOpenBulletin = {}) } }
        val temp = rule.onNodeWithTag("hero_temp").fetchSemanticsNode().boundsInRoot
        val source = rule.onNodeWithTag("hero_source").fetchSemanticsNode().boundsInRoot
        val updated = rule.onNodeWithTag("hero_updated").fetchSemanticsNode().boundsInRoot
        assertEquals(temp.left, source.left, 1f)
        assertEquals(source.left, updated.left, 1f)
        assertTrue(updated.top >= source.bottom - 1f)
    }

    /**
     * The icon shares the temperature's line rather than sitting a line below it: the degrees and the
     * picture are the two halves of one answer. Geometry, not wording — CI's emulator is en-US.
     */
    @Test
    fun heroConditionIconSitsBesideTheTemperature() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        val temp = rule.onNodeWithTag("hero_temp").fetchSemanticsNode().boundsInRoot
        val icon = rule.onNodeWithTag("hero_condition_icon").fetchSemanticsNode().boundsInRoot
        assertTrue("icon should start right of the number: icon=$icon temp=$temp", icon.left >= temp.right)
        assertTrue("icon should sit on the number's line: icon=$icon temp=$temp",
            icon.center.y > temp.top && icon.center.y < temp.bottom)
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

    /**
     * The sheet's content on its own, not inside the sheet. A partially expanded `ModalBottomSheet`
     * lays its content out at full height and clips it, so `performScrollTo` brings a row into the
     * *sheet's* viewport, which reaches below the screen — whether it is then displayed depends on
     * the device's height. That passed on the phone and failed on CI's smaller emulator. The route
     * from the strip into the sheet is covered by [hourlyStripScrollsAndOpensDetailSheet] and
     * [hourSheetClosesFromItsCross]; what is asserted here is the content.
     */
    @Test
    fun hourDetailShowsItsStatsAndItsAgreement() {
        rule.setContent { ApexTheme { HourDetail(state.upcomingHours.first(), state) } }
        rule.onNodeWithTag("hour_stat_temp").assertIsDisplayed()
        rule.onNodeWithTag("hour_stat_precip").assertIsDisplayed()
        // The stat tile merges its label, value and sub-line into one TalkBack stop, which also
        // takes the sub-line's own tag out of the default (merged) query tree — reach it unmerged.
        rule.onNodeWithTag("hour_stat_gust", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("hour_agreement_badge").assertIsDisplayed()
        rule.onNodeWithTag("hour_source_header").performScrollTo().assertIsDisplayed()
    }

    /** A quantity nobody publishes is left out rather than drawn as a dash. */
    @Test
    fun hourDetailLeavesOutTheGustNobodyPublishes() {
        rule.setContent { ApexTheme { HourDetail(noGustState.upcomingHours.first(), noGustState) } }
        rule.onNodeWithTag("hour_stat_wind").assertIsDisplayed()
        // Unmerged, so this proves the line was never composed rather than merely swallowed by the
        // tile it would have belonged to.
        rule.onAllNodesWithTag("hour_stat_gust", useUnmergedTree = true).fetchSemanticsNodes().let {
            assertEquals("no model publishes a gust, so there is no gust line", 0, it.size)
        }
    }

    /**
     * The content scrolls, so the sheet needs a cross: once the reader has scrolled, dragging the
     * sheet down scrolls the content back instead of dismissing it.
     */
    @Test
    fun hourSheetClosesFromItsCross() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        openHourSheet()
        rule.onNodeWithTag("hour_detail_close").performClick()
        rule.waitUntil(2_000) { rule.onAllNodesWithTag("hour_detail_sheet").fetchSemanticsNodes().isEmpty() }
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

    /**
     * The day sheet's content scrolls and the sheet skips its half state, so once the reader has
     * scrolled, dragging down scrolls the content back instead of dismissing. The cross is the one
     * way out that works from any scroll position.
     */
    @Test
    fun theCrossClosesTheDaySheet() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("day_row_4"))
        rule.onNodeWithTag("day_row_4").performClick()
        rule.onNodeWithTag("day_detail_sheet").assertIsDisplayed()
        rule.onNodeWithTag("day_detail_close").performClick()
        rule.waitUntil(2_000) { rule.onAllNodesWithTag("day_detail_sheet").fetchSemanticsNodes().isEmpty() }
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
        // The card is last in the list now, so it has to be scrolled to through the LazyColumn
        // rather than with performScrollTo, which only reaches a node already composed.
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("station_card"))
        rule.onNodeWithTag("station_card").assertIsDisplayed()
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

    /**
     * The line that claims how fresh the data is says so when refreshing has been failing.
     *
     * Resolved from the resources rather than asserted as German: CI's emulators are en-US. It stays
     * one line either way, which is the constraint — the hero's vertical budget was measured and its
     * footnote was cut from three lines to two to fit.
     */
    @Test
    fun theUpdatedLineSaysWhenNothingCanBeReached() {
        val stamp = context.getString(R.string.updated_at, "")
        val stale = context.getString(R.string.updated_at_stale, "")

        rule.setContent {
            ApexTheme { HomeContent(state.copy(updatedAt = t0, staleOnScreen = true), onRefresh = {}, onOpenBulletin = {}) }
        }

        val node = rule.onNodeWithTag("hero_updated").fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertTrue("expected the unreachable wording, got: $text", text.contains(stale.trim().substringAfterLast("· ")))
        assertEquals("it should still be one line", 1, text.lines().size)
        assertTrue(stamp.isNotEmpty())
    }

    /** And says nothing of the sort while it is working. */
    @Test
    fun theUpdatedLineIsQuietWhenRefreshingWorks() {
        val stale = context.getString(R.string.updated_at_stale, "")
        rule.setContent {
            ApexTheme { HomeContent(state.copy(updatedAt = t0, staleOnScreen = false), onRefresh = {}, onOpenBulletin = {}) }
        }
        val node = rule.onNodeWithTag("hero_updated").fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertTrue(!text.contains(stale.trim().substringAfterLast("· ")))
    }

    /** A hot hour, built from the models so the whole path is exercised rather than the state faked. */
    private fun hotState(): HomeUiState {
        fun hot(source: Source) = SourceForecast(
            source, t0, t0,
            hourly = (0 until 168).map {
                HourlyPoint(
                    t0.plusSeconds(it * 3600L), 30.0, feelsLikeC = 34.0,
                    precipMm = 0.0, windKmh = 6.0, gustKmh = 21.0,
                    condition = Condition.CLEAR,
                )
            },
            daily = emptyList(),
        )
        val snap = WeatherSnapshot.EMPTY.copy(
            forecasts = mapOf(Source.ICON_CH1 to hot(Source.ICON_CH1), Source.ICON_D2 to hot(Source.ICON_D2)),
        )
        return HomeStateBuilder.build(dorfTirol, snap, AppSettings(), ConsensusBlender().blend(snap.forecasts), t0.plusSeconds(60))
    }

    @Test
    fun hotHourShowsTheFeelsLikeClause() {
        val hot = hotState()
        rule.setContent { ApexTheme { HomeContent(hot, onRefresh = {}, onOpenBulletin = {}) } }
        val expected = context.getString(
            R.string.hero_condition_feels,
            context.getString(Condition.CLEAR.labelRes()),
            "34°",
        )
        rule.onNodeWithTag("hero_feels").assertTextContains(expected)
    }

    /** The default fixture sits in the mild middle, so the hero says nothing about how it feels. */
    @Test
    fun mildHourShowsTheConditionAlone() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("hero_feels")
            .assertTextContains(context.getString(Condition.PARTLY_CLOUDY.labelRes()))
    }

    /**
     * The clause lives inside the condition's own string precisely so this row does not change
     * shape. At a 2x font scale the rain line still has to be on screen beside it — that is the
     * assertion that the FlowRow was not turned into a three-child SpaceBetween.
     */
    @Test
    fun atLargeTextTheRainLineSurvivesTheFeelsLikeClause() {
        val hot = hotState().copy(minutelyStart = t0.plusSeconds(3600))
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = androidx.compose.ui.platform.LocalDensity.current.density,
                    fontScale = 2.0f,
                ),
            ) {
                ApexTheme { HomeContent(hot, onRefresh = {}, onOpenBulletin = {}) }
            }
        }
        rule.onNodeWithTag("hero_feels").assertIsDisplayed()
        rule.onNodeWithTag("rain_starts_at").assertIsDisplayed()
    }

    /**
     * Rain and wind stack on the right under the icon; at a 2x font scale both still have to be
     * on screen. The text is resolved from resources: CI's emulators are en-US.
     */
    @Test
    fun atLargeTextTheWindLineSitsUnderTheRainLine() {
        val windy = hotState().copy(
            minutelyStart = t0.plusSeconds(3600),
            windLine = it.apexweather.domain.StrongWind.Line(
                from = null, peakKmh = 62.0, level = it.apexweather.domain.StrongWind.Level.STRONG,
            ),
        )
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = androidx.compose.ui.platform.LocalDensity.current.density,
                    fontScale = 2.0f,
                ),
            ) {
                ApexTheme { HomeContent(windy, onRefresh = {}, onOpenBulletin = {}) }
            }
        }
        rule.onNodeWithTag("rain_starts_at").assertIsDisplayed()
        rule.onNodeWithTag("wind_line", useUnmergedTree = true).assertIsDisplayed()
        val expected = context.getString(R.string.wind_gusts_now, "62 km/h")
        rule.onNodeWithText(expected).assertIsDisplayed()
    }

    /** A calm strip has no gust row at all; one windy hour gives every column the row. */
    @Test
    fun onlyAWindyStripCarriesTheGustRow() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onAllNodesWithTag("hour_column_wind_0", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aWindyHourIsMarkedInTheStrip() {
        val hours = state.upcomingHours.mapIndexed { i, h -> if (i == 1) h.copy(gustMedianKmh = 80.0) else h }
        rule.setContent { ApexTheme { HomeContent(state.copy(upcomingHours = hours), onRefresh = {}, onOpenBulletin = {}) } }
        rule.onAllNodesWithTag("hour_column_wind_0", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithTag("hour_column_1").assertContentDescriptionContains(
            context.getString(R.string.wind_storm), substring = true,
        )
    }
}
