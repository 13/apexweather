package it.apexweather.ui.compare

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertNotEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import it.apexweather.data.AppSettings
import it.apexweather.data.CompareVariable
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.theme.ApexTheme
import androidx.compose.ui.semantics.SemanticsNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class CompareScreenTest {
    @get:Rule val rule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    private fun fc(s: Source, off: Double) = SourceForecast(s, t0, t0, (0 until 72).map { HourlyPoint(t0.plusSeconds(it * 3600L), 10.0 + off, condition = Condition.CLEAR) }, emptyList())
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = mapOf(Source.ICON_CH1 to fc(Source.ICON_CH1, 0.0), Source.ICON_D2 to fc(Source.ICON_D2, 3.0)))
    private val state = CompareStateBuilder.build(snapshot, AppSettings(), ConsensusBlender().blend(snapshot.forecasts), t0)

    /**
     * The screen is a lazy list, so anything below the fold is not composed at all. Scroll to it
     * first: on a short screen these assertions otherwise fail for want of a pixel, not a bug.
     */
    private fun scrollTo(tag: String) =
        rule.onNodeWithTag("compare_list").performScrollToNode(hasTestTag(tag))

    @Test
    fun chartTableAndStatusRender() {
        rule.setContent { ApexTheme { CompareContent(state, {}, {}) } }
        rule.onNodeWithTag("compare_chart").assertIsDisplayed()
        scrollTo("chip_ICON_D2")
        rule.onNodeWithTag("chip_ICON_D2").assertIsDisplayed()
        scrollTo("day_table")
        rule.onNodeWithTag("day_table").assertIsDisplayed()
        scrollTo("status_list")
        rule.onNodeWithTag("status_list").assertIsDisplayed()
    }

    /**
     * The chart is a bare canvas and the table shows deviation as a background tint, so both used
     * to be silent to a screen reader and invisible without colour vision.
     */
    @Test
    fun theChartAndTheDayCellsDescribeThemselves() {
        rule.setContent { ApexTheme { CompareContent(state, {}, {}) } }
        val chart = rule.onNodeWithTag("compare_chart").fetchSemanticsNode()
        val chartDescription = chart.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertTrue("the chart says nothing", chartDescription.isNotEmpty())

        // ICON_D2 runs 3 degrees above ICON_CH1, so its cells sit off the consensus and must say so.
        scrollTo("day_table")
        val spoken = rule.onNodeWithTag("day_table").fetchSemanticsNode()
            .let { collectDescriptions(it) }
        assertTrue("no day cell described itself: $spoken", spoken.any { it.contains(Source.ICON_D2.displayName) })
    }

    private fun collectDescriptions(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.children.flatMap { collectDescriptions(it) }

    @Test
    fun chipAndVariableCallbacksFire() {
        var toggled: Source? = null
        var variable: CompareVariable? = null
        rule.setContent { ApexTheme { CompareContent(state, { toggled = it }, { variable = it }) } }
        scrollTo("chip_ICON_D2")
        rule.onNodeWithTag("chip_ICON_D2").performClick()
        scrollTo("variable_WIND")
        rule.onNodeWithTag("variable_WIND").performClick()
        assertEquals(Source.ICON_D2, toggled)
        assertEquals(CompareVariable.WIND, variable)
    }

    /** The chips exist for the days the consensus reaches, plus the sweep the screen opens on. */
    @Test
    fun theDayChipsCoverTheDaysThatExist() {
        var chosen: DaySelection? = null
        rule.setContent { ApexTheme { CompareContent(state, {}, {}, { chosen = it }) } }
        rule.onNodeWithTag("day_sweep").assertIsDisplayed()
        rule.onNodeWithTag("day_0").assertIsDisplayed().performClick()
        assertEquals(DaySelection.Day(0), chosen)
        rule.onNodeWithTag("day_1").performClick()
        assertEquals(DaySelection.Day(1), chosen)
    }

    /**
     * Touching the chart used to do nothing. The values are shown outside the canvas, so this
     * asserts what a reader can actually read rather than what was drawn.
     */
    @Test
    fun touchingTheChartMovesTheReadoutToThatHour() {
        rule.setContent { ApexTheme { CompareContent(state, {}, {}, {}) } }
        val before = readoutHour()

        val chart = rule.onNodeWithTag("compare_chart")
        val size = chart.fetchSemanticsNode().size
        chart.performTouchInput { down(Offset(size.width * 0.85f, size.height / 2f)); up() }
        rule.waitForIdle()

        assertNotEquals("the readout did not follow the touch", before, readoutHour())
    }

    /** Releasing must leave the values on screen; they are useless if they vanish with the finger. */
    @Test
    fun theReadoutSurvivesLiftingTheFinger() {
        rule.setContent { ApexTheme { CompareContent(state, {}, {}, {}) } }
        val chart = rule.onNodeWithTag("compare_chart")
        val size = chart.fetchSemanticsNode().size
        chart.performTouchInput { down(Offset(size.width * 0.7f, size.height / 2f)); up() }
        rule.waitForIdle()
        val afterLift = readoutHour()
        rule.waitForIdle()
        assertEquals(afterLift, readoutHour())
    }

    private fun readoutHour(): String =
        rule.onNodeWithTag("readout_hour").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }

}
