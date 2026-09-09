package it.apexweather.ui.compare

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

    @Test
    fun chartTableAndStatusRender() {
        rule.setContent { ApexTheme { CompareContent(state, {}, {}) } }
        rule.onNodeWithTag("compare_chart").assertIsDisplayed()
        rule.onNodeWithTag("chip_ICON_D2").assertIsDisplayed()
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
        rule.onNodeWithTag("chip_ICON_D2").performClick()
        rule.onNodeWithTag("variable_WIND").performClick()
        assertEquals(Source.ICON_D2, toggled)
        assertEquals(CompareVariable.WIND, variable)
    }
}
