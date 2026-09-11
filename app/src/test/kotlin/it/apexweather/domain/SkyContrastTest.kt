package it.apexweather.domain

import it.apexweather.domain.model.Condition
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every word on the home screen is white, drawn straight onto the sky. A palette light enough to
 * swallow it is therefore not a matter of taste but a bug, and on 2026-09-11 at 06:00 it was one:
 * the dawn sky under broken cloud ended at rgb(228, 160, 122), which carries white text at 2,2:1.
 * WCAG asks 4,5:1 of body text.
 *
 * The floor is enforced in [SkyPaletteSelector] rather than by hand-picking colours, so a palette
 * added later cannot quietly reintroduce it.
 */
class SkyContrastTest {
    private val phases = SunPhase.entries
    private val precipitations = listOf(0.0, 0.4, 3.0, 12.0)

    @Test
    fun `every sky carries white text`() {
        val failures = mutableListOf<String>()
        for (condition in Condition.entries) {
            for (phase in phases) {
                for (mm in precipitations) {
                    val palette = SkyPaletteSelector.select(condition, phase, mm)
                    for ((name, colour) in listOf("top" to palette.top, "mid" to palette.mid, "bottom" to palette.bottom)) {
                        val ratio = SkyContrast.withWhite(colour)
                        if (ratio < SkyContrast.MIN_RATIO) {
                            failures += "$condition/$phase/$mm $name is ${"%.2f".format(ratio)}:1"
                        }
                    }
                }
            }
        }
        assertTrue("skies too light for white text:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** Darkening may not turn a sunrise grey: the hue has to survive it. */
    @Test
    fun `darkening keeps the colour's hue`() {
        val dawn = SkyPaletteSelector.select(Condition.PARTLY_CLOUDY, SunPhase.DAWN, 0.0)
        val r = (dawn.bottom shr 16 and 0xFF).toInt()
        val g = (dawn.bottom shr 8 and 0xFF).toInt()
        val b = (dawn.bottom and 0xFF).toInt()
        assertTrue("a dawn sky should still be warmest in red, got r=$r g=$g b=$b", r > g && g > b)
    }

    /** A sky already dark enough is left exactly as it was written. */
    @Test
    fun `a night sky is untouched`() {
        val night = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.NIGHT, 0.0)
        assertTrue("night was darkened needlessly: ${night.top.toString(16)}", night.top == 0xFF05081AL)
    }
}
