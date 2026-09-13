package it.apexweather.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A label in a control whose width is decided by something other than the label.
 *
 * Measured on the phone before this existed: at a 2x font scale the navigation bar's "Vergleich"
 * wrapped to "Vergl / eich" and stood 64 dp tall in the 32 dp an item is given, and "Niederschlag"
 * did the same inside its segmented button. Five items share 384 dp whatever the text size says, so
 * no arrangement fixes it — the label's own growth is what has to give.
 *
 * Every scale is composed in one pass, because a Compose rule takes its content once.
 */
class CompactLabelTest {

    @get:Rule val rule = createComposeRule()

    private val scales = listOf(0.85f, 1.0f, MAX_FONT_SCALE, 2.0f)

    private fun widths(): Map<Float, Pair<Int, Int>> {
        rule.setContent {
            val base = LocalDensity.current
            Column {
                scales.forEach { scale ->
                    CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                        CompactLabel { Text("Vergleich", Modifier.testTag("capped_$scale")) }
                        Text("Vergleich", Modifier.testTag("plain_$scale"))
                    }
                }
            }
        }
        return scales.associateWith { scale ->
            rule.onNodeWithTag("capped_$scale").fetchSemanticsNode().size.width to
                rule.onNodeWithTag("plain_$scale").fetchSemanticsNode().size.width
        }
    }

    @Test
    fun theLabelStopsGrowingAtTheCeiling() {
        val (capped, plain) = widths().getValue(2.0f)
        assertTrue("a capped label must be narrower than one that scaled all the way ($capped vs $plain)", capped < plain)
    }

    /**
     * The ceiling is a ceiling and not a target: at the ordinary size, below it, and at the ceiling
     * itself, a capped label is exactly the label. A reader who made the text *smaller* gets what
     * they asked for.
     */
    @Test
    fun nothingAtOrBelowTheCeilingIsTouched() {
        val measured = widths()
        listOf(0.85f, 1.0f, MAX_FONT_SCALE).forEach { scale ->
            val (capped, plain) = measured.getValue(scale)
            assertEquals("scale $scale must be left alone", plain, capped)
        }
    }
}
