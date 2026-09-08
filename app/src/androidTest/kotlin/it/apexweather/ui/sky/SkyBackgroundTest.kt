package it.apexweather.ui.sky

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import it.apexweather.domain.SkyPaletteSelector
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Condition
import it.apexweather.ui.theme.ApexTheme
import org.junit.Rule
import org.junit.Test

class SkyBackgroundTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun rendersEveryParticleKindWithoutCrashing() {
        val palettes = listOf(
            SkyPaletteSelector.select(Condition.CLEAR, SunPhase.NIGHT, 0.0),
            SkyPaletteSelector.select(Condition.RAIN, SunPhase.DAY, 3.0),
            SkyPaletteSelector.select(Condition.SNOW, SunPhase.DUSK, 2.0),
            SkyPaletteSelector.select(Condition.THUNDERSTORM, SunPhase.NIGHT, 5.0),
            SkyPaletteSelector.select(Condition.FOG, SunPhase.DAWN, 0.0),
            SkyPaletteSelector.select(Condition.PARTLY_CLOUDY, SunPhase.DAY, 0.0),
        )
        val current = mutableStateOf(palettes.first())
        rule.setContent { ApexTheme { SkyBackground(palette = current.value, animationsEnabled = true) } }
        palettes.forEach { p ->
            rule.runOnIdle { current.value = p }
            rule.mainClock.advanceTimeBy(500)
            rule.onNodeWithTag("sky").assertIsDisplayed()
        }
    }
}
