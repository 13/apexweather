package it.apexweather.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.NearbyStation
import it.apexweather.domain.Place
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.home.HomeContent
import it.apexweather.ui.home.HomeStateBuilder
import it.apexweather.ui.home.HomeUiState
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale

/**
 * The share button, the preview it opens, and the one promise the card makes about its own size.
 *
 * Nothing here fires a real `ACTION_SEND`: what a chooser does with a Uri is Android's business, and
 * what this app has to get right is which day is being shared, whether it can be shared at all, and
 * whether the picture is the same picture whoever sends it.
 *
 * Assertions about wording would go through the resources — CI runs an en-US emulator, not a German
 * phone — but none are needed here, because every assertion below is about structure.
 */
class ShareSheetTest {
    @get:Rule val rule = createComposeRule()

    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")

    private fun fc(source: Source, offset: Double) = SourceForecast(
        source, t0, t0,
        hourly = (0 until 168).map {
            HourlyPoint(
                t0.plusSeconds(it * 3600L), 15.0 + offset + it % 8,
                feelsLikeC = 13.0 + offset + it % 8,
                precipMm = if (it % 5 == 0) 1.0 else 0.0,
                windKmh = 6.0, gustKmh = 21.0, freezingLevelM = 3100.0,
                condition = Condition.PARTLY_CLOUDY,
            )
        },
        daily = emptyList(),
    )

    private val place = Place(
        istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
        lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
        station = NearbyStation("23200MS", "Meran", 46.688, 11.1366, 330, 1.53),
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(
        forecasts = mapOf(Source.ICON_CH1 to fc(Source.ICON_CH1, 0.0), Source.ICON_D2 to fc(Source.ICON_D2, 2.0)),
    )
    private val state = HomeStateBuilder.build(
        place, snapshot, AppSettings(), ConsensusBlender().blend(snapshot.forecasts), t0.plusSeconds(60),
    )
    private val empty = HomeStateBuilder.build(
        place, WeatherSnapshot.EMPTY, AppSettings(), ConsensusBlender().blend(emptyMap()), t0,
    )

    private fun showHome(s: HomeUiState = state) {
        rule.setContent { ApexTheme { HomeContent(state = s, onRefresh = {}, onOpenBulletin = {}) } }
    }

    @Test
    fun theHeroOffersToShareToday() {
        showHome()
        rule.onNodeWithTag("share_today").assertIsEnabled().performClick()
        rule.onNodeWithTag("share_sheet").assertIsDisplayed()
        rule.onNodeWithTag("share_card").assertIsDisplayed()
        rule.onNodeWithTag("share_sheet_send").assertIsDisplayed()
    }

    @Test
    fun theSheetClosesOnItsCross() {
        showHome()
        rule.onNodeWithTag("share_today").performClick()
        rule.onNodeWithTag("share_sheet").assertIsDisplayed()
        rule.onNodeWithTag("share_sheet_close").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("share_sheet").fetchSemanticsNodes().size)
    }

    /**
     * Drawn dim rather than hidden before the first fetch: "not yet" and "never" are different
     * messages, and the first one resolves itself in a few seconds.
     *
     * The empty state replaces the whole list, so the hero's button is not on screen — the assertion
     * that matters is that the card itself refuses to be built from a state with nothing in it,
     * which is what leaves the button with nothing to open.
     */
    @Test
    fun nothingCanBeSharedBeforeTheFirstFetch() {
        assertNull(ShareCardStateBuilder.today(empty, Locale.US))
    }

    /** The day sheet shares the day it is about, and never today. */
    @Test
    fun theDaySheetSharesItsOwnDay() {
        showHome()
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("day_row_4"))
        rule.onNodeWithTag("day_row_4").performClick()
        rule.onNodeWithTag("day_detail_share").performScrollTo().performClick()
        rule.onNodeWithTag("share_card").assertIsDisplayed()

        val shared = ShareCardStateBuilder.day(state, state.days[4].date, Locale.US)!!
        assertNotEquals(state.days[0].date, shared.date)
        assertNull("a day is not a measurement", shared.adjustmentC)
    }

    /**
     * The one promise the card makes to the person receiving it: it is the same picture whatever the
     * sender's text size is.
     *
     * Everywhere else in this app a larger setting is honoured all the way down — `CompactLabel`
     * exists because two controls could not do that and had to be capped, and even there the icon
     * beside the label still scales. Here the output is a bitmap that leaves the phone: at a 2x
     * scale the eight columns would not fit the fixed width, so honouring the setting would not even
     * serve the reader who set it. Both scales are composed side by side in one composition, because
     * a rule may only be given content once and two rules would not be comparable.
     */
    @Test
    fun theCardIsTheSameSizeAtEveryFontScale() {
        val card = ShareCardStateBuilder.today(state, Locale.US)!!
        rule.setContent {
            ApexTheme {
                CompositionLocalProvider(LocalFormats provides Formats(Locale.US, true)) {
                    val base = LocalDensity.current
                    // Scrollable, so the column's max height is unbounded. A plain Column hands
                    // its last child whatever height is left on screen, which squeezed the second
                    // card and made this test fail for a reason that had nothing to do with fonts.
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        listOf(1f, 2f).forEach { scale ->
                            CompositionLocalProvider(
                                LocalDensity provides Density(base.density, fontScale = scale),
                            ) {
                                Box(Modifier.testTag("scaled_$scale")) { ShareCard(card) }
                            }
                        }
                    }
                }
            }
        }
        val one = rule.onNodeWithTag("scaled_1.0").getUnclippedBoundsInRoot()
        val two = rule.onNodeWithTag("scaled_2.0").getUnclippedBoundsInRoot()
        assertEquals((one.right - one.left).value, (two.right - two.left).value, 0.5f)
        assertEquals((one.bottom - one.top).value, (two.bottom - two.top).value, 0.5f)
    }

    /** And the disabled case, driven through the hero rather than through the builder. */
    @Test
    fun theHeroShareButtonIsDimWhileTheHeroHasNoPlace() {
        showHome(state.copy(place = null))
        rule.onNodeWithTag("share_today").assertIsNotEnabled()
    }
}
