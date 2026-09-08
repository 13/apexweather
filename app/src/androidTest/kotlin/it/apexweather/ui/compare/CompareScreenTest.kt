package it.apexweather.ui.compare

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assert.assertEquals
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
