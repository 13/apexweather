package it.apexweather.ui.sky

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import it.apexweather.domain.SkyPaletteSelector
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Condition
import it.apexweather.ui.theme.ApexTheme
import it.apexweather.ui.theme.fromArgb
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

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
        rule.mainClock.autoAdvance = false
        rule.setContent { ApexTheme { SkyBackground(palette = current.value, animationsEnabled = true) } }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("sky").assertIsDisplayed()
        palettes.forEach { p ->
            rule.runOnIdle { current.value = p }
            rule.mainClock.advanceTimeBy(500)
            rule.onNodeWithTag("sky").assertIsDisplayed()
        }
    }

    /**
     * With the switch off there is no particle frame loop and no crossfade `tween` — both gates
     * `motion` guards — so this is the one test in the file that leaves `autoAdvance` at its default
     * `true` and lets the rule go idle: a running frame loop would hang it, and a `tween` spec would
     * leave the old colour on screen for a frame `waitForIdle()` does not wait through. The palette
     * change has to arrive between one idle point and the next, not over a crossfade.
     */
    @Test
    fun paletteArrivesAtOnceWhenAnimationsAreOff() {
        val day = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.DAY, 0.0)
        val night = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.NIGHT, 0.0)
        val current = mutableStateOf(day)
        rule.setContent { ApexTheme { SkyBackground(palette = current.value, animationsEnabled = false) } }
        rule.runOnIdle { current.value = night }
        rule.waitForIdle()
        val pixel = rule.onNodeWithTag("sky").captureToImage().toPixelMap()[2, 1]
        assertNear(Color.fromArgb(night.top), pixel)
    }

    /** The gradient interpolates from its first stop, so the topmost row is the top colour but not to the bit. */
    private fun assertNear(expected: Color, actual: Color) {
        val delta = maxOf(
            abs(expected.red - actual.red),
            abs(expected.green - actual.green),
            abs(expected.blue - actual.blue),
        )
        assertTrue("expected about $expected, got $actual", delta < 0.05f)
    }
}
