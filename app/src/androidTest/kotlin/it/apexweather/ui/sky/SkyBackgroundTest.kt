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
     * With the switch off the sky still changes colour — it simply arrives at once. Two frames after
     * the palette changes, far short of the 1.5 s crossfade, the new colour has to be on screen.
     * This is also the one test in the file that may let the rule idle: with motion off there is no
     * frame loop asking for another frame forever.
     */
    @Test
    fun paletteArrivesAtOnceWhenAnimationsAreOff() {
        val day = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.DAY, 0.0)
        val night = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.NIGHT, 0.0)
        val current = mutableStateOf(day)
        rule.mainClock.autoAdvance = false
        rule.setContent { ApexTheme { SkyBackground(palette = current.value, animationsEnabled = false) } }
        rule.mainClock.advanceTimeBy(64)
        rule.runOnIdle { current.value = night }
        rule.mainClock.advanceTimeBy(32)
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
